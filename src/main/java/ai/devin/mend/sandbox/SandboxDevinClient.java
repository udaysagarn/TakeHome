package ai.devin.mend.sandbox;

import ai.devin.mend.config.MendProperties;
import ai.devin.mend.devin.DevinApiClient;
import ai.devin.mend.devin.DevinDtos;
import ai.devin.mend.domain.ContextKind;
import ai.devin.mend.github.GitHubDtos;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * A scripted stand-in for Devin, used by the {@code sandbox} profile.
 *
 * <p>It answers with the same structured output the real sessions return, so every decision the
 * control plane makes — the candidacy gate, the criteria contract, the verification tier, the
 * retrospective — is the production code path. Only the engineering work itself is faked.
 *
 * <p>Sessions do not answer on the first poll: a contributor should see {@code DISPATCHED} become
 * {@code RUNNING} on the board rather than watching a task teleport to {@code SUCCEEDED}.
 */
@Component
@Profile("sandbox")
public class SandboxDevinClient extends DevinApiClient {

    private static final Logger log = LoggerFactory.getLogger(SandboxDevinClient.class);
    private static final Pattern TARGET = Pattern.compile("([\\w.-]+/[\\w.-]+)#(\\d+)");
    private static final int POLLS_BEFORE_ANSWERING = 2;

    private enum Kind {
        PROFILE,
        CRITERIA,
        REMEDIATION,
        VERIFY,
        RETROSPECTIVE
    }

    private record Session(
            String id, Kind kind, String repo, int issueNumber, AtomicInteger polls, AtomicInteger rounds) {}

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final AtomicInteger sequence = new AtomicInteger(1);
    private final SandboxHub hub;
    private final ObjectMapper mapper;

    public SandboxDevinClient(
            RestClient.Builder builder, ObjectMapper mapper, MendProperties props, SandboxHub hub) {
        super(builder, mapper, props);
        this.mapper = mapper;
        this.hub = hub;
    }

    @Override
    public boolean isConfigured() {
        return true;
    }

    @Override
    public DevinDtos.SessionDetails createSession(
            String prompt,
            String title,
            List<String> tags,
            Integer maxAcuLimit,
            String structuredOutputSchema,
            String repo) {
        Kind kind = kindOf(tags);
        Matcher matcher = TARGET.matcher(title == null ? "" : title);
        int issueNumber = matcher.find() ? Integer.parseInt(matcher.group(2)) : 0;
        String id = "sandbox-%s-%d".formatted(kind.name().toLowerCase(Locale.ROOT), sequence.getAndIncrement());
        sessions.put(id, new Session(id, kind, repo, issueNumber, new AtomicInteger(), new AtomicInteger()));
        log.info("sandbox: created {} session {} for {}#{}", kind, id, repo, issueNumber);
        return details(id, "running", "working", null);
    }

    @Override
    public Optional<DevinDtos.SessionDetails> getSession(String sessionId) {
        Session session = sessions.get(sessionId);
        if (session == null) {
            return Optional.empty();
        }
        if (session.polls().incrementAndGet() < POLLS_BEFORE_ANSWERING) {
            return Optional.of(details(sessionId, "running", "working", null));
        }
        return Optional.of(finish(session));
    }

    /** A nudge or reviewer feedback puts the session back to work; it answers again a poll later. */
    @Override
    public void sendMessage(String sessionId, String message) {
        Session session = sessions.get(sessionId);
        if (session != null) {
            session.polls().set(0);
            session.rounds().incrementAndGet();
            log.info("sandbox: session {} was asked to do another round", sessionId);
        }
    }

    // --------------------------------------------------------------- scripting

    private DevinDtos.SessionDetails finish(Session session) {
        SandboxScenario scenario = hub.scenario(session.repo(), session.issueNumber());
        return switch (session.kind()) {
            case PROFILE -> details(session.id(), "exit", "finished", json(profile()));
            case CRITERIA -> details(session.id(), "exit", "finished", json(criteria(scenario)));
            case REMEDIATION -> {
                GitHubDtos.PullRequest pull = hub.openPullRequest(session.repo(), session.issueNumber());
                if (session.rounds().get() > 0) {
                    hub.completeRework(session.repo(), pull.number());
                }
                yield details(session.id(), "running", "working", json(outcome(pull.htmlUrl(), scenario)));
            }
            // The unverifiable scenario is unverifiable all the way down: the verifier reports nothing.
            case VERIFY -> scenario == SandboxScenario.UNVERIFIED
                    ? details(session.id(), "exit", "finished", null)
                    : details(session.id(), "exit", "finished", json(verifierReport()));
            case RETROSPECTIVE -> details(session.id(), "exit", "finished", json(retrospective()));
        };
    }

    private static String profile() {
        StringBuilder json = new StringBuilder("{\"commit_sha\": \"0f1e2d3c4b5a\"");
        for (ContextKind kind : ContextKind.values()) {
            json.append(", \"%s\": \"%s (simulated profile slice for the sandbox)\""
                    .formatted(kind.name().toLowerCase(Locale.ROOT), kind.getLabel()));
        }
        return json.append("}").toString();
    }

