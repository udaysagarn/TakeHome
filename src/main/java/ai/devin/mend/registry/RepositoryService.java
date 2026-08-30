package ai.devin.mend.registry;

import ai.devin.mend.config.MendProperties;
import ai.devin.mend.domain.AccessState;
import ai.devin.mend.domain.IndexState;
import ai.devin.mend.domain.Repository;
import ai.devin.mend.domain.RepositoryRegistry;
import ai.devin.mend.github.GitHubClient;
import ai.devin.mend.github.GitHubCredentialsException;
import ai.devin.mend.github.GitHubDtos;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;

/**
 * Registers the repositories menD watches and proves, at registration time, that it can actually do
 * its job on each one. A repository that fails validation is kept with the reason rather than
 * dropped, so the dashboard can tell an operator what to fix.
 */
@Service
public class RepositoryService {

    private static final Logger log = LoggerFactory.getLogger(RepositoryService.class);

    /** GitHub owner/name rules: alphanumerics, hyphen, underscore, dot; no path traversal. */
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9_.-]{1,100}");

    /** Permissions menD cannot work without, mapped to what it needs them for. */
    private static final Map<String, String> REQUIRED_PERMISSIONS = Map.of(
            "issues", "read and label issues",
            "pull_requests", "read the pull requests Devin opens");

    private final RepositoryRegistry repositories;
    private final GitHubClient github;
    private final MendProperties props;

    public RepositoryService(RepositoryRegistry repositories, GitHubClient github, MendProperties props) {
        this.repositories = repositories;
        this.github = github;
        this.props = props;
    }

    public List<Repository> all() {
        return repositories.findAllByOrderByOwnerAscNameAsc();
    }

    public List<Repository> operational() {
        return repositories.findOperational();
    }

    public Optional<Repository> find(String slug) {
        String[] parts = split(slug);
        return parts == null ? Optional.empty() : repositories.findByOwnerAndName(parts[0], parts[1]);
    }

    public Optional<Repository> byId(long id) {
        return repositories.findById(id);
    }

    public Optional<Repository> find(String owner, String name) {
        return repositories.findByOwnerAndName(owner, name);
    }

    /** The repository a request means when it does not name one. */
    public Optional<Repository> primary() {
        List<Repository> operational = operational();
        return operational.isEmpty() ? all().stream().findFirst() : Optional.of(operational.getFirst());
    }

    /**
     * Registers {@code owner/name} and validates access immediately. Re-registering an existing
     * repository re-runs validation rather than failing, which is how an operator retries after
     * granting a missing permission.
     */
    @Transactional
    public Repository register(String slug) {
        String[] parts = split(slug);
        if (parts == null) {
            throw new IllegalArgumentException("expected owner/name, got: " + slug);
        }
        Repository repository = repositories
                .findByOwnerAndName(parts[0], parts[1])
                .orElseGet(() -> repositories.save(new Repository(parts[0], parts[1])));
        return validate(repository);
    }

    /**
     * Runs the access chain and stores the verdict on the repository. An error GitHub was not asked
     * about — a private key menD cannot read, a network failure — is a verdict too: it is stored the
     * same way, because a registry that lost the repository leaves an operator with nothing to fix.
     */
    @Transactional
    public Repository validate(Repository repository) {
        try {
            return interrogate(repository);
        } catch (RuntimeException e) {
            log.warn("could not validate {}: {}", repository.slug(), e.toString());
            repository.markAccessFailure(AccessState.NO_ACCESS, unreachable(repository, e));
            return repositories.save(repository);
        }
    }

    /**
     * The stored reason names the shape of the failure only. The full exception goes to the log,
     * because {@code accessError} is rendered on pages nothing authenticates and a transport or
     * parse error can carry request URLs, tokens and other configuration in its message.
     */
    private String unreachable(Repository repository, RuntimeException e) {
        return "menD could not ask GitHub about " + repository.slug() + ": " + detail(e)
                + ". Check the GitHub App id, installation id and private key, then re-validate.";
    }

    private static String detail(RuntimeException e) {
        if (e instanceof HttpStatusCodeException http) {
            return "GitHub answered " + http.getStatusCode().value();
        }
        if (e instanceof ResourceAccessException) {
            return "GitHub could not be reached";
        }
        return "the request failed (" + e.getClass().getSimpleName() + ")";
    }

    private Repository interrogate(Repository repository) {
        if (!github.isConfigured()) {
            repository.markAccessFailure(
                    AccessState.NO_ACCESS,
                    "No GitHub credentials configured. Set the menD GitHub App or a token and re-validate.");
            return repositories.save(repository);
        }
        try {
            return runAccessChain(repository);
        } catch (GitHubCredentialsException e) {
            repository.markAccessFailure(AccessState.NO_ACCESS, e.getMessage());
            return repositories.save(repository);
        }
    }

    private Repository runAccessChain(Repository repository) {
        Optional<GitHubDtos.Repo> remote = github.getRepo(repository.slug());
        if (remote.isEmpty()) {
            repository.markAccessFailure(
                    AccessState.NO_ACCESS,
                    "menD cannot see " + repository.slug()
                            + ". Check the name, and install the menD GitHub App on this repository.");
            return repositories.save(repository);
        }

        GitHubDtos.Repo repo = remote.get();
        if (repo.archived() || repo.disabled()) {
            repository.markAccessFailure(
                    AccessState.NO_ACCESS, repository.slug() + " is archived or disabled, so it accepts no changes.");
            return repositories.save(repository);
        }
        if (!repo.hasIssues()) {
            repository.markAccessFailure(
                    AccessState.MISSING_PERMISSION,
                    "Issues are turned off on " + repository.slug() + ", and menD is triggered by issues.");
            return repositories.save(repository);
        }

        List<GitHubDtos.Repo> installed = github.installationRepos();
        boolean inInstallation =
                installed.stream().anyMatch(r -> repository.slug().equalsIgnoreCase(r.fullName()));
        if (!installed.isEmpty() && !inInstallation) {
            repository.markAccessFailure(
                    AccessState.NO_ACCESS,
                    "The menD GitHub App is installed, but " + repository.slug()
                            + " is not one of its selected repositories. Add it under the installation's"
                            + " repository access.");
            return repositories.save(repository);
        }

        String missing = missingPermissions();
        if (missing != null) {
            repository.markAccessFailure(
                    AccessState.MISSING_PERMISSION,
                    "The menD GitHub App installation is missing: " + missing
                            + ". Grant it under the app's permissions, then accept the request on the installation.");
            return repositories.save(repository);
        }

        repository.markValidated(repo.defaultBranch(), credentialsInstallationId());
        if (repository.getTriggerLabel() == null) {
            repository.setTriggerLabel(props.getGithub().getTriggerLabel());
        }
        log.info("repository {} validated (default branch {})", repository.slug(), repo.defaultBranch());
        return repositories.save(repository);
    }

    /**
     * Counts commits against the profile, and ages it only when the push actually touched something
     * the profile describes — ordinary source commits leave a good profile alone.
     */
    @Transactional
    public void notePush(Repository repository, int commits, boolean agesProfile) {
        repository.setCommitsSinceIndex(repository.getCommitsSinceIndex() + Math.max(commits, 1));
        if (agesProfile && repository.getIndexState() == IndexState.INDEXED) {
            repository.setIndexState(IndexState.STALE);
        }
        repository.setUpdatedAt(Instant.now());
        repositories.save(repository);
    }

    @Transactional
    public Repository save(Repository repository) {
        repository.setUpdatedAt(Instant.now());
        return repositories.save(repository);
    }

    /** The label that puts an issue in this repository's queue. */
    public String triggerLabel(Repository repository) {
        return repository != null && repository.getTriggerLabel() != null
                ? repository.getTriggerLabel()
                : props.getGithub().getTriggerLabel();
    }

    private String credentialsInstallationId() {
        String id = props.getGithub().getApp().getInstallationId();
        return id == null || id.isBlank() ? null : id;
    }

    /** Null when every required permission is present, otherwise a human-readable list. */
    private String missingPermissions() {
        Map<String, String> granted = github.installationPermissions();
        if (granted.isEmpty()) {
            return null; // running on a token, whose scopes GitHub does not report
        }
        String missing = REQUIRED_PERMISSIONS.entrySet().stream()
                .filter(required -> !"write".equals(granted.get(required.getKey())))
                .map(required -> required.getKey().replace('_', ' ') + " (write, to " + required.getValue() + ")")
                .reduce((a, b) -> a + ", " + b)
                .orElse(null);
        return missing;
    }

    private static String[] split(String slug) {
        if (slug == null) {
            return null;
        }
        String trimmed = slug.trim().toLowerCase(Locale.ROOT).replaceFirst("^https://github\\.com/", "");
        trimmed = trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
        String[] parts = trimmed.split("/");
        if (parts.length != 2 || !SEGMENT.matcher(parts[0]).matches() || !SEGMENT.matcher(parts[1]).matches()) {
            return null;
        }
        return parts;
    }
}
