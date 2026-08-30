package ai.devin.mend.registry;

import ai.devin.mend.config.MendProperties;
import ai.devin.mend.domain.RemediationTask;
import ai.devin.mend.domain.TaskRepository;
import java.util.LinkedHashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Seeds the registry on startup: the repositories named in configuration, plus any repository that
 * already owns tasks from before menD was multi-repository. Validation failures are stored on the
 * repository rather than thrown, so a demo container still starts without GitHub credentials.
 */
@Component
public class RepositoryBootstrap {

    private static final Logger log = LoggerFactory.getLogger(RepositoryBootstrap.class);

    private final RepositoryService registry;
    private final TaskRepository tasks;
    private final MendProperties props;

    public RepositoryBootstrap(RepositoryService registry, TaskRepository tasks, MendProperties props) {
        this.registry = registry;
        this.tasks = tasks;
        this.props = props;
    }

    /** Runs before label bootstrapping, which iterates the repositories seeded here. */
    @Order(0)
    @EventListener(ApplicationReadyEvent.class)
    public void seed() {
        Set<String> slugs = new LinkedHashSet<>(props.getGithub().getRepos());
        tasks.findAll().stream().map(RemediationTask::getRepo).forEach(slugs::add);
        for (String slug : slugs) {
            if (slug == null || slug.isBlank() || registry.find(slug).isPresent()) {
                continue;
            }
            try {
                registry.register(slug);
            } catch (RuntimeException e) {
                log.warn("could not register {}: {}", slug, e.getMessage());
            }
        }
    }
}
