package ai.devin.mend.engine;

import ai.devin.mend.config.MendProperties;
import ai.devin.mend.devin.DevinApiClient;
import ai.devin.mend.devin.DevinDtos;
import ai.devin.mend.domain.RemediationTask;
import ai.devin.mend.domain.SuccessCriteria;
import ai.devin.mend.domain.Verification;
import ai.devin.mend.github.GitHubClient;
import ai.devin.mend.github.GitHubDtos;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Decides whether anything other than the session that wrote the code agrees the fix works.
 *
 * <p>Tiers are tried strongest first — the repository's own required checks, then the menD contract
 * workflow running the agreed commands inside the repository's CI, then a separate Devin session that
 * may only run commands. When none of them can answer, the answer is {@link
 * Verification.Verdict#UNAVAILABLE}, never a green light: menD does not run arbitrary repository test
 * suites inside its own container, because one image cannot hold every toolchain and executing
 * untrusted repository code would be a far worse problem than an unverified pull request.
 */
@Component
public class Verifier {

    private static final Logger log = LoggerFactory.getLogger(Verifier.class);

    private final GitHubClient github;
    private final DevinApiClient devin;
    private final PromptBuilder prompts;
    private final ObjectMapper mapper;
    private final MendProperties props;

    public Verifier(
            GitHubClient github,
            DevinApiClient devin,
            PromptBuilder prompts,
            ObjectMapper mapper,
            MendProperties props) {
        this.github = github;
        this.devin = devin;
        this.prompts = prompts;
        this.mapper = mapper;
        this.props = props;
    }

    /**
     * Produces the current verdict for a task's pull request. May mutate {@code task} to record a
     * verifier session it started or a contract workflow it dispatched; the caller persists it.
     */
    public Verification verify(RemediationTask task, SuccessCriteria criteria, int pullNumber) {
        List<GitHubDtos.CheckRun> runs = github.checkRuns(task.getRepo(), pullNumber);
        String prefix = props.getVerify().getContractCheckPrefix();
        List<GitHubDtos.CheckRun> contract = runs.stream()
                .filter(r -> r.name() != null && r.name().startsWith(prefix))
                .toList();
        List<GitHubDtos.CheckRun> repoChecks =
                runs.stream().filter(r -> !contract.contains(r)).toList();

        if (!repoChecks.isEmpty()) {
            return fromChecks(Verification.Tier.REPO_CI, repoChecks);
        }
        if (!contract.isEmpty()) {
            return fromChecks(Verification.Tier.CONTRACT_WORKFLOW, contract);
        }
        GitHubDtos.CiVerdict legacy = github.ciVerdict(task.getRepo(), pullNumber);
        if (legacy != GitHubDtos.CiVerdict.NONE) {
            return new Verification(
                    Verification.Tier.REPO_CI, verdictOf(legacy), "commit status: " + legacy, List.of(), task.getPrUrl());
        }

        Verification dispatched = tryContractWorkflow(task, criteria, pullNumber);
        if (dispatched != null) {
            return dispatched;
        }
        return verifierSession(task, criteria);
    }

    // -------------------------------------------------------------- tier two

    /**
     * Asks the repository to run the agreed commands itself, through the {@code mend-verify} workflow
     * a human merged into it. Dispatched at most once per task; the resulting check run is picked up
     * on a later pass as {@link Verification.Tier#CONTRACT_WORKFLOW}.
     */
    private Verification tryContractWorkflow(RemediationTask task, SuccessCriteria criteria, int pullNumber) {
        if (task.getContractDispatchedAt() != null) {
            return waitingFor(
                    Verification.Tier.CONTRACT_WORKFLOW,
                    task.getContractDispatchedAt(),
                    "the menD verification contract workflow has not reported a check run yet");
        }
        if (criteria == null || criteria.verificationCommands().isEmpty()) {
            return null;
        }
        Optional<GitHubDtos.PullRequest> pr = github.getPullRequest(task.getRepo(), pullNumber);
        if (pr.isEmpty() || pr.get().head() == null) {
            return null;
        }
        boolean dispatched = github.dispatchWorkflow(
                task.getRepo(),
                props.getVerify().getContractWorkflow(),
                pr.get().head().ref(),
                java.util.Map.of(
                        "pull_request", String.valueOf(pullNumber),
                        "commands", String.join("\n", criteria.verificationCommands())));
        if (!dispatched) {
            return null;
        }
        task.setContractDispatchedAt(Instant.now());
        log.info("dispatched the verification contract workflow for {}", task.key());
        return new Verification(
                Verification.Tier.CONTRACT_WORKFLOW,
                Verification.Verdict.PENDING,
                "asked the repository to run the agreed commands on the pull request head",
                List.of(),
                null);
    }

    // ------------------------------------------------------------ tier three

    private Verification verifierSession(RemediationTask task, SuccessCriteria criteria) {
        if (!props.getVerify().isVerifierSessionEnabled() || !devin.isConfigured()) {
            return Verification.unavailable("no independent verifier is configured for this repository");
        }
        if (criteria == null || criteria.verificationCommands().isEmpty()) {
            return Verification.unavailable("the contract carries no command that could prove the change");
        }
        if (task.getVerifierSessionId() == null) {
            DevinDtos.SessionDetails session = devin.createSession(
                    prompts.verifierPrompt(task.getRepo(), task.getPrUrl(), criteria),
                    "menD verify %s".formatted(task.key()),
                    List.of("mend", "mend-verify", task.getRepo()),
                    props.getVerify().getVerifierAcuLimit(),
                    Verification.JSON_SCHEMA,
                    task.getRepo());
            task.setVerifierSessionId(session.sessionId());
            task.setVerifierSessionUrl(session.url());
            log.info("started verifier session {} for {}", session.sessionId(), task.key());
            return new Verification(
                    Verification.Tier.VERIFIER_SESSION,
                    Verification.Verdict.PENDING,
                    "a separate session is running the agreed commands against the pull request head",
                    List.of(),
                    session.url());
        }

        Optional<DevinDtos.SessionDetails> session = devin.getSession(task.getVerifierSessionId());
        if (session.isEmpty()) {
            return Verification.unavailable("the verifier session could not be read back");
        }
        DevinDtos.SessionDetails details = session.get();
        if (details.hasStructuredOutput()) {
            Verification.VerifierReport report = read(details);
            if (report != null) {
                return new Verification(
                        Verification.Tier.VERIFIER_SESSION,
                        report.allPassed() && report.commands().stream().allMatch(Verification.CommandResult::passed)
                                ? Verification.Verdict.PASSED
                                : Verification.Verdict.FAILED,
                        report.summary(),
                        report.commands(),
                        task.getVerifierSessionUrl());
            }
        }
        if (details.isFinished() || details.isExpired() || details.isBlocked()) {
            return Verification.unavailable("the verifier session ended without reporting any command result");
        }
        return waitingFor(
                Verification.Tier.VERIFIER_SESSION, task.getPrOpenedAt(), "the verifier session is still running");
    }

    // --------------------------------------------------------------- helpers

    /** A tier that stays silent past the timeout stops blocking the pipeline and hands over. */
    private Verification waitingFor(Verification.Tier tier, Instant since, String why) {
        Duration timeout = props.getVerify().getTierTimeout();
        if (since != null && Instant.now().isAfter(since.plus(timeout))) {
            return Verification.unavailable(why + ", and its %d minute budget has run out".formatted(timeout.toMinutes()));
        }
        return new Verification(tier, Verification.Verdict.PENDING, why, List.of(), null);
    }

    private Verification fromChecks(Verification.Tier tier, List<GitHubDtos.CheckRun> runs) {
        boolean pending = runs.stream().anyMatch(r -> !"completed".equals(r.status()));
        GitHubDtos.CheckRun failed = runs.stream()
                .filter(r -> "completed".equals(r.status()))
                .filter(r -> !List.of("success", "neutral", "skipped").contains(String.valueOf(r.conclusion())))
                .findFirst()
                .orElse(null);
        String url = runs.get(0).htmlUrl();
        if (failed != null) {
            return new Verification(
                    tier,
                    Verification.Verdict.FAILED,
                    "%s concluded %s".formatted(failed.name(), failed.conclusion()),
                    List.of(),
                    failed.htmlUrl());
        }
        if (pending) {
            return new Verification(tier, Verification.Verdict.PENDING, "checks are still running", List.of(), url);
        }
        return new Verification(
                tier,
                Verification.Verdict.PASSED,
                runs.stream().map(GitHubDtos.CheckRun::name).reduce((a, b) -> a + ", " + b).orElse("checks")
                        + " passed on the pull request head",
                List.of(),
                url);
    }

    private static Verification.Verdict verdictOf(GitHubDtos.CiVerdict verdict) {
        return switch (verdict) {
            case PASSED -> Verification.Verdict.PASSED;
            case FAILED -> Verification.Verdict.FAILED;
            case PENDING -> Verification.Verdict.PENDING;
            case NONE -> Verification.Verdict.UNAVAILABLE;
        };
    }

    private Verification.VerifierReport read(DevinDtos.SessionDetails details) {
        try {
            return mapper.treeToValue(details.structuredOutput(), Verification.VerifierReport.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("verifier session {} returned unreadable output", details.sessionId(), e);
            return null;
        }
    }
}
