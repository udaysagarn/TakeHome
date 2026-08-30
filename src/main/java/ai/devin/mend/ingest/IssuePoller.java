package ai.devin.mend.ingest;

import ai.devin.mend.config.MendProperties;
import ai.devin.mend.domain.Repository;
import ai.devin.mend.engine.Notifier;
import ai.devin.mend.engine.Orchestrator;
import ai.devin.mend.github.GitHubClient;
import ai.devin.mend.github.GitHubDtos;
import ai.devin.mend.registry.RepositoryService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fallback trigger. The webhook is the low-latency path, but a repository cannot always be given one
 * (org policy, no public ingress), and webhooks are at-most-once in practice. Polling the trigger
 * label makes the pipeline eventually consistent with GitHub regardless.
 */
@Component
public class IssuePoller {

    private static final Logger log = LoggerFactory.getLogger(IssuePoller.class);

    private final GitHubClient github;
    private final Orchestrator orchestrator;
    private final Notifier notifier;
    private final RepositoryService registry;
    private final MendProperties props;

    public IssuePoller(
            GitHubClient github,
            Orchestrator orchestrator,
            Notifier notifier,
            RepositoryService registry,
            MendProperties props) {
        this.github = github;
        this.orchestrator = orchestrator;
        this.notifier = notifier;
        this.registry = registry;
        this.props = props;
    }

    @Order(10)
    @EventListener(ApplicationReadyEvent.class)
    public void bootstrapLabels() {
        if (!github.isConfigured()) {
            log.warn("GitHub token not configured: ingestion and issue feedback are disabled");
            return;
        }
        for (Repository repository : registry.operational()) {
            notifier.ensureLabels(repository.slug());
        }
    }

    /**
     * Each registered repository is polled independently so one unreachable repository cannot stop
     * ingestion for the rest.
     */
    @Scheduled(fixedDelayString = "${mend.github.poll-interval:PT30S}")
    public void poll() {
        if (!props.getGithub().isPollingEnabled() || !github.isConfigured()) {
            return;
        }
        for (Repository repository : registry.operational()) {
            try {
                List<GitHubDtos.Issue> issues =
                        github.listIssuesWithLabel(repository.slug(), registry.triggerLabel(repository));
                for (GitHubDtos.Issue issue : issues) {
                    orchestrator.onTriggerLabel(repository.slug(), issue);
                }
            } catch (RuntimeException e) {
                log.warn("poll of {} failed: {}", repository.slug(), e.getMessage());
            }
        }
    }
}
