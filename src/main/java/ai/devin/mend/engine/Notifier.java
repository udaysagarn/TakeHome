package ai.devin.mend.engine;

import ai.devin.mend.config.MendProperties;
import ai.devin.mend.domain.Learning;
import ai.devin.mend.domain.RemediationOutcome;
import ai.devin.mend.domain.RemediationTask;
import ai.devin.mend.domain.SuccessCriteria;
import ai.devin.mend.domain.Verification;
import ai.devin.mend.github.GitHubClient;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Writes the pipeline's decisions back onto the issue. Everything the automation decides is visible to
 * the engineers who own the repository, not only on an internal dashboard.
 */
@Component
public class Notifier {

    private static final Logger log = LoggerFactory.getLogger(Notifier.class);
    private static final String FOOTER = "\n\n<sub>Posted by the menD remediation orchestrator.</sub>";

    private final GitHubClient github;
    private final MendProperties props;

    public Notifier(GitHubClient github, MendProperties props) {
        this.github = github;
        this.props = props;
    }

    public void ensureLabels(String repo) {
        MendProperties.Github cfg = props.getGithub();
        github.ensureLabel(repo, cfg.getTriggerLabel(), "1f6feb", "Queued for autonomous remediation by Devin");
        github.ensureLabel(repo, cfg.getInProgressLabel(), "fbca04", "A Devin session is working on this issue");
        github.ensureLabel(repo, cfg.getPrOpenLabel(), "0e8a16", "A Devin pull request is open for this issue");
        github.ensureLabel(repo, cfg.getDoneLabel(), "5319e7", "Remediated by Devin with green CI");
        github.ensureLabel(
                repo,
                cfg.getNotCandidateLabel(),
                "b60205",
                "Not automatable: success criteria could not be established");
        github.ensureLabel(
                repo, cfg.getNeedsHumanLabel(), "d93f0b", "Escalated from autonomous remediation to a human");
        github.ensureLabel(
                repo,
                cfg.getUnverifiedLabel(),
                "8b6914",
                "Fix proposed but nothing independent could prove it; needs a human verdict");
        github.ensureLabel(
                repo,
                cfg.getChangesRequestedLabel(),
                "e99695",
                "A human reviewer asked for changes; menD handed the feedback back to the session");
    }

    public void criteriaAccepted(RemediationTask task, SuccessCriteria criteria) {
        String body =
                """
                ### Accepted for autonomous remediation

                **Problem** — %s

                **Acceptance criteria**
                %s

                **Verification commands**
                %s

                **Tests** — %s

                **Files in scope:** %s
                **Risk:** %s · **Confidence:** %.2f%s

                These criteria are the contract for the remediation session: the pull request must show evidence
                for each one, and CI is the independent check.%s"""
                        .formatted(
                                criteria.problemRestatement(),
                                checklist(criteria.acceptanceCriteria()),
                                code(criteria.verificationCommands()),
                                criteria.testPlan() == null || criteria.testPlan().isBlank()
                                        ? "no test plan was stated"
                                        : criteria.testPlan(),
                                inline(criteria.filesInScope()),
                                criteria.risk(),
                                criteria.confidence(),
                                task.getCriteriaSessionUrl() == null
                                        ? ""
                                        : " · [scoping session](%s)".formatted(task.getCriteriaSessionUrl()),
                                FOOTER);
        comment(task, body);
        swapLabels(task, List.of(), List.of());
    }

    public void notACandidate(RemediationTask task, List<String> failures, String sessionUrl) {
        String body =
                """
                ### Not a candidate for autonomous remediation

                menD could not establish a machine-checkable definition of done for this issue, so no Devin
                session will be started. The gate failed on:

                %s

                To make this automatable, add what is missing (a reproduction, a concrete definition of done, or
                a narrower scope) and re-apply the `%s` label — or add a `devin-criteria` block to the issue body
                to state the contract explicitly.%s%s"""
                        .formatted(
                                bullets(failures),
                                props.getGithub().getTriggerLabel(),
                                sessionUrl == null ? "" : "\n\nScoping analysis: %s".formatted(sessionUrl),
                                FOOTER);
        comment(task, body);
        settleLabel(task, props.getGithub().getNotCandidateLabel());
    }

    public void dispatched(RemediationTask task) {
        comment(
                task,
                "### Remediation session started\n\nDevin session: %s\n\nAttempt %d. Progress is tracked on the menD dashboard.%s"
                        .formatted(task.getSessionUrl(), task.getAttempts(), FOOTER));
        swapLabels(
                task,
                List.of(props.getGithub().getInProgressLabel()),
                List.of(props.getGithub().getTriggerLabel()));
    }

