package ai.devin.mend.registry;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.devin.mend.config.MendProperties;
import ai.devin.mend.domain.Repository;
import ai.devin.mend.domain.TaskRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * {@code MEND_REPO} is the variable the runbook and the compose file tell an operator to set, so a
 * repository named there has to end up in the registry even when the list form is left alone.
 */
class RepositoryBootstrapTest {

    private final RepositoryService registry = mock(RepositoryService.class);
    private final TaskRepository tasks = mock(TaskRepository.class);
    private final MendProperties props = new MendProperties();

    @Test
    void theSingleConfiguredRepositoryIsRegisteredAlongsideTheList() {
        props.getGithub().setRepo("acme/superset");
        props.getGithub().setRepos(List.of("acme/websocket"));
        when(tasks.findAll()).thenReturn(List.of());
        when(registry.find(anyString())).thenReturn(Optional.empty());
        when(registry.register(anyString())).thenReturn(new Repository("acme", "superset"));

        new RepositoryBootstrap(registry, tasks, props).seed();

        verify(registry).register("acme/websocket");
        verify(registry).register("acme/superset");
    }
}
