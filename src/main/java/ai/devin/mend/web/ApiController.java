package ai.devin.mend.web;

import ai.devin.mend.domain.IssueState;
import ai.devin.mend.domain.Learning;
import ai.devin.mend.domain.RemediationTask;
import ai.devin.mend.domain.Repository;
import ai.devin.mend.domain.TaskEvent;
import ai.devin.mend.domain.TaskRepository;
import ai.devin.mend.engine.EngineControl;
import ai.devin.mend.engine.Orchestrator;
import ai.devin.mend.engine.TaskService;
import ai.devin.mend.github.GitHubClient;
import ai.devin.mend.learning.LearningService;
import ai.devin.mend.registry.RepositoryService;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** JSON surface for the dashboard, for scripts, and for anything that wants the raw state. */
@RestController
@RequestMapping("/api")
public class ApiController {

    private final DashboardService dashboard;
    private final ReportService report;
    private final TaskRepository tasks;
    private final TaskService taskService;
    private final Orchestrator orchestrator;
    private final GitHubClient github;
    private final RepositoryService registry;
    private final LearningService learnings;
    private final EngineControl engine;

    public ApiController(
            DashboardService dashboard,
            ReportService report,
            TaskRepository tasks,
            TaskService taskService,
            Orchestrator orchestrator,
            GitHubClient github,
            RepositoryService registry,
            LearningService learnings,
            EngineControl engine) {
        this.engine = engine;
        this.registry = registry;
        this.learnings = learnings;
        this.dashboard = dashboard;
        this.report = report;
        this.tasks = tasks;
        this.taskService = taskService;
        this.orchestrator = orchestrator;
        this.github = github;
    }

    /** Whether menD is allowed to start work that spends, and why not if it is not. */
    @GetMapping("/engine")
    public EngineControl.Status engine() {
        return engine.status();
    }

    /**
     * Pauses or resumes new work. Nothing authenticates this route, like every other {@code /api}
     * route, so {@code actor} is a claim recorded for the audit trail rather than an identity.
     */
    @PostMapping("/engine")
    public EngineControl.Status engine(
            @RequestParam boolean paused,
            @RequestParam(required = false) String reason,
            @RequestParam(defaultValue = "api") String actor) {
        return paused ? engine.pause(actor, reason) : engine.resume(actor);
    }

    @GetMapping("/summary")
    public DashboardService.Kpis summary() {
        return dashboard.kpis(tasks.findAllByOrderByUpdatedAtDesc());
    }

    @GetMapping("/states")
    public Map<IssueState, Long> states() {
        return dashboard.stateCounts();
    }

    @GetMapping("/tasks")
    public List<DashboardService.TaskRow> tasks() {
        return dashboard.rows(tasks.findAllByOrderByUpdatedAtDesc());
    }

    @GetMapping("/tasks/{id}")
    public ResponseEntity<DashboardService.TaskDetail> task(@PathVariable long id) {
        return ResponseEntity.of(dashboard.detail(id));
    }

    @GetMapping("/tasks/{id}/events")
    public List<TaskEvent> events(@PathVariable long id) {
        return dashboard.timeline(id);
    }

    @GetMapping(value = "/report", produces = "text/markdown;charset=UTF-8")
    public String report() {
        return report.markdown();
    }

    /** Manual trigger, for demos and for re-driving an issue without touching GitHub labels. */
    @PostMapping("/issues/{number}/ingest")
    public ResponseEntity<?> ingest(@PathVariable int number, @RequestParam(required = false) String repo) {
        String slug = repo != null
                ? repo
                : registry.primary().map(Repository::slug).orElse(github.defaultRepo());
        return github.getIssue(slug, number)
                .<ResponseEntity<?>>map(issue -> ResponseEntity.accepted()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("task", orchestrator.onTriggerLabel(slug, issue).key())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Everything reviewers have taught menD, active first. */
    @GetMapping("/learnings")
    public Map<String, List<Learning>> learnings() {
        return Map.of(
                "active", learnings.active(),
                "recommendedActions", learnings.recommendedActions(),
                "retired", learnings.retired());
    }

    @GetMapping("/repositories")
    public List<Repository> repositories() {
        return registry.all();
    }

    /** Registers a repository and returns the validation verdict, successful or not. */
    @PostMapping("/repositories")
    public ResponseEntity<?> registerRepository(@RequestBody Map<String, String> body) {
        String slug = body.get("repo");
        try {
            return ResponseEntity.ok(registry.register(slug));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** Re-runs access validation, for retrying after a permission is granted. */
    @PostMapping("/repositories/{id}/validate")
    public ResponseEntity<?> validateRepository(@PathVariable long id) {
        return ResponseEntity.of(
                registry.byId(id).map(repository -> (Object) registry.validate(repository)));
    }

    @PostMapping("/tasks/{id}/cancel")
    public ResponseEntity<?> cancel(@PathVariable long id) {
        RemediationTask task = tasks.findById(id).orElse(null);
        if (task == null) {
            return ResponseEntity.notFound().build();
        }
        taskService.transition(task, IssueState.CANCELLED, "cancelled from the dashboard", "operator");
        return ResponseEntity.ok(Map.of("state", task.getState()));
    }
}
