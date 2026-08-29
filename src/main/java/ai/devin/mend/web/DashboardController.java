package ai.devin.mend.web;

import ai.devin.mend.config.MendProperties;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/** Serves the monitoring view. htmx re-fetches the fragments on an interval. */
@Controller
public class DashboardController {

    private final DashboardService dashboard;
    private final MendProperties props;

    public DashboardController(DashboardService dashboard, MendProperties props) {
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
