package ai.devin.mend.ingest;

import ai.devin.mend.config.MendProperties;
import ai.devin.mend.engine.Notifier;
import ai.devin.mend.engine.Orchestrator;
import ai.devin.mend.github.GitHubClient;
import ai.devin.mend.github.GitHubDtos;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
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
    private final MendProperties props;

    public IssuePoller(GitHubClient github, Orchestrator orchestrator, Notifier notifier, MendProperties props) {
        this.github = github;
        this.orchestrator = orchestrator;
        this.notifier = notifier;
        this.props = props;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void bootstrapLabels() {
        if (!github.isConfigured()) {
            log.warn("GitHub token not configured: ingestion and issue feedback are disabled");
            return;
        }
        notifier.ensureLabels();
    }

    @Scheduled(fixedDelayString = "${mend.github.poll-interval:PT30S}")
    public void poll() {
        if (!props.getGithub().isPollingEnabled() || !github.isConfigured()) {
            return;
        }
        try {
            List<GitHubDtos.Issue> issues = github.listIssuesWithLabel(props.getGithub().getTriggerLabel());
            for (GitHubDtos.Issue issue : issues) {
                orchestrator.onTriggerLabel(issue);
            }
        } catch (RuntimeException e) {
            log.warn("poll failed: {}", e.getMessage());
        }
    }
}
