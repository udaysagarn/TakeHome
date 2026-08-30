package ai.devin.mend.sandbox;

import ai.devin.mend.github.GitHubDtos;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * An in-memory stand-in for GitHub, used by the {@code sandbox} profile.
 *
 * <p>It holds the issues, labels, comments, pull requests, check runs and reviews that the real
 * control plane reads and writes, so the whole pipeline can be run on a laptop with no credentials.
 * Everything a contributor would otherwise have to fake by hand — a reviewer asking for changes, a
 * repository with no CI — is one HTTP call away through {@link SandboxController}.
 */
@Component
public class SandboxHub {

    private static final Logger log = LoggerFactory.getLogger(SandboxHub.class);

    private final AtomicInteger nextIssue = new AtomicInteger(101);
    private final AtomicInteger nextPull = new AtomicInteger(9001);

    private final Map<String, GitHubDtos.Issue> issues = new LinkedHashMap<>();
    private final Map<String, Set<String>> labels = new LinkedHashMap<>();
    private final Map<String, SandboxScenario> scenarios = new LinkedHashMap<>();
    private final Map<String, List<String>> comments = new LinkedHashMap<>();

    private final Map<Integer, GitHubDtos.PullRequest> pulls = new LinkedHashMap<>();
    private final Map<Integer, Integer> pullToIssue = new LinkedHashMap<>();
    private final Map<Integer, List<GitHubDtos.CheckRun>> checks = new LinkedHashMap<>();
    private final Map<Integer, List<GitHubDtos.Review>> reviews = new LinkedHashMap<>();

    // ------------------------------------------------------------ issue side

    /** Files a synthetic issue already carrying the trigger label, as a human would have done. */
    public synchronized GitHubDtos.Issue fileIssue(String repo, SandboxScenario scenario, String triggerLabel) {
        int number = nextIssue.getAndIncrement();
        String key = key(repo, number);
        labels.put(key, new LinkedHashSet<>(List.of(triggerLabel)));
        scenarios.put(key, scenario);
        GitHubDtos.Issue issue = new GitHubDtos.Issue(
                number,
                titleFor(scenario),
                bodyFor(scenario),
                "open",
                "https://github.com/%s/issues/%d".formatted(repo, number),
                labelList(key),
                null,
                Instant.now(),
                Instant.now());
        issues.put(key, issue);
        log.info("sandbox: filed {} as {}", key, scenario);
        return issue;
    }

    public synchronized Optional<GitHubDtos.Issue> issue(String repo, int number) {
        return Optional.ofNullable(withLabels(issues.get(key(repo, number))));
    }

    public synchronized List<GitHubDtos.Issue> issuesWithLabel(String repo, String label) {
        return issues.entrySet().stream()
                .filter(e -> e.getKey().startsWith(repo + "#"))
                .filter(e -> labels.getOrDefault(e.getKey(), Set.of()).contains(label))
                .map(e -> withLabels(e.getValue()))
                .toList();
    }

    public synchronized void addLabels(String repo, int number, List<String> added) {
        labels.computeIfAbsent(key(repo, number), k -> new LinkedHashSet<>()).addAll(added);
    }

    public synchronized void removeLabel(String repo, int number, String label) {
        labels.getOrDefault(key(repo, number), new LinkedHashSet<>()).remove(label);
    }

    public synchronized void comment(String repo, int number, String body) {
        comments.computeIfAbsent(key(repo, number), k -> new ArrayList<>()).add(body);
    }

    public synchronized SandboxScenario scenario(String repo, int number) {
        return scenarios.getOrDefault(key(repo, number), SandboxScenario.CLEAN_FIX);
    }

    // ------------------------------------------------------- pull request side

    /** Opens the pull request the fake remediation session claims to have written. */
    public synchronized GitHubDtos.PullRequest openPullRequest(String repo, int issueNumber) {
        Integer existing = pullToIssue.entrySet().stream()
                .filter(e -> e.getValue() == issueNumber)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
        if (existing != null) {
            return pulls.get(existing);
        }
        int number = nextPull.getAndIncrement();
        GitHubDtos.PullRequest pull = new GitHubDtos.PullRequest(
                number,
                "https://github.com/%s/pull/%d".formatted(repo, number),
                "open",
                false,
                new GitHubDtos.Head("0f1e2d3c4b5a", "mend/issue-" + issueNumber));
        pulls.put(number, pull);
        pullToIssue.put(number, issueNumber);
        SandboxScenario scenario = scenario(repo, issueNumber);
        if (scenario == SandboxScenario.REVIEW_THEN_FIX) {
            // The reviewer gets there before CI does, which is what makes this scenario worth watching.
            requestChanges(
                    number,
                    "staff-engineer",
                    "Please add a spec next to the component; that is where we keep them in this repository.");
        } else if (scenario != SandboxScenario.UNVERIFIED) {
            passChecks(repo, number);
        }
        log.info("sandbox: opened pull request #{} for issue #{}", number, issueNumber);
        return pull;
    }

    /** Called when a rework round finishes: the second attempt is the one CI gets to see. */
    public synchronized void completeRework(String repo, int pullNumber) {
        if (pulls.containsKey(pullNumber) && checks.getOrDefault(pullNumber, List.of()).isEmpty()) {
            passChecks(repo, pullNumber);
        }
    }

    private void passChecks(String repo, int pullNumber) {
        checks.put(
                pullNumber,
                List.of(new GitHubDtos.CheckRun(
                        "ci / build-and-test",
                        "completed",
                        "success",
                        "https://github.com/%s/pull/%d/checks".formatted(repo, pullNumber))));
    }

