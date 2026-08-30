package ai.devin.mend.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.devin.mend.devin.DevinApiClient;
import ai.devin.mend.devin.DevinDtos;
import ai.devin.mend.domain.ContextKind;
import ai.devin.mend.domain.IndexState;
import ai.devin.mend.domain.Repository;
import ai.devin.mend.domain.RepositoryContextRepository;
import ai.devin.mend.domain.RepositoryRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;

/**
 * The profile is the reason a second issue in a repository is cheaper than the first, so the
 * properties worth pinning are: it is generated once, a push ages only what it touched, only the
 * aged slices are regenerated, and a failure never silently discards a profile menD already has.
 */
@SpringBootTest
@TestPropertySource(
        properties = {
            "mend.engine.enabled=false",
            "mend.github.polling-enabled=false",
            "mend.github.repos=",
            "spring.datasource.url=jdbc:h2:mem:context;DB_CLOSE_DELAY=-1",
            "spring.jpa.hibernate.ddl-auto=create-drop"
        })
class ContextServiceTest {

    private static final String SLUG = "acme/superset";

    @Autowired
    private ContextService service;

    @Autowired
    private RepositoryRegistry repositories;

    @Autowired
    private RepositoryContextRepository contexts;

    @Autowired
    private ObjectMapper json;

    @MockBean
    private DevinApiClient devin;

    private Repository repository;

    @BeforeEach
    void setUp() {
        contexts.deleteAll();
        repositories.deleteAll();
        repository = new Repository("acme", "superset");
        repository.markValidated("master", null);
        repository = repositories.save(repository);
        when(devin.isConfigured()).thenReturn(true);
    }

    @Test
    void aRepositoryWithNoProfileNeedsEverySlice() {
        assertThat(service.needsIndexing(repository)).isTrue();
        assertThat(service.pendingKinds(repository)).containsExactlyInAnyOrder(ContextKind.values());
        assertThat(service.renderProfile(repository)).isEmpty();
    }

    @Test
    void indexingStoresTheSlicesAndTheCommitItSawThemAt() {
        index("a1b2c3d4e5f6a7b8");

        Repository stored = repositories.findById(repository.getId()).orElseThrow();
        assertThat(stored.getIndexState()).isEqualTo(IndexState.INDEXED);
        assertThat(stored.getIndexedSha()).isEqualTo("a1b2c3d4e5f6a7b8");
        assertThat(stored.getCommitsSinceIndex()).isZero();
        assertThat(service.needsIndexing(stored)).isFalse();

        String profile = service.renderProfile(stored);
        assertThat(profile).contains("acme/superset", "a1b2c3d", "Apache Superset, Python and TypeScript");
        assertThat(profile).contains("CLAUDE.md says: run pre-commit before pushing");
    }

    @Test
    void anOrdinarySourcePushLeavesTheProfileAlone() {
        index("a1b2c3d4e5f6a7b8");

        service.onPush(reload(), 3, List.of("superset/models/core.py"), "ffffff1");

        Repository stored = reload();
        assertThat(stored.getIndexState()).isEqualTo(IndexState.INDEXED);
        assertThat(stored.getCommitsSinceIndex()).isEqualTo(3);
        assertThat(service.needsIndexing(stored)).isFalse();
    }

    @Test
    void aPushToAWorkflowAgesOnlyTheCiSliceAndOnlyThatIsRegenerated() {
        index("a1b2c3d4e5f6a7b8");

        service.onPush(reload(), 1, List.of(".github/workflows/build.yml"), "ffffff1");

        Repository stored = reload();
        assertThat(stored.getIndexState()).isEqualTo(IndexState.STALE);
        assertThat(service.pendingKinds(stored)).containsExactly(ContextKind.CI);
        assertThat(service.renderProfile(stored)).contains("may be out of date");

        service.startIndexing(stored);
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(devin, org.mockito.Mockito.atLeastOnce())
                .createSession(prompt.capture(), anyString(), anyList(), anyInt(), any(), anyString());
        assertThat(prompt.getValue()).contains("- ci:");
        assertThat(prompt.getValue()).doesNotContain("- stack:");
    }

