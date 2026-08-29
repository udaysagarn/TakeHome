package ai.devin.mend.web;

import ai.devin.mend.domain.IssueState;
import ai.devin.mend.domain.RemediationTask;
import ai.devin.mend.domain.Repository;
import ai.devin.mend.domain.SuccessCriteria;
import ai.devin.mend.domain.TaskEvent;
import ai.devin.mend.domain.TaskEventRepository;
import ai.devin.mend.domain.TaskRepository;
import ai.devin.mend.registry.RepositoryService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Optional;
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

    /** Conservative estimate of the engineer time one merged remediation would otherwise consume. */
    public static final double ENGINEER_HOURS_PER_FIX = 2.5;

    private final TaskRepository tasks;
    private final TaskEventRepository events;
    private final RepositoryService repositories;
    private final ObjectMapper json;

    public DashboardService(
            TaskRepository tasks,
            TaskEventRepository events,
            RepositoryService repositories,
            ObjectMapper json) {
        this.tasks = tasks;
        this.events = events;
        this.repositories = repositories;
        this.json = json;
    }

    public DashboardView view() {
        return view(null);
    }

    /** The board for one repository, or for every watched repository when {@code repo} is null. */
    public DashboardView view(String repo) {
        String selected = repo == null || repo.isBlank() ? null : repo.trim();
        List<RemediationTask> all = selected == null
                ? tasks.findAllByOrderByUpdatedAtDesc()
                : tasks.findByRepoOrderByUpdatedAtDesc(selected);
        return new DashboardView(
                kpis(all),
                board(all),
                rows(all),
                exclusions(all),
                recentEvents(selected),
                repoCards(),
                selected,
                Instant.now());
    }

    /**
     * One card per watched repository: its access and profile health next to the work menD has done
     * on it, which is what makes the multi-repository view worth switching between.
     */
    public List<RepoCard> repoCards() {
        List<RemediationTask> all = tasks.findAllByOrderByUpdatedAtDesc();
        return repositories.all().stream().map(r -> card(r, all)).toList();
    }

    private RepoCard card(Repository repository, List<RemediationTask> all) {
        List<RemediationTask> mine = all.stream()
                .filter(t -> repository.slug().equalsIgnoreCase(t.getRepo()))
                .toList();
        Kpis kpis = kpis(mine);
        return new RepoCard(
                repository.getId(),
                repository.slug(),
                repository.htmlUrl(),
                repository.getDefaultBranch(),
                repository.getAccessState().name(),
                repository.getAccessError(),
                repository.getIndexState().name(),
                repository.getIndexedAt(),
                repository.getIndexedSha(),
                repository.getCommitsSinceIndex(),
                repository.isEnabled(),
                repositories.triggerLabel(repository),
                kpis,
                mine.stream()
                        .map(RemediationTask::getUpdatedAt)
                        .max(Comparator.naturalOrder())
                        .orElse(null));
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
                acu, acuPerSuccess, exclusionRate, succeeded * ENGINEER_HOURS_PER_FIX);
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
        return recentEvents(null);
    }

    public List<EventRow> recentEvents(String repo) {
        return events.findAllByOrderByOccurredAtDesc(PageRequest.of(0, 40)).stream()
                .filter(e -> repo == null || e.getTaskKey() == null || e.getTaskKey().startsWith(repo + "#"))
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

    /**
     * Everything persisted about one task, including the criteria contract Devin was held to and the
     * lease its worker holds — the view an engineer opens when they want to audit a decision.
     */
    public Optional<TaskDetail> detail(long taskId) {
        return tasks.findById(taskId).map(t -> {
            Instant now = Instant.now();
            return new TaskDetail(
                    row(t),
                    criteria(t),
                    t.getCriteriaJson(),
                    t.getCriteriaHash(),
                    t.getCriteriaSessionUrl(),
                    t.getSessionUrl(),
                    t.getSessionId(),
                    t.getNudges(),
                    t.getLastError(),
                    t.getExclusionReason(),
                    t.getCreatedAt(),
                    t.getReadyAt(),
                    t.getDispatchedAt(),
                    t.getPrOpenedAt(),
                    t.getCompletedAt(),
                    lease(t, now),
                    timeline(taskId));
        });
    }

    private SuccessCriteria criteria(RemediationTask t) {
        if (t.getCriteriaJson() == null || t.getCriteriaJson().isBlank()) {
            return null;
        }
        try {
            return json.readValue(t.getCriteriaJson(), SuccessCriteria.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static Lease lease(RemediationTask t, Instant now) {
        boolean held = t.isLeased(now);
        String status;
        if (t.getState().isTerminal()) {
            status = "released";
        } else if (held) {
            status = "held";
        } else if (t.getOwnerId() != null) {
            status = "expired";
        } else {
            status = "unclaimed";
        }
        Long secondsLeft = t.getLeaseExpiresAt() == null || !held
                ? null
                : Duration.between(now, t.getLeaseExpiresAt()).toSeconds();
        return new Lease(
                status,
                t.getOwnerId(),
                t.getLeaseAcquiredAt(),
                t.getLeaseExpiresAt(),
                secondsLeft,
                t.getEtaAt(),
                t.isOverdue(now),
                t.getLeaseTakeovers());
    }

    private TaskRow row(RemediationTask t) {
        return new TaskRow(
                t.getId(),
                t.getRepo(),
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
                t.getUpdatedAt(),
                t.getOwnerId(),
                t.getEtaAt(),
                t.isOverdue(Instant.now()));
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
            List<RepoCard> repositories,
            String selectedRepo,
            Instant generatedAt) {}

    /** A watched repository plus the work menD has done on it. */
    public record RepoCard(
            Long id,
            String slug,
            String htmlUrl,
            String defaultBranch,
            String accessState,
            String accessError,
            String indexState,
            Instant indexedAt,
            String indexedSha,
            int commitsSinceIndex,
            boolean enabled,
            String triggerLabel,
            Kpis kpis,
            Instant lastActivityAt) {}

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
            Double exclusionRatePct,
            double engineerHoursAvoided) {}

    /** Who owns a task right now and what they promised. */
    public record Lease(
            String status,
            String ownerId,
            Instant acquiredAt,
            Instant expiresAt,
            Long secondsRemaining,
            Instant etaAt,
            boolean overdue,
            int takeovers) {}

    public record TaskDetail(
            TaskRow task,
            SuccessCriteria criteria,
            String criteriaJson,
            String criteriaHash,
            String criteriaSessionUrl,
            String remediationSessionUrl,
            String remediationSessionId,
            int nudges,
            String lastError,
            String exclusionReason,
            Instant createdAt,
            Instant readyAt,
            Instant dispatchedAt,
            Instant prOpenedAt,
            Instant completedAt,
            Lease lease,
            List<TaskEvent> timeline) {}

    public record BoardColumn(String name, int count, List<TaskRow> tasks) {}

    public record EventRow(String when, String taskKey, IssueState fromState, IssueState toState, String reason) {}

    public record TaskRow(
            Long id,
            String repo,
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
            Instant updatedAt,
            String ownerId,
            Instant etaAt,
            boolean overdue) {}

    /** Convenience for the markdown report. */
    public String csvLabels(List<TaskRow> rows) {
        return rows.stream().map(r -> "#" + r.issueNumber()).collect(Collectors.joining(", "));
    }
}
