package ai.devin.mend.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import ai.devin.mend.domain.AccessState;
import ai.devin.mend.domain.IndexState;
import ai.devin.mend.domain.Repository;
import ai.devin.mend.domain.RepositoryRegistry;
import ai.devin.mend.github.GitHubClient;
import ai.devin.mend.github.GitHubCredentialsException;
import ai.devin.mend.github.GitHubDtos;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;

/**
 * Registration is the point where menD promises it can work on a repository, so the interesting
 * assertions are the refusals: no access, wrong installation, missing permission.
 */
@SpringBootTest
@TestPropertySource(
        properties = {
            "mend.engine.enabled=false",
            "mend.github.polling-enabled=false",
            "mend.github.repos=",
            "spring.datasource.url=jdbc:h2:mem:registry;DB_CLOSE_DELAY=-1"
        })
class RepositoryServiceTest {

    private static final String SLUG = "acme/superset";

    @Autowired
    private RepositoryService service;

    @Autowired
    private RepositoryRegistry repositories;

    @MockBean
    private GitHubClient github;

    @BeforeEach
    void setUp() {
        repositories.deleteAll();
        when(github.isConfigured()).thenReturn(true);
        when(github.installationRepos()).thenReturn(List.of(repo(SLUG, "master", false, true)));
        when(github.installationPermissions()).thenReturn(Map.of("issues", "write", "pull_requests", "write"));
        when(github.getRepo(SLUG)).thenReturn(Optional.of(repo(SLUG, "master", false, true)));
    }

    @Test
    void registrationValidatesAccessAndRecordsTheDefaultBranch() {
        Repository repository = service.register("https://github.com/Acme/Superset");

        assertThat(repository.slug()).isEqualTo(SLUG);
        assertThat(repository.getAccessState()).isEqualTo(AccessState.VALIDATED);
        assertThat(repository.getDefaultBranch()).isEqualTo("master");
        assertThat(repository.getIndexState()).isEqualTo(IndexState.NEVER_INDEXED);
        assertThat(repository.isOperational()).isTrue();
        assertThat(service.operational()).extracting(Repository::slug).containsExactly(SLUG);
    }

    @Test
    void reRegisteringRevalidatesRatherThanDuplicating() {
        service.register(SLUG);
        when(github.getRepo(SLUG)).thenReturn(Optional.empty());

        Repository again = service.register(SLUG);

        assertThat(repositories.findAll()).hasSize(1);
        assertThat(again.getAccessState()).isEqualTo(AccessState.NO_ACCESS);
        assertThat(again.getAccessError()).contains("cannot see");
        assertThat(service.operational()).isEmpty();
    }

    @Test
    void aRepositoryOutsideTheInstallationIsRefusedWithTheReason() {
        when(github.installationRepos()).thenReturn(List.of(repo("acme/other", "main", false, true)));

        Repository repository = service.register(SLUG);

        assertThat(repository.getAccessState()).isEqualTo(AccessState.NO_ACCESS);
        assertThat(repository.getAccessError()).contains("not one of its selected repositories");
    }

    @Test
    void aMissingWritePermissionIsReportedAsSuch() {
        when(github.installationPermissions()).thenReturn(Map.of("issues", "read", "pull_requests", "write"));

        Repository repository = service.register(SLUG);

        assertThat(repository.getAccessState()).isEqualTo(AccessState.MISSING_PERMISSION);
        assertThat(repository.getAccessError()).contains("issues (write");
    }

    @Test
    void archivedRepositoriesAndDisabledIssuesAreRefused() {
        when(github.getRepo(SLUG)).thenReturn(Optional.of(repo(SLUG, "master", true, true)));
        assertThat(service.register(SLUG).getAccessError()).contains("archived");

        when(github.getRepo(SLUG)).thenReturn(Optional.of(repo(SLUG, "master", false, false)));
        Repository repository = service.register(SLUG);
        assertThat(repository.getAccessState()).isEqualTo(AccessState.MISSING_PERMISSION);
        assertThat(repository.getAccessError()).contains("Issues are turned off");
    }

    @Test
    void anUnusablePrivateKeyIsRecordedAsTheVerdictRatherThanThrownAtTheOperator() {
        when(github.getRepo(SLUG))
                .thenThrow(new GitHubCredentialsException("GITHUB_APP_PRIVATE_KEY does not hold a private key."));

        Repository repository = service.register(SLUG);

        assertThat(repository.getAccessState()).isEqualTo(AccessState.NO_ACCESS);
        assertThat(repository.getAccessError()).contains("GITHUB_APP_PRIVATE_KEY");
    }

    @Test
    void aSlugThatIsNotOwnerSlashNameIsRejectedBeforeAnyGitHubCall() {
        assertThatThrownBy(() -> service.register("../../etc/passwd")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.register("superset")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aPushAgesAnIndexedProfileWithoutLosingTheOldOne() {
        Repository repository = service.register(SLUG);
        repository.setIndexState(IndexState.INDEXED);
        Repository indexed = service.save(repository);

        service.notePush(indexed, 3, true);

        Repository reloaded = service.find(SLUG).orElseThrow();
        assertThat(reloaded.getIndexState()).isEqualTo(IndexState.STALE);
        assertThat(reloaded.getCommitsSinceIndex()).isEqualTo(3);
    }

    @Test
    void aPushThatTouchesNothingTheProfileDescribesLeavesItIndexed() {
        Repository repository = service.register(SLUG);
        repository.setIndexState(IndexState.INDEXED);
        Repository indexed = service.save(repository);

        service.notePush(indexed, 2, false);

        Repository reloaded = service.find(SLUG).orElseThrow();
        assertThat(reloaded.getIndexState()).isEqualTo(IndexState.INDEXED);
        assertThat(reloaded.getCommitsSinceIndex()).isEqualTo(2);
    }

    private static GitHubDtos.Repo repo(String slug, String defaultBranch, boolean archived, boolean hasIssues) {
        return new GitHubDtos.Repo(
                1L,
                slug.substring(slug.indexOf('/') + 1),
                slug,
                "https://github.com/" + slug,
                defaultBranch,
                archived,
                false,
                "public",
                hasIssues,
                "TypeScript");
    }
}
