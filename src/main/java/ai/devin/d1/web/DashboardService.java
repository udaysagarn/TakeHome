package ai.devin.d1.web;

import ai.devin.d1.domain.IssueState;
import ai.devin.d1.domain.RemediationTask;
import ai.devin.d1.domain.TaskEvent;
import ai.devin.d1.domain.TaskEventRepository;
import ai.devin.d1.domain.TaskRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read model behind the monitoring view, the JSON API and the leadership report. */
@Service
@Transactional(readOnly = true)
public class DashboardService {

    /** Columns of the pipeline board, in flow order. */
    private static final Map<String, List<IssueState>> BOARD = new LinkedHashMap<>();

    static {
        BOARD.put("Triage", List.of(IssueState.DISCOVERED, IssueState.CRITERIA_PENDING));
        BOARD.put("Ready", List.of(IssueState.READY));
        BOARD.put("Devin working", List.of(IssueState.DISPATCHED, IssueState.RUNNING, IssueState.BLOCKED));
        BOARD.put("Verifying", List.of(IssueState.PR_OPEN, IssueState.VERIFYING));
        BOARD.put("Done", List.of(IssueState.SUCCEEDED));
        BOARD.put("Excluded / escalated", List.of(
                IssueState.NOT_A_CANDIDATE, IssueState.NEEDS_HUMAN, IssueState.FAILED, IssueState.CANCELLED));
    }

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("MMM dd HH:mm:ss").withZone(ZoneOffset.UTC);

    private final TaskRepository tasks;
    private final TaskEventRepository events;

    public DashboardService(TaskRepository tasks, TaskEventRepository events) {
        this.tasks = tasks;
        this.events = events;
    }

    public DashboardView view() {
        List<RemediationTask> all = tasks.findAllByOrderByUpdatedAtDesc();
        return new DashboardView(kpis(all), board(all), rows(all), exclusions(all), recentEvents(), Instant.now());
    }

    public Kpis kpis(List<RemediationTask> all) {
        long inFlight = all.stream().filter(t -> t.getState().isActive()).count();
        long succeeded = count(all, IssueState.SUCCEEDED);
        long prsOpened = all.stream().filter(t -> t.getPrUrl() != null).count();
        long excluded = count(all, IssueState.NOT_A_CANDIDATE);
        long escalated = count(all, IssueState.NEEDS_HUMAN) + count(all, IssueState.FAILED);
        long attempted = succeeded + escalated;

        Double successRate = attempted == 0 ? null : (100.0 * succeeded) / attempted;
        Long medianToPr = median(all.stream()
                .map(RemediationTask::timeToPr)
                .filter(java.util.Objects::nonNull)
                .map(Duration::toMinutes)
                .sorted()
                .toList());
        int acu = all.stream().mapToInt(t -> t.getAcuBudget() == null ? 0 : t.getAcuBudget()).sum();
        Double acuPerSuccess = succeeded == 0 ? null : (double) acu / succeeded;
        long gated = succeeded + escalated + excluded;
        Double exclusionRate = gated == 0 ? null : (100.0 * excluded) / gated;

        return new Kpis(
                all.size(), inFlight, prsOpened, succeeded, excluded, escalated, successRate, medianToPr,
                acu, acuPerSuccess, exclusionRate);
    }

    public List<BoardColumn> board(List<RemediationTask> all) {
        List<BoardColumn> columns = new ArrayList<>();
        BOARD.forEach((name, states) -> {
            List<RemediationTask> members =
                    all.stream().filter(t -> states.contains(t.getState())).toList();
            columns.add(new BoardColumn(name, members.size(), members.stream().map(this::row).toList()));
        });
        return columns;
    }

    public List<TaskRow> rows(List<RemediationTask> all) {
        return all.stream().map(this::row).toList();
    }

    public List<TaskRow> exclusions(List<RemediationTask> all) {
        return all.stream()
                .filter(t -> t.getState() == IssueState.NOT_A_CANDIDATE || t.getState() == IssueState.NEEDS_HUMAN)
                .sorted(Comparator.comparing(RemediationTask::getUpdatedAt).reversed())
                .map(this::row)
                .toList();
    }

    public Map<IssueState, Long> stateCounts() {
        Map<IssueState, Long> counts = new EnumMap<>(IssueState.class);
        for (IssueState state : IssueState.values()) {
            counts.put(state, 0L);
        }
        for (Object[] row : tasks.countByState()) {
            counts.put((IssueState) row[0], (Long) row[1]);
        }
        return counts;
    }

    public List<EventRow> recentEvents() {
        return events.findAllByOrderByOccurredAtDesc(PageRequest.of(0, 40)).stream()
                .map(e -> new EventRow(
                        TIME.format(e.getOccurredAt()),
                        e.getTaskKey(),
                        e.getFromState(),
                        e.getToState(),
                        e.getReason()))
                .toList();
    }

    public List<TaskEvent> timeline(long taskId) {
        return events.findByTaskIdOrderByOccurredAtAsc(taskId);
    }

    private TaskRow row(RemediationTask t) {
        return new TaskRow(
                t.getId(),
                t.getIssueNumber(),
                t.getIssueTitle(),
                t.getIssueUrl(),
                t.getState(),
                t.getState().bucket(),
                t.getSessionUrl() != null ? t.getSessionUrl() : t.getCriteriaSessionUrl(),
                t.getPrUrl(),
                t.getCiStatus(),
                t.getConfidence(),
                t.getAttempts(),
                t.getAcuBudget(),
                t.getExclusionReason() != null ? t.getExclusionReason() : t.getLastError(),
                t.timeToPr() == null ? null : t.timeToPr().toMinutes(),
                t.elapsed().toMinutes(),
                t.getUpdatedAt());
    }

    private static long count(List<RemediationTask> all, IssueState state) {
        return all.stream().filter(t -> t.getState() == state).count();
    }

    private static Long median(List<Long> sorted) {
        if (sorted.isEmpty()) {
            return null;
        }
        int mid = sorted.size() / 2;
        return sorted.size() % 2 == 1 ? sorted.get(mid) : (sorted.get(mid - 1) + sorted.get(mid)) / 2;
    }

    public record DashboardView(
            Kpis kpis,
            List<BoardColumn> board,
            List<TaskRow> rows,
            List<TaskRow> exclusions,
            List<EventRow> events,
            Instant generatedAt) {}

    public record Kpis(
            int total,
            long inFlight,
            long prsOpened,
            long succeeded,
            long excluded,
            long escalated,
            Double successRatePct,
            Long medianMinutesToPr,
            int acuBudgeted,
            Double acuPerSuccess,
            Double exclusionRatePct) {}

    public record BoardColumn(String name, int count, List<TaskRow> tasks) {}

    public record EventRow(String when, String taskKey, IssueState fromState, IssueState toState, String reason) {}

    public record TaskRow(
            Long id,
            int issueNumber,
            String title,
            String issueUrl,
            IssueState state,
            String bucket,
            String sessionUrl,
            String prUrl,
            String ciStatus,
            Double confidence,
            int attempts,
            Integer acu,
            String note,
            Long minutesToPr,
            long ageMinutes,
            Instant updatedAt) {}

    /** Convenience for the markdown report. */
    public String csvLabels(List<TaskRow> rows) {
        return rows.stream().map(r -> "#" + r.issueNumber()).collect(Collectors.joining(", "));
    }
}
