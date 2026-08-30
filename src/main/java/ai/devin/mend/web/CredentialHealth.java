package ai.devin.mend.web;

import ai.devin.mend.devin.DevinApiClient;
import ai.devin.mend.devin.DevinCredentialMonitor;
import ai.devin.mend.domain.AccessState;
import ai.devin.mend.domain.Repository;
import ai.devin.mend.github.GitHubClient;
import ai.devin.mend.registry.RepositoryService;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Whether menD's credentials actually work, phrased for the operator who has to fix them. Nothing
 * else on the dashboard says this out loud: a rejected GitHub App leaves every page looking calm
 * while no issue can be ingested, so the verdicts stored at validation time are collected here and
 * shown as an alarm on every page.
 */
@Service
public class CredentialHealth {

    /**
     * One thing to fix. {@code repo} is the slug to re-validate when the problem belongs to a
     * repository, and null when the credential itself is missing and no re-check would help.
     */
    public record Problem(String subject, String detail, String repo) {}

    private final RepositoryService repositories;
    private final GitHubClient github;
    private final DevinApiClient devin;
    private final DevinCredentialMonitor devinCredential;

    public CredentialHealth(
            RepositoryService repositories,
            GitHubClient github,
            DevinApiClient devin,
            DevinCredentialMonitor devinCredential) {
        this.repositories = repositories;
        this.github = github;
        this.devin = devin;
        this.devinCredential = devinCredential;
    }

    /** Empty when menD can do its job; otherwise every failure an operator can act on. */
    public List<Problem> problems() {
        List<Problem> problems = new ArrayList<>();
        boolean githubConfigured = github.isConfigured();
        if (!githubConfigured) {
            problems.add(new Problem(
                    "GitHub credentials are not configured",
                    "menD has no GitHub App id, installation id and private key, so it cannot read"
                            + " issues, label them or see the pull requests Devin opens.",
                    null));
        }
        if (!devin.isConfigured()) {
            problems.add(new Problem(
                    "Devin credentials are not configured",
                    "DEVIN_API_KEY or DEVIN_ORG_ID is missing, so menD cannot dispatch a remediation"
                            + " session even for an issue that passes the gate.",
                    null));
        } else {
            // A present key can still be revoked, mistyped or from another organisation. menD only
            // learns that from Devin refusing a call it was making anyway, so this appears after the
            // first refused dispatch rather than at startup.
            devinCredential
                    .refusal()
                    .ifPresent(reason -> problems.add(new Problem("Devin refused menD's credential", reason, null)));
        }
        if (githubConfigured) {
            // With no credentials at all every repository reports the same thing; naming them then
            // buries the one problem worth fixing.
            repositories.all().stream().filter(CredentialHealth::rejected).forEach(r -> problems.add(new Problem(
                    r.slug() + " · " + r.getAccessState().getLabel(),
                    r.getAccessError() == null ? r.getAccessState().getLabel() : r.getAccessError(),
                    r.slug())));
        }
        return problems;
    }

    /** PENDING is a repository menD has not got to yet, not a repository it was refused. */
    private static boolean rejected(Repository repository) {
        return !repository.getAccessState().isUsable() && repository.getAccessState() != AccessState.PENDING;
    }
}
