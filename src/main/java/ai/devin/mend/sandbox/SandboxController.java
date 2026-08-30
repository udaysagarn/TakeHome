package ai.devin.mend.sandbox;

import ai.devin.mend.config.MendProperties;
import ai.devin.mend.github.GitHubDtos;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The contributor's control surface for the simulated workflow: file an issue, play the reviewer,
 * and read back everything menD wrote to the fake GitHub. Only exists under the {@code sandbox}
 * profile, so it cannot be reached by a deployment that talks to the real one.
 */
@RestController
@RequestMapping("/api/sandbox")
@Profile("sandbox")
public class SandboxController {

    private final SandboxHub hub;
    private final MendProperties props;

    public SandboxController(SandboxHub hub, MendProperties props) {
        this.hub = hub;
        this.props = props;
    }

    /** What can be simulated, so `curl /api/sandbox` is enough to learn the surface. */
    @GetMapping
    public Map<String, Object> overview() {
        List<Map<String, String>> scenarios = new ArrayList<>();
        for (SandboxScenario scenario : SandboxScenario.values()) {
            scenarios.add(Map.of("scenario", scenario.name(), "simulates", scenario.getDescription()));
        }
        return Map.of(
                "repository", props.getGithub().getRepo(),
                "scenarios", scenarios,
                "state", hub.snapshot());
    }

    @PostMapping("/issues")
    public GitHubDtos.Issue fileIssue(
            @RequestParam(defaultValue = "CLEAN_FIX") SandboxScenario scenario,
            @RequestParam(required = false) String repo) {
        return hub.fileIssue(
                repo == null || repo.isBlank() ? props.getGithub().getRepo() : repo,
                scenario,
                props.getGithub().getTriggerLabel());
    }

    /** Files one issue per scenario, which is the fastest way to fill the board for a demo. */
    @PostMapping("/issues/all")
    public List<GitHubDtos.Issue> fileEveryScenario(@RequestParam(required = false) String repo) {
        String slug = repo == null || repo.isBlank() ? props.getGithub().getRepo() : repo;
        List<GitHubDtos.Issue> filed = new ArrayList<>();
        for (SandboxScenario scenario : SandboxScenario.values()) {
            filed.add(hub.fileIssue(slug, scenario, props.getGithub().getTriggerLabel()));
        }
        return filed;
    }

    /** Plays the human reviewer: rejects a pull request menD opened and says why. */
    @PostMapping("/pulls/{pullNumber}/request-changes")
    public ResponseEntity<?> requestChanges(
            @PathVariable int pullNumber, @RequestBody(required = false) ReviewRequest request) {
        String reviewer = request == null || request.reviewer() == null ? "staff-engineer" : request.reviewer();
        String body = request == null || request.body() == null || request.body().isBlank()
                ? "Please add a spec next to the component; that is where we keep them in this repository."
                : request.body();
        return hub.requestChanges(pullNumber, reviewer, body)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(404)
                        .body(Map.of("error", "no simulated pull request #" + pullNumber)));
    }

    public record ReviewRequest(String reviewer, String body) {}
}
