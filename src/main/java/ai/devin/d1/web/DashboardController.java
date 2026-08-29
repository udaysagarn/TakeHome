package ai.devin.d1.web;

import ai.devin.d1.config.D1Properties;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/** Serves the monitoring view. htmx re-fetches the fragments on an interval. */
@Controller
public class DashboardController {

    private final DashboardService dashboard;
    private final D1Properties props;

    public DashboardController(DashboardService dashboard, D1Properties props) {
        this.dashboard = dashboard;
        this.props = props;
    }

    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("view", dashboard.view());
        model.addAttribute("repo", props.getGithub().getRepo());
        model.addAttribute("triggerLabel", props.getGithub().getTriggerLabel());
        return "dashboard";
    }

    /** htmx polling target: everything below the header. */
    @GetMapping("/fragments/live")
    public String live(Model model) {
        model.addAttribute("view", dashboard.view());
        return "fragments/live :: live";
    }
}
