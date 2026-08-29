package ai.devin.mend.web;

import ai.devin.mend.config.MendProperties;
import ai.devin.mend.domain.IssueState;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * The answer to "if I were an engineering leader, how would I know this is working?" — a markdown
 * digest that can be pasted into a review or posted to Slack.
 */
@Service
public class ReportService {

    /** Conservative estimate of the engineer time one merged remediation would otherwise consume. */
    private static final double ENGINEER_HOURS_PER_FIX = 2.5;

    private final DashboardService dashboard;
    private final MendProperties props;

    public ReportService(DashboardService dashboard, MendProperties props) {
        this.dashboard = dashboard;
        this.props = props;
    }

    public String markdown() {
        DashboardService.DashboardView view = dashboard.view();
        DashboardService.Kpis k = view.kpis();

        StringBuilder md = new StringBuilder();
        md.append("# Autonomous remediation report\n\n")
                .append("Repository: `").append(props.getGithub().getRepo()).append("`  \n")
                .append("Generated: ").append(Instant.now()).append("\n\n")
                .append("## Outcomes\n\n")
                .append("| Metric | Value |\n|---|---|\n")
                .append(row("Issues ingested", k.total()))
                .append(row("In flight", k.inFlight()))
                .append(row("Pull requests opened", k.prsOpened()))
                .append(row("Remediated (green CI)", k.succeeded()))
                .append(row("Excluded by the criteria gate", k.excluded()))
                .append(row("Escalated to a human", k.escalated()))
                .append(row("Success rate", pct(k.successRatePct())))
                .append(row("Median time to pull request", k.medianMinutesToPr() == null
                        ? "n/a"
                        : k.medianMinutesToPr() + " min"))
                .append(row("ACU budgeted", k.acuBudgeted()))
                .append(row("ACU per remediation", k.acuPerSuccess() == null
                        ? "n/a"
                        : "%.1f".formatted(k.acuPerSuccess())))
                .append(row("Engineer-hours avoided (est.)", "%.1f".formatted(k.succeeded() * ENGINEER_HOURS_PER_FIX)))
                .append("\n");

        md.append("## Remediated\n\n");
        List<DashboardService.TaskRow> done = view.rows().stream()
                .filter(r -> r.state() == IssueState.SUCCEEDED)
                .toList();
        if (done.isEmpty()) {
            md.append("_None yet._\n\n");
        } else {
            md.append("| Issue | Pull request | Time to PR | Attempts |\n|---|---|---|---|\n");
            done.forEach(r -> md.append("| [#%d](%s) %s | %s | %s | %d |\n"
                    .formatted(
                            r.issueNumber(),
                            r.issueUrl(),
                            r.title(),
                            r.prUrl() == null ? "—" : r.prUrl(),
                            r.minutesToPr() == null ? "—" : r.minutesToPr() + " min",
                            r.attempts())));
            md.append("\n");
        }

        md.append("## Excluded by the criteria gate\n\n")
                .append("These issues were never sent to a remediation session because no machine-checkable ")
                .append("definition of done could be established.\n\n");
        if (view.exclusions().isEmpty()) {
            md.append("_None._\n\n");
        } else {
            md.append("| Issue | State | Reason |\n|---|---|---|\n");
            view.exclusions().forEach(r -> md.append("| [#%d](%s) %s | %s | %s |\n"
                    .formatted(r.issueNumber(), r.issueUrl(), r.title(), r.state(), oneLine(r.note()))));
            md.append("\n");
        }

        md.append("## In flight\n\n");
        String inFlight = view.rows().stream()
                .filter(r -> r.state().isActive())
                .map(r -> "- #%d %s — **%s** (%d min, %s)"
                        .formatted(
                                r.issueNumber(),
                                r.title(),
                                r.state(),
                                r.ageMinutes(),
                                r.sessionUrl() == null ? "no session" : r.sessionUrl()))
                .collect(Collectors.joining("\n"));
        md.append(inFlight.isEmpty() ? "_Nothing active._" : inFlight).append("\n");
        return md.toString();
    }

    private static String row(String label, Object value) {
        return "| %s | %s |\n".formatted(label, value);
    }

    private static String pct(Double value) {
        return value == null ? "n/a" : "%.0f%%".formatted(value);
    }

    private static String oneLine(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }
}
