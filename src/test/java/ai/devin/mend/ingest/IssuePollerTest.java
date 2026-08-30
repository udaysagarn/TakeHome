package ai.devin.mend.ingest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.devin.mend.config.MendProperties;
import ai.devin.mend.domain.Repository;
import ai.devin.mend.engine.Notifier;
import ai.devin.mend.engine.Orchestrator;
import ai.devin.mend.github.GitHubClient;
import ai.devin.mend.github.GitHubDtos;
import ai.devin.mend.registry.RepositoryService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The poller is the ingestion path a repository behind NAT actually gets, so its refusals matter as
 * much as its work: nothing is called without credentials, and one unreachable repository must not
 * stop the others.
 */
class IssuePollerTest {

    private GitHubClient github;
    private Orchestrator orchestrator;
    private Notifier notifier;
    private RepositoryService registry;
    private MendProperties props;
    private IssuePoller poller;

    private final Repository one = new Repository("acme", "one");
    private final Repository two = new Repository("acme", "two");

    @BeforeEach
    void setUp() {
        github = mock(GitHubClient.class);
        orchestrator = mock(Orchestrator.class);
        notifier = mock(Notifier.class);
        registry = mock(RepositoryService.class);
        props = new MendProperties();
        poller = new IssuePoller(github, orchestrator, notifier, registry, props);

        when(registry.operational()).thenReturn(List.of(one, two));
        when(registry.triggerLabel(any())).thenReturn("menD:fix");
    }

    @Test
    void withoutCredentialsNothingIsPolledAndNoLabelIsCreated() {
        when(github.isConfigured()).thenReturn(false);

        poller.bootstrapLabels();
        poller.poll();

        verifyNoInteractions(notifier);
        verifyNoInteractions(orchestrator);
    }

    @Test
    void pollingCanBeTurnedOffWithoutTurningOffLabelBootstrap() {
        when(github.isConfigured()).thenReturn(true);
        props.getGithub().setPollingEnabled(false);

        poller.bootstrapLabels();
        poller.poll();

        verify(notifier).ensureLabels("acme/one");
        verify(notifier).ensureLabels("acme/two");
        verify(github, never()).listIssuesWithLabel(anyString(), anyString());
    }

    @Test
    void everyLabelledIssueIsHandedToTheOrchestrator() {
        when(github.isConfigured()).thenReturn(true);
        GitHubDtos.Issue issue = issue(7);
        when(github.listIssuesWithLabel("acme/one", "menD:fix")).thenReturn(List.of(issue));
        when(github.listIssuesWithLabel("acme/two", "menD:fix")).thenReturn(List.of());

        poller.poll();

        verify(orchestrator).onTriggerLabel("acme/one", issue);
        verify(orchestrator, never()).onTriggerLabel(eq("acme/two"), any());
    }

    @Test
    void anUnreachableRepositoryDoesNotStopIngestionForTheRest() {
        when(github.isConfigured()).thenReturn(true);
        when(github.listIssuesWithLabel("acme/one", "menD:fix"))
                .thenThrow(new IllegalStateException("connection reset"));
        GitHubDtos.Issue issue = issue(9);
        when(github.listIssuesWithLabel("acme/two", "menD:fix")).thenReturn(List.of(issue));

        poller.poll();

        verify(orchestrator).onTriggerLabel("acme/two", issue);
    }

    private GitHubDtos.Issue issue(int number) {
        return new GitHubDtos.Issue(
                number,
                "title",
                "body",
                "open",
                "https://github.com/acme/one/issues/" + number,
                List.of(),
                null,
                null,
                null);
    }
}