    public void prOpened(RemediationTask task) {
        comment(task, "### Pull request opened\n\n%s\n\nWaiting for CI before this issue is considered remediated.%s"
                .formatted(task.getPrUrl(), FOOTER));
        swapLabels(
                task,
                List.of(props.getGithub().getPrOpenLabel()),
                List.of(props.getGithub().getInProgressLabel()));
    }

    public void succeeded(RemediationTask task, RemediationOutcome outcome) {
        String evidence = outcome == null || outcome.criteriaResults().isEmpty()
                ? "_No per-criterion evidence was returned._"
                : outcome.criteriaResults().stream()
                        .map(r -> "- %s **%s** — %s".formatted(r.satisfied() ? "✔" : "✘", r.criterion(), r.evidence()))
                        .collect(Collectors.joining("\n"));
        comment(
                task,
                """
                ### Remediated

                Pull request %s has green CI and satisfies the agreed criteria.

                %s

                **Tests** — %s

                Time from label to pull request: %s.%s"""
                        .formatted(task.getPrUrl(), evidence, testEvidence(outcome), humanDuration(task), FOOTER));
        settleLabel(task, props.getGithub().getDoneLabel());
    }

    /**
     * Posts the verification evidence on the pull request itself, where a reviewer is already looking,
     * naming what produced the verdict so nobody has to take menD's word for it.
     */
    public void verification(RemediationTask task, Verification verification) {
        Integer pull = GitHubClient.pullNumberFromUrl(task.getPrUrl());
        if (pull == null) {
            return;
        }
        String body =
                """
                ### Verification: %s

                Checked by %s.

                %s

                %s%s"""
                        .formatted(
                                verification.verdict(),
                                verification.provenance(),
                                verification.summary() == null ? "" : verification.summary(),
                                commandTable(verification),
                                FOOTER);
        try {
            github.comment(task.getRepo(), pull, body);
        } catch (RuntimeException e) {
            log.warn("failed to comment verification on {}: {}", task.getPrUrl(), e.getMessage());
        }
    }

    /** The honest outcome: a pull request exists, but menD will not call it a success. */
    public void unverified(RemediationTask task, Verification verification, RemediationOutcome outcome) {
        comment(
                task,
                """
                ### Fix proposed, not independently verified

                %s is open and the session asserts the acceptance criteria are met, but %s.

                menD deliberately does not count this as remediated. To close the gap, either add the
                repository's own CI to this pull request, or merge the menD verification contract workflow
                so the agreed commands run inside your CI:

                %s

                **Tests** — %s%s"""
                        .formatted(
                                task.getPrUrl(),
                                verification.summary() == null
                                        ? "nothing independent could prove it"
                                        : verification.summary(),
                                code(
                                        outcome == null || outcome.commandsRun().isEmpty()
                                                ? List.of("# no verification command was run")
                                                : outcome.commandsRun()),
                                testEvidence(outcome),
                                FOOTER));
        settleLabel(task, props.getGithub().getUnverifiedLabel());
    }

    private static String commandTable(Verification verification) {
        if (verification.commands().isEmpty()) {
            return verification.checkUrl() == null ? "" : "Evidence: " + verification.checkUrl();
        }
        return verification.commands().stream()
                .map(c -> "- %s `%s` → exit %d".formatted(c.passed() ? "✔" : "✘", c.command(), c.exitCode()))
                .collect(Collectors.joining("\n"));
    }

    /** Tells the issue thread that a human reviewer's verdict was handed back to the session. */
    public void changesRequested(RemediationTask task, int round, int maxRounds, String feedbackSummary) {
        comment(
                task,
                """
                ### Reviewer asked for changes (round %d of %d)

                menD handed the review back to the session that wrote %s. Reviewer comments outrank the
                acceptance criteria: where they conflict, the session is told to do what the reviewer asked.

                %s%s"""
                        .formatted(round, maxRounds, task.getPrUrl(), quote(feedbackSummary), FOOTER));
        swapLabels(
                task,
                List.of(props.getGithub().getChangesRequestedLabel()),
                List.of(props.getGithub().getPrOpenLabel(), props.getGithub().getDoneLabel()));
    }