    private static String criteria(SandboxScenario scenario) {
        if (scenario == SandboxScenario.NOT_A_CANDIDATE) {
            return """
                    {
                      "is_candidate": false,
                      "confidence": 0.2,
                      "problem_restatement": "Cross-filter interaction needs a product decision before code.",
                      "acceptance_criteria": [],
                      "verification_commands": [],
                      "files_in_scope": [],
                      "test_plan": "No test can be written until the intended behaviour is decided.",
                      "risk": "high",
                      "blocking_unknowns": ["Should filters be scoped per tab, and what happens to saved dashboards?"],
                      "rationale": "There is no objectively checkable definition of done here."
                    }
                    """;
        }
        return """
                {
                  "is_candidate": true,
                  "confidence": 0.86,
                  "problem_restatement": "A bounded change with an objectively checkable definition of done.",
                  "acceptance_criteria": [
                    "The reported defect no longer reproduces",
                    "No existing test is weakened, skipped or deleted"
                  ],
                  "verification_commands": ["npm test -- --run", "npm audit --audit-level=high"],
                  "files_in_scope": ["package-lock.json"],
                  "test_plan": "Extend the existing suite with a case that fails before the change and passes after.",
                  "risk": "low",
                  "blocking_unknowns": [],
                  "rationale": "The definition of done is stated in the issue and is machine-checkable."
                }
                """;
    }

    private static String outcome(String prUrl, SandboxScenario scenario) {
        return """
                {
                  "remediated": true,
                  "pr_url": "%s",
                  "summary": "Simulated remediation for the %s scenario.",
                  "files_changed": ["package-lock.json"],
                  "criteria_results": [
                    {"criterion": "The reported defect no longer reproduces", "satisfied": true,
                     "evidence": "npm test -- --run\\n  42 passing"},
                    {"criterion": "No existing test is weakened, skipped or deleted", "satisfied": true,
                     "evidence": "git diff --stat shows no deletions under test/"}
                  ],
                  "tests_changed": ["test/chartControls.test.ts"],
                  "test_evidence": "Added a case that fails on the previous revision.",
                  "commands_run": ["npm test -- --run", "npm audit --audit-level=high"],
                  "confidence": 0.9,
                  "blocked_reason": ""
                }
                """
                .formatted(prUrl, scenario);
    }

    private static String verifierReport() {
        return """
                {
                  "all_passed": true,
                  "summary": "Ran the contract's commands at the pull request head; both exited 0.",
                  "commands": [
                    {"command": "npm test -- --run", "exit_code": 0, "output": "42 passing"},
                    {"command": "npm audit --audit-level=high", "exit_code": 0, "output": "found 0 vulnerabilities"}
                  ]
                }
                """;
    }

    private static String retrospective() {
        return """
                {
                  "summary": "The fix was right but the reviewer wanted the repository's own conventions followed.",
                  "lessons": [
                    {
                      "scope": "REPO",
                      "topic": "tests",
                      "lesson": "Frontend changes here need a matching test under superset-frontend/spec, not only a unit test.",
                      "evidence": "Reviewer: 'please add a spec next to the component, that is where we keep them'.",
                      "recommended_action": "PROMPT_PREAMBLE",
                      "action_detail": "Inject into this repository's remediation prompts.",
                      "confidence": 0.82
                    },
                    {
                      "scope": "GENERAL",
                      "topic": "lockfiles",
                      "lesson": "When a lockfile changes, show the resolved version diff in the pull request body.",
                      "evidence": "Reviewer asked twice which transitive version was pinned.",
                      "recommended_action": "DEVIN_KNOWLEDGE",
                      "action_detail": "Worth promoting to an organisation-wide Devin knowledge note.",
                      "confidence": 0.74
                    }
                  ]
                }
                """;
    }

    // ---------------------------------------------------------------- helpers

    private static Kind kindOf(List<String> tags) {
        List<String> safe = tags == null ? List.of() : tags;
        if (safe.contains("context")) {
            return Kind.PROFILE;
        }
        if (safe.contains("criteria")) {
            return Kind.CRITERIA;
        }
        if (safe.contains("mend-verify")) {
            return Kind.VERIFY;
        }
        if (safe.contains("mend-retrospective")) {
            return Kind.RETROSPECTIVE;
        }
        return Kind.REMEDIATION;
    }

    private DevinDtos.SessionDetails details(String id, String status, String statusDetail, JsonNode output) {
        return new DevinDtos.SessionDetails(
                id,
                "https://app.devin.ai/sessions/" + id,
                status,
                statusDetail,
                id,
                List.of("mend", "sandbox"),
                output,
                List.of(),
                0.0,
                System.currentTimeMillis() / 1000,
                System.currentTimeMillis() / 1000);
    }

    private JsonNode json(String raw) {
        try {
            return mapper.readTree(raw);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("sandbox produced invalid JSON", e);
        }
    }
}
