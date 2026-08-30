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
                .append("Repositories: ").append(repositoryList(view)).append("  \n")
                .append("Generated: ").append(Instant.now()).append("\n\n")
                .append("## Outcomes\n\n")
                .append("| Metric | Value |\n|---|---|\n")
                .append(row("Issues ingested", k.total()))
                .append(row("In flight", k.inFlight()))
                .append(row("Pull requests opened", k.prsOpened()))
                .append(row("Remediated (independently verified)", k.succeeded()))
                .append(row("Unverified (fix opened, no independent evidence)", k.unverified()))
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
                .append(row("Engineer-hours avoided (est.)", "%.1f".formatted(k.engineerHoursAvoided())))
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
                            cell(r.title()),
                            r.prUrl() == null ? "—" : r.prUrl(),
                            r.minutesToPr() == null ? "—" : r.minutesToPr() + " min",
                            r.attempts())));
            md.append("\n");
        }

        md.append("## Unverified\n\n")
                .append("menD opened a fix for these, but nothing independent of the session that wrote ")
                .append("the code could prove it works, so they are **not** counted as remediated.\n\n");
        List<DashboardService.TaskRow> unverified = view.rows().stream()
                .filter(r -> r.state() == IssueState.UNVERIFIED)
                .toList();
        if (unverified.isEmpty()) {
            md.append("_None._\n\n");
        } else {
            md.append("| Issue | Pull request | Why |\n|---|---|---|\n");
            unverified.forEach(r -> md.append("| [#%d](%s) %s | %s | %s |\n"
                    .formatted(
                            r.issueNumber(),
                            r.issueUrl(),
                            cell(r.title()),
                            r.prUrl() == null ? "—" : r.prUrl(),
                            cell(r.note()))));
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
                    .formatted(r.issueNumber(), r.issueUrl(), cell(r.title()), r.state(), cell(r.note()))));
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

    /** Collapses whitespace and escapes pipes so a long reason cannot break the markdown table. */
    private static String cell(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").replace("|", "\\|").trim();
    }

    private String repositoryList(DashboardService.DashboardView view) {
        if (view.repositories().isEmpty()) {
            return "`" + props.getGithub().getRepo() + "`";
        }
        return view.repositories().stream()
                .map(r -> "`" + r.slug() + "`")
                .collect(Collectors.joining(", "));
    }
}