    /** Publishes what menD learned, including the parts a human has to act on. */
    public void learned(RemediationTask task, List<Learning> lessons) {
        if (lessons.isEmpty()) {
            return;
        }
        String body =
                """
                ### What menD learned here

                %s%s"""
                        .formatted(
                                lessons.stream()
                                        .map(l -> "- **%s** (%s) — %s\n  _Recommended:_ %s%s"
                                                .formatted(
                                                        l.getTopic() == null ? "lesson" : l.getTopic(),
                                                        l.getScope(),
                                                        l.getLesson(),
                                                        l.getRecommendedAction().label(),
                                                        l.getActionDetail() == null
                                                                        || l.getActionDetail()
                                                                                .isBlank()
                                                                ? ""
                                                                : " — " + l.getActionDetail()))
                                        .collect(Collectors.joining("\n")),
                                FOOTER);
        comment(task, body);
    }

    private static String quote(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.lines().map(line -> "> " + line).collect(Collectors.joining("\n"));
    }

    public void escalated(RemediationTask task, String reason) {
        comment(
                task,
                "### Escalated to a human\n\n%s\n\nSession: %s%s"
                        .formatted(reason, String.valueOf(task.getSessionUrl()), FOOTER));
        settleLabel(task, props.getGithub().getNeedsHumanLabel());
    }

    public void failed(RemediationTask task, String reason) {
        comment(task, "### Remediation attempt failed\n\n%s\n\nSession: %s%s"
                .formatted(reason, String.valueOf(task.getSessionUrl()), FOOTER));
    }

    private void comment(RemediationTask task, String body) {
        try {
            github.comment(task.getRepo(), task.getIssueNumber(), body);
        } catch (RuntimeException e) {
            log.warn("failed to comment on {}: {}", task.key(), e.getMessage());
        }
    }

    /**
     * Applies a terminal label and strips every other menD label, so an issue that went through a
     * review round does not end up wearing both {@code changes-requested} and {@code done}.
     */
    private void settleLabel(RemediationTask task, String settled) {
        MendProperties.Github cfg = props.getGithub();
        List<String> stale = Stream.of(
                        cfg.getTriggerLabel(),
                        cfg.getInProgressLabel(),
                        cfg.getPrOpenLabel(),
                        cfg.getDoneLabel(),
                        cfg.getNotCandidateLabel(),
                        cfg.getNeedsHumanLabel(),
                        cfg.getUnverifiedLabel(),
                        cfg.getChangesRequestedLabel())
                .filter(label -> !label.equals(settled))
                .toList();
        swapLabels(task, List.of(settled), stale);
    }

    private void swapLabels(RemediationTask task, List<String> add, List<String> remove) {
        try {
            if (!add.isEmpty()) {
                github.addLabels(task.getRepo(), task.getIssueNumber(), add);
            }
            remove.forEach(label -> github.removeLabel(task.getRepo(), task.getIssueNumber(), label));
        } catch (RuntimeException e) {
            log.warn("failed to update labels on {}: {}", task.key(), e.getMessage());
        }
    }

    private static String humanDuration(RemediationTask task) {
        var duration = task.timeToPr();
        if (duration == null) {
            return "n/a";
        }
        long minutes = duration.toMinutes();
        return minutes < 60 ? minutes + "m" : "%dh %dm".formatted(minutes / 60, minutes % 60);
    }

    private static String checklist(List<String> items) {
        return items.stream().map(i -> "- [ ] " + i).collect(Collectors.joining("\n"));
    }

    private static String bullets(List<String> items) {
        return items.stream().map(i -> "- " + i).collect(Collectors.joining("\n"));
    }

    private static String code(List<String> items) {
        return "```bash\n" + String.join("\n", items) + "\n```";
    }

    /**
     * What the change did to the repository's tests. A remediation that touched no test is reported
     * as exactly that, so the absence is visible to a reviewer rather than merely unmentioned.
     */
    private static String testEvidence(RemediationOutcome outcome) {
        if (outcome == null) {
            return "_no outcome was returned._";
        }
        String note = outcome.testEvidence() == null || outcome.testEvidence().isBlank()
                ? "no explanation was given"
                : outcome.testEvidence().strip();
        return outcome.testsChanged().isEmpty()
                ? "no test was added or changed — %s".formatted(note)
                : "%s — %s".formatted(inline(outcome.testsChanged()), note);
    }

    private static String inline(List<String> items) {
        return items.isEmpty() ? "_unspecified_" : items.stream().map(i -> "`" + i + "`").collect(Collectors.joining(", "));
    }
}
