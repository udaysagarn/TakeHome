package ai.devin.mend.web;

import ai.devin.mend.config.MendProperties;
import ai.devin.mend.domain.IssueState;
import ai.devin.mend.domain.LearningScope;
import ai.devin.mend.domain.Repository;
import ai.devin.mend.learning.LearningService;
import ai.devin.mend.registry.RepositoryService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

/**
 * Serves the product overview, the monitoring board and the registration guide. htmx re-fetches the
 * board fragment on an interval.
 */
@Controller
public class DashboardController {

    private final DashboardService dashboard;
    private final RepositoryService registry;
    private final LearningService learnings;
    private final MendProperties props;

    public DashboardController(
            DashboardService dashboard,
            RepositoryService registry,
            LearningService learnings,
            MendProperties props) {
        this.dashboard = dashboard;
        this.registry = registry;
        this.learnings = learnings;
        this.props = props;
    }

    /** The landing page: what menD does, and the way in to each watched repository. */
    @GetMapping("/")
    public String overview(Model model) {
        model.addAttribute("repositories", dashboard.repoCards());
        model.addAttribute("kpis", dashboard.view(null).kpis());
        model.addAttribute("triggerLabel", props.getGithub().getTriggerLabel());
        return "overview";
    }

    @GetMapping("/pipeline")
    public String pipeline(@RequestParam(required = false) String repo, Model model) {
        model.addAttribute("view", dashboard.view(repo));
        model.addAttribute("selectedRepo", repo);
        model.addAttribute("repo", repo == null ? "all repositories" : repo);
        model.addAttribute("triggerLabel", triggerLabel(repo));
        return "dashboard";
    }

    /**
     * The pitch, as slides: what the problem is, how menD solves it, why an autonomous agent is the
     * only way to, and where it would go next. The numbers and the finished tasks are read live from
     * this instance, so the deck is never showing something the board disagrees with.
     */
    @GetMapping("/deck")
    public String deck(Model model) {
        model.addAttribute("kpis", dashboard.view(null).kpis());
        model.addAttribute("repositories", dashboard.repoCards());
        List<DashboardService.TaskRow> finished = dashboard.finished(50);
        model.addAttribute("recent", finished.stream().limit(5).toList());
        model.addAttribute(
                "showcase",
                finished.stream()
                        .filter(r -> r.state() == IssueState.SUCCEEDED)
                        .findFirst()
                        .orElse(null));
        model.addAttribute("triggerLabel", props.getGithub().getTriggerLabel());
        model.addAttribute("notCandidateLabel", props.getGithub().getNotCandidateLabel());
        return "deck";
    }

    /** Step-by-step instructions plus the form that registers and validates a repository. */
    @GetMapping("/repositories/new")
    public String registerForm(Model model) {
        model.addAttribute("app", props.getGithub().getApp().getSlug());
        model.addAttribute("triggerLabel", props.getGithub().getTriggerLabel());
        model.addAttribute("repositories", dashboard.repoCards());
        return "register";
    }

    /** Registers from the form, then shows the same page with the validation verdict. */
    @PostMapping("/repositories")
    public String register(@RequestParam String repo, Model model) {
        try {
            Repository registered = registry.register(repo);
            model.addAttribute("registered", registered);
        } catch (IllegalArgumentException e) {
            model.addAttribute("error", e.getMessage());
        }
        return registerForm(model);
    }

    /** What human reviewers have taught menD, and the promotions a human still has to make. */
    @GetMapping("/learnings")
    public String learnings(Model model) {
        model.addAttribute("repoLessons", learnings.byScope(LearningScope.REPO));
        model.addAttribute("generalLessons", learnings.byScope(LearningScope.GENERAL));
        model.addAttribute("actions", learnings.recommendedActions());
        model.addAttribute("retired", learnings.retired());
        return "learnings";
    }

    /** Everything menD persisted about one issue, including the contract Devin was held to. */
    @GetMapping("/tasks/{id}")
    public String task(@PathVariable long id, Model model) {
        DashboardService.TaskDetail detail = dashboard
                .detail(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no task " + id));
        model.addAttribute("detail", detail);
        model.addAttribute("repo", detail.task().repo());
        return "task";
    }

    /** htmx polling target: everything below the header. */
    @GetMapping("/fragments/live")
    public String live(@RequestParam(required = false) String repo, Model model) {
        model.addAttribute("view", dashboard.view(repo));
        return "fragments/live :: live";
    }

    private String triggerLabel(String repo) {
        return registry.find(repo == null ? "" : repo)
                .map(registry::triggerLabel)
                .orElse(props.getGithub().getTriggerLabel());
    }
}
