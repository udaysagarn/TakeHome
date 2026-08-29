package ai.devin.d1.engine;

import ai.devin.d1.config.D1Properties;
import ai.devin.d1.domain.RemediationOutcome;
import ai.devin.d1.domain.RemediationTask;
import ai.devin.d1.domain.SuccessCriteria;
import ai.devin.d1.github.GitHubClient;
import java.util.List;
import java.util.stream.Collectors;
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
    private static final String FOOTER = "\n\n<sub>Posted by the D1 remediation orchestrator.</sub>";

    private final GitHubClient github;
    private final D1Properties props;

    public Notifier(GitHubClient github, D1Properties props) {
        this.github = github;
        this.props = props;
    }

    public void ensureLabels() {
        D1Properties.Github cfg = props.getGithub();
        github.ensureLabel(cfg.getTriggerLabel(), "1f6feb", "Queued for autonomous remediation by Devin");
        github.ensureLabel(cfg.getInProgressLabel(), "fbca04", "A Devin session is working on this issue");
        github.ensureLabel(cfg.getPrOpenLabel(), "0e8a16", "A Devin pull request is open for this issue");
        github.ensureLabel(cfg.getDoneLabel(), "5319e7", "Remediated by Devin with green CI");
        github.ensureLabel(cfg.getNotCandidateLabel(), "b60205", "Not automatable: success criteria could not be established");
        github.ensureLabel(cfg.getNeedsHumanLabel(), "d93f0b", "Escalated from autonomous remediation to a human");
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

                **Files in scope:** %s
                **Risk:** %s · **Confidence:** %.2f%s

                These criteria are the contract for the remediation session: the pull request must show evidence
                for each one, and CI is the independent check.%s"""
                        .formatted(
                                criteria.problemRestatement(),
                                checklist(criteria.acceptanceCriteria()),
                                code(criteria.verificationCommands()),
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

                D1 could not establish a machine-checkable definition of done for this issue, so no Devin
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
        swapLabels(
                task,
                List.of(props.getGithub().getNotCandidateLabel()),
                List.of(props.getGithub().getTriggerLabel(), props.getGithub().getInProgressLabel()));
    }

    public void dispatched(RemediationTask task) {
        comment(
                task,
                "### Remediation session started\n\nDevin session: %s\n\nAttempt %d. Progress is tracked on the D1 dashboard.%s"
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

                Time from label to pull request: %s.%s"""
                        .formatted(task.getPrUrl(), evidence, humanDuration(task), FOOTER));
        swapLabels(
                task,
                List.of(props.getGithub().getDoneLabel()),
                List.of(props.getGithub().getPrOpenLabel(), props.getGithub().getInProgressLabel()));
    }

    public void escalated(RemediationTask task, String reason) {
        comment(
                task,
                "### Escalated to a human\n\n%s\n\nSession: %s%s"
                        .formatted(reason, String.valueOf(task.getSessionUrl()), FOOTER));
        swapLabels(
                task,
                List.of(props.getGithub().getNeedsHumanLabel()),
                List.of(props.getGithub().getInProgressLabel(), props.getGithub().getTriggerLabel()));
    }

    public void failed(RemediationTask task, String reason) {
        comment(task, "### Remediation attempt failed\n\n%s\n\nSession: %s%s"
                .formatted(reason, String.valueOf(task.getSessionUrl()), FOOTER));
    }

    private void comment(RemediationTask task, String body) {
        try {
            github.comment(task.getIssueNumber(), body);
        } catch (RuntimeException e) {
            log.warn("failed to comment on {}: {}", task.key(), e.getMessage());
        }
    }

    private void swapLabels(RemediationTask task, List<String> add, List<String> remove) {
        try {
            if (!add.isEmpty()) {
                github.addLabels(task.getIssueNumber(), add);
            }
            remove.forEach(label -> github.removeLabel(task.getIssueNumber(), label));
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

    private static String inline(List<String> items) {
        return items.isEmpty() ? "_unspecified_" : items.stream().map(i -> "`" + i + "`").collect(Collectors.joining(", "));
    }
}
