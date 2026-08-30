package ai.devin.mend.sandbox;

import ai.devin.mend.github.GitHubDtos;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;

/**
 * Renders the simulated issues and pull requests the sandbox invents.
 *
 * <p>The board links to whatever url the GitHub client reports, so in the sandbox those links have
 * to lead somewhere: a github.com url for an issue number that does not exist is a 404 in the
 * middle of a demo, and worse, it looks like the real thing.
 */
@Controller
@Profile("sandbox")
public class SandboxPagesController {

    private final SandboxHub hub;

    public SandboxPagesController(SandboxHub hub) {
        this.hub = hub;
    }

    @GetMapping("/sandbox/{owner}/{name}/issues/{number}")
    public String issue(
            @PathVariable String owner, @PathVariable String name, @PathVariable int number, Model model) {
        String repo = owner + "/" + name;
        GitHubDtos.Issue issue = hub.issue(repo, number).orElseThrow(() -> missing("issue #" + number));
        model.addAttribute("repo", repo);
        model.addAttribute("issue", issue);
        model.addAttribute("scenario", hub.scenario(repo, number));
        model.addAttribute("comments", hub.comments(repo, number));
        return "sandbox-issue";
    }

    @GetMapping("/sandbox/{owner}/{name}/pull/{number}")
    public String pull(
            @PathVariable String owner, @PathVariable String name, @PathVariable int number, Model model) {
        String repo = owner + "/" + name;
        GitHubDtos.PullRequest pull = hub.pull(number)
                .filter(p -> hub.repoOf(number).map(repo::equals).orElse(false))
                .orElseThrow(() -> missing("pull request #" + number));
        Optional<Integer> issueNumber = hub.issueBehind(number);
        model.addAttribute("repo", repo);
        model.addAttribute("pull", pull);
        model.addAttribute("checks", hub.checkRuns(number));
        model.addAttribute("reviews", hub.reviews(number));
        model.addAttribute("issueNumber", issueNumber.orElse(null));
        model.addAttribute(
                "issueUrl", issueNumber.map(n -> SandboxHub.issueUrl(repo, n)).orElse(null));
        model.addAttribute(
                "comments", issueNumber.map(n -> hub.comments(repo, n)).orElse(List.of()));
        return "sandbox-pull";
    }

    private static ResponseStatusException missing(String what) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "no simulated " + what);
    }
}
