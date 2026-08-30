package ai.devin.mend.registry;

import ai.devin.mend.config.MendProperties;
import ai.devin.mend.domain.IndexState;
import ai.devin.mend.domain.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps repository profiles current in the background: collects finished profiling sessions and
 * starts new ones for repositories whose profile is missing or has aged. Deliberately one session
 * at a time — profiling is never urgent, and it must not compete with remediation for capacity.
 */
@Component
public class ContextReconciler {

    private static final Logger log = LoggerFactory.getLogger(ContextReconciler.class);

    private final RepositoryService registry;
    private final ContextService context;
    private final MendProperties props;

    public ContextReconciler(RepositoryService registry, ContextService context, MendProperties props) {
        this.registry = registry;
        this.context = context;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${mend.engine.context-interval:PT60S}")
    public void tick() {
        if (!props.getEngine().isEnabled()) {
            return;
        }
        for (Repository repository : registry.operational()) {
            if (repository.getIndexState() == IndexState.INDEXING) {
                context.collect(repository);
            }
        }
        registry.operational().stream()
                .filter(repository -> repository.getIndexState() != IndexState.INDEXING)
                .filter(context::needsIndexing)
                .findFirst()
                .ifPresent(repository -> {
                    log.info("starting profile refresh for {}", repository.slug());
                    context.startIndexing(repository);
                });
    }
}
