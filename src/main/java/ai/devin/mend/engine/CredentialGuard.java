package ai.devin.mend.engine;

import ai.devin.mend.devin.DevinApiClient;
import ai.devin.mend.devin.DevinCredentialMonitor;
import ai.devin.mend.domain.AccessState;
import ai.devin.mend.domain.Repository;
import ai.devin.mend.github.GitHubClient;
import ai.devin.mend.registry.RepositoryService;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Pauses menD when its credentials cannot do the job, so a broken key stops the board instead of
 * quietly draining ACUs into sessions that cannot open a pull request.
 *
 * <p>The alarm on every page already says what is wrong, but an alarm nobody is watching does not
 * stop the engine: a Devin key refused mid-run keeps being dispatched against, and a GitHub App that
 * lost its installation leaves menD unable to read the issue it is remediating. Pausing states the
 * problem where the operator already looks for it — the paused strip carries the same reason — and
 * makes the resume deliberate.
 *
 * <p>Recovery is not automatic on purpose. menD only learns a credential works by using it, so
 * lifting the pause by itself would mean spending to find out; the operator who fixed the key is the
 * one who resumes.
 */
@Component
public class CredentialGuard {

    private final GitHubClient github;
    private final DevinApiClient devin;
    private final DevinCredentialMonitor devinCredential;
    private final RepositoryService repositories;
    private final EngineControl control;

    public CredentialGuard(
            GitHubClient github,
            DevinApiClient devin,
            DevinCredentialMonitor devinCredential,
            RepositoryService repositories,
            EngineControl control) {
        this.github = github;
        this.devin = devin;
        this.devinCredential = devinCredential;
        this.repositories = repositories;
        this.control = control;
    }

    /** Pauses menD if a credential is unusable, or clears the trigger once none is. */
    public void enforce() {
        Optional<Problem> problem = problem();
        problem.ifPresentOrElse(p -> control.pauseBecause(p.trigger(), p.reason()), control::problemCleared);
    }

    /** What is wrong with the credentials, or empty while menD can work. */
    Optional<Problem> problem() {
        if (!github.isConfigured()) {
            return Optional.of(new Problem(
                    "github-missing",
                    "menD paused itself: no GitHub App credentials, so it cannot read issues, label them"
                            + " or see the pull requests Devin opens. Set GITHUB_APP_ID,"
                            + " GITHUB_APP_INSTALLATION_ID and GITHUB_APP_PRIVATE_KEY, then resume."));
        }
        if (!devin.isConfigured()) {
            return Optional.of(new Problem(
                    "devin-missing",
                    "menD paused itself: no Devin credentials, so no issue that passes the gate can be"
                            + " dispatched. Set DEVIN_API_KEY and DEVIN_ORG_ID, then resume."));
        }
        Optional<String> refusal = devinCredential.refusal();
        if (refusal.isPresent()) {
            return Optional.of(new Problem(
                    "devin-refused:" + refusal.get(),
                    "menD paused itself: " + refusal.get()
                            + " Nothing further is dispatched until the key is fixed and menD is resumed."));
        }
        return lockedOut();
    }

    /**
     * Every registered repository refused. One repository losing access is that repository's
     * problem; all of them is the app id, the installation id or the private key.
     */
    private Optional<Problem> lockedOut() {
        List<Repository> all = repositories.all();
        if (all.isEmpty() || !repositories.operational().isEmpty()) {
            return Optional.empty();
        }
        List<Repository> refused = all.stream().filter(CredentialGuard::refused).toList();
        if (refused.size() != all.size()) {
            // Something is still being validated; wait for the verdict rather than guessing.
            return Optional.empty();
        }
        String detail = refused.stream()
                .map(r -> r.slug() + " — " + (r.getAccessError() == null ? r.getAccessState().getLabel() : r.getAccessError()))
                .reduce((a, b) -> a + "; " + b)
                .orElse("");
        return Optional.of(new Problem(
                "github-locked-out:" + detail,
                "menD paused itself: GitHub refused access to every registered repository (" + detail
                        + "). Check the app id, the installation id and the private key, then resume."));
    }

    private static boolean refused(Repository repository) {
        AccessState state = repository.getAccessState();
        return !state.isUsable() && state != AccessState.PENDING;
    }

    /** {@code trigger} identifies the problem so it pauses menD once; {@code reason} is what an operator reads. */
    record Problem(String trigger, String reason) {

        /** Both are stored in varchar(1024) columns, and a long access error must not fail the write. */
        Problem {
            trigger = clip(trigger);
            reason = clip(reason);
        }

        private static String clip(String value) {
            return value.length() <= 1024 ? value : value.substring(0, 1021) + "...";
        }
    }
}