    @Test
    void aFailedFirstIndexParksTheRepositoryRatherThanRetryingForever() {
        when(devin.createSession(anyString(), anyString(), anyList(), anyInt(), any(), anyString()))
                .thenReturn(session("devin-ctx", "running", null, null));
        Repository indexing = service.startIndexing(reload());
        assertThat(indexing.getIndexState()).isEqualTo(IndexState.INDEXING);

        when(devin.getSession("devin-ctx")).thenReturn(Optional.of(session("devin-ctx", "exit", "finished", null)));
        Repository failed = service.collect(indexing);

        assertThat(failed.getIndexState()).isEqualTo(IndexState.INDEX_FAILED);
        assertThat(failed.getIndexError()).contains("without returning a profile");
        assertThat(service.needsIndexing(failed)).isFalse();
    }

    @Test
    void aFailedRefreshKeepsTheProfileItAlreadyHas() {
        index("a1b2c3d4e5f6a7b8");
        service.onPush(reload(), 1, List.of(".github/workflows/build.yml"), "ffffff1");

        when(devin.createSession(anyString(), anyString(), anyList(), anyInt(), any(), anyString()))
                .thenReturn(session("devin-ctx2", "running", null, null));
        Repository indexing = service.startIndexing(reload());
        when(devin.getSession("devin-ctx2")).thenReturn(Optional.of(session("devin-ctx2", "error", null, null)));

        Repository after = service.collect(indexing);

        assertThat(after.getIndexState()).isEqualTo(IndexState.STALE);
        assertThat(service.slices(after)).isNotEmpty();
        assertThat(service.renderProfile(after)).contains("Apache Superset, Python and TypeScript");
    }

    @Test
    void anUnreachableDevinApiIsNotTreatedAsAnEmptyProfile() {
        when(devin.isConfigured()).thenReturn(false);

        Repository after = service.startIndexing(reload());

        assertThat(after.getIndexState()).isEqualTo(IndexState.NEVER_INDEXED);
        verify(devin, never()).createSession(anyString(), anyString(), anyList(), anyInt(), any(), anyString());
    }

    @Test
    void collectIgnoresARepositoryThatIsNotIndexing() {
        Repository after = service.collect(reload());
        assertThat(after.getIndexState()).isEqualTo(IndexState.NEVER_INDEXED);
        verify(devin, never()).getSession(anyString());
    }

    private void index(String sha) {
        when(devin.createSession(anyString(), anyString(), anyList(), anyInt(), any(), anyString()))
                .thenReturn(session("devin-ctx", "running", null, null));
        Repository indexing = service.startIndexing(reload());
        when(devin.getSession("devin-ctx"))
                .thenReturn(Optional.of(session("devin-ctx", "exit", "finished", profileOutput(sha))));
        service.collect(indexing);
    }

    private Repository reload() {
        return repositories.findById(repository.getId()).orElseThrow();
    }

    private JsonNode profileOutput(String sha) {
        var node = json.createObjectNode();
        node.put("commit_sha", sha);
        node.put("stack", "Apache Superset, Python and TypeScript; npm in superset-frontend.");
        node.put("commands", "pytest tests/unit_tests; npm run test in superset-frontend.");
        node.put("layout", "superset/ is the Flask app, superset-frontend/ is the React app.");
        node.put("tests", "pytest and Jest; frontend specs live beside the component.");
        node.put("ci", "GitHub Actions: python.yml and frontend.yml gate every PR.");
        node.put("agent_rules", "CLAUDE.md says: run pre-commit before pushing.");
        node.put("pr_conventions", "Conventional commit titles, CODEOWNERS review required.");
        node.put("risk", "superset/migrations is risky and reviewed closely.");
        return node;
    }

    private static DevinDtos.SessionDetails session(String id, String status, String detail, JsonNode output) {
        return new DevinDtos.SessionDetails(
                id,
                "https://app.devin.ai/sessions/" + id,
                status,
                detail,
                "menD profile",
                List.of("mend", "context"),
                output,
                List.of(),
                0.4,
                null,
                null);
    }

    @Test
    void slicesAreEmptyForARepositoryThatWasNeverSaved() {
        assertThat(service.slices(new Repository("acme", "unsaved"))).isEmpty();
        assertThat(service.profileFor("acme/unknown")).isEmpty();
        assertThat(ContextService.invalidatedBy(null)).isEmpty();
        assertThat(ContextService.invalidatedBy(List.of("README.md"))).isEmpty();
        assertThat(ContextService.invalidatedBy(List.of("CLAUDE.md")))
                .isEqualTo(Set.of(ContextKind.AGENT_RULES));
    }
}
