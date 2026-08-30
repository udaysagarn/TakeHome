package ai.devin.mend.registry;

import ai.devin.mend.config.MendProperties;
import ai.devin.mend.domain.IndexState;
import ai.devin.mend.domain.Repository;
import ai.devin.mend.engine.LeaseManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps repository profiles current in the background: collects finished profiling sessions and
 * starts new ones for repositories whose profile is missing or has aged. Deliberately one session
 * at a time — profiling is never urgent, and it must not compete with remediation for capacity.
 *
 * <p>Every repository is claimed through {@link LeaseManager} before its session is created, so a
 * second replica running the same loop cannot profile the same repository twice.
 */
@Component
public class ContextReconciler {

    private static final Logger log = LoggerFactory.getLogger(ContextReconciler.class);

    private final RepositoryService registry;
    private final ContextService context;
    private final LeaseManager leases;
    private final MendProperties props;

    public ContextReconciler(
            RepositoryService registry, ContextService context, LeaseManager leases, MendProperties props) {
        this.registry = registry;
        this.context = context;
        this.leases = leases;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${mend.engine.context-interval:PT60S}")
    public void tick() {
        if (!props.getEngine().isEnabled()) {
            return;
        }
        for (Repository repository : registry.operational()) {
            if (repository.getIndexState() == IndexState.INDEXING) {
                leases.claimRepository(repository).ifPresent(this::collect);
            }
        }
        registry.operational().stream()
                .filter(repository -> repository.getIndexState() != IndexState.INDEXING)
                .filter(context::needsIndexing)
                .findFirst()
                .flatMap(leases::claimRepository)
                .ifPresent(this::startIndexing);
    }

    /** Reads the in-flight session; the lease is held until the profile settles, or dropped if lost. */
    private void collect(Repository claimed) {
        Repository after = context.collect(claimed);
        if (after.getIndexState() == IndexState.INDEXING) {
            leases.renewRepository(after);
        } else {
            leases.releaseRepository(after);
        }
    }

    private void startIndexing(Repository claimed) {
        if (!context.needsIndexing(claimed)) {
            leases.releaseRepository(claimed); // another worker profiled it between the read and the claim
            return;
        }
        log.info("starting profile refresh for {}", claimed.slug());
        Repository after = context.startIndexing(claimed);
        if (after.getIndexState() != IndexState.INDEXING) {
            leases.releaseRepository(after);
        }
    }
}