    public synchronized Optional<GitHubDtos.PullRequest> pull(int number) {
        return Optional.ofNullable(pulls.get(number));
    }

    public synchronized List<GitHubDtos.CheckRun> checkRuns(int pullNumber) {
        return checks.getOrDefault(pullNumber, List.of());
    }

    public synchronized List<GitHubDtos.Review> reviews(int pullNumber) {
        return reviews.getOrDefault(pullNumber, List.of());
    }

    /** Simulates a human reviewer rejecting the pull request menD opened. */
    public synchronized Optional<GitHubDtos.Review> requestChanges(int pullNumber, String reviewer, String body) {
        GitHubDtos.PullRequest pull = pulls.get(pullNumber);
        if (pull == null) {
            return Optional.empty();
        }
        GitHubDtos.Review review = new GitHubDtos.Review(
                System.currentTimeMillis(),
                new GitHubDtos.User(reviewer, "User"),
                "CHANGES_REQUESTED",
                body,
                pull.htmlUrl() + "#pullrequestreview",
                Instant.now());
        reviews.computeIfAbsent(pullNumber, k -> new ArrayList<>()).add(review);
        log.info("sandbox: @{} requested changes on pull request #{}", reviewer, pullNumber);
        return Optional.of(review);
    }

    // ---------------------------------------------------------------- reading

    public synchronized Map<String, Object> snapshot() {
        List<Map<String, Object>> issueViews = issues.keySet().stream()
                .map(key -> Map.<String, Object>of(
                        "issue", key,
                        "scenario", scenarios.get(key),
                        "labels", List.copyOf(labels.getOrDefault(key, Set.of())),
                        "comments", List.copyOf(comments.getOrDefault(key, List.of()))))
                .toList();
        List<Map<String, Object>> pullViews = pulls.values().stream()
                .map(p -> Map.<String, Object>of(
                        "pull", p.number(),
                        "url", p.htmlUrl(),
                        "issue", pullToIssue.get(p.number()),
                        "checks", checkRuns(p.number()).stream().map(GitHubDtos.CheckRun::name).toList(),
                        "reviews", reviews(p.number()).stream().map(GitHubDtos.Review::state).toList()))
                .toList();
        return Map.of("issues", issueViews, "pullRequests", pullViews);
    }

    // ---------------------------------------------------------------- helpers

    private static String key(String repo, int number) {
        return repo + "#" + number;
    }

    private GitHubDtos.Issue withLabels(GitHubDtos.Issue issue) {
        if (issue == null) {
            return null;
        }
        return new GitHubDtos.Issue(
                issue.number(),
                issue.title(),
                issue.body(),
                issue.state(),
                issue.htmlUrl(),
                labelList(key(repoOf(issue), issue.number())),
                issue.pullRequest(),
                issue.createdAt(),
                issue.updatedAt());
    }

    /** The html url carries the slug, which is the only place the issue record keeps it. */
    private static String repoOf(GitHubDtos.Issue issue) {
        String url = issue.htmlUrl();
        String path = url.substring("https://github.com/".length());
        return path.substring(0, path.indexOf("/issues/"));
    }

    private List<GitHubDtos.Label> labelList(String key) {
        return labels.getOrDefault(key, Set.of()).stream()
                .map(GitHubDtos.Label::new)
                .toList();
    }

    private static String titleFor(SandboxScenario scenario) {
        return switch (scenario) {
            case CLEAN_FIX -> "Pin transitive dependency flagged by npm audit in the frontend build";
            case NOT_A_CANDIDATE -> "Rethink how dashboards handle cross-filter state";
            case UNVERIFIED -> "Correct the retry backoff calculation in the alerting worker";
            case REVIEW_THEN_FIX -> "Replace explicit any in the chart controls type definitions";
        };
    }

    /**
     * Bodies are written the way a real reporter writes them, because the pre-filter and the
     * candidacy gate are real code in the sandbox: a thin body is supposed to be rejected.
     */
    private static String bodyFor(SandboxScenario scenario) {
        return switch (scenario) {
            case CLEAN_FIX -> """
                    `npm audit` in the frontend workspace reports a high-severity advisory in a transitive
                    dependency. The direct dependency already ships a patched range, so pinning the resolved
                    version in the lockfile clears the advisory without a functional change.

                    Definition of done: `npm audit --audit-level=high` exits 0 in the frontend workspace and the
                    existing unit suite still passes.
                    """;
            case NOT_A_CANDIDATE -> """
                    Cross-filter state is confusing when several charts filter one another. We should rethink the
                    interaction model and decide how filters compose, whether they are scoped per tab, and what the
                    default should be for existing dashboards. Needs a product decision and a design review before
                    anyone writes code.
                    """;
            case UNVERIFIED -> """
                    The alerting worker computes its retry backoff from the attempt count but multiplies before it
                    clamps, so the final attempt sleeps far longer than the configured maximum. Expected behaviour is
                    that no sleep exceeds the configured ceiling.

                    Definition of done: the backoff never exceeds the configured maximum, with a unit test covering
                    the final attempt.
                    """;
            case REVIEW_THEN_FIX -> """
                    The chart controls module declares several props as `any`, which disables type checking for the
                    components that consume them. The shapes are already described elsewhere in the codebase, so the
                    replacements are mechanical.

                    Definition of done: no `any` remains in the chart controls type definitions, `tsc --noEmit`
                    passes, and the existing component tests still pass.
                    """;
        };
    }
}
