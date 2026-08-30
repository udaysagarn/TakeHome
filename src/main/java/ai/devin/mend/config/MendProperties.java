package ai.devin.mend.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * All tunables of the control plane. Bound from the {@code mend.*} prefix.
 */
@ConfigurationProperties(prefix = "mend")
public class MendProperties {

    private Devin devin = new Devin();
    private Github github = new Github();
    private Triage triage = new Triage();
    private Engine engine = new Engine();
    private Verify verify = new Verify();
    private Learning learning = new Learning();

    public Devin getDevin() {
        return devin;
    }

    public void setDevin(Devin devin) {
        this.devin = devin;
    }

    public Github getGithub() {
        return github;
    }

    public void setGithub(Github github) {
        this.github = github;
    }

    public Triage getTriage() {
        return triage;
    }

    public void setTriage(Triage triage) {
        this.triage = triage;
    }

    public Engine getEngine() {
        return engine;
    }

    public void setEngine(Engine engine) {
        this.engine = engine;
    }

    public Learning getLearning() {
        return learning;
    }

    public void setLearning(Learning learning) {
        this.learning = learning;
    }

    public Verify getVerify() {
        return verify;
    }

    public void setVerify(Verify verify) {
        this.verify = verify;
    }

    public static class Devin {
        private String baseUrl = "https://api.devin.ai";
        private String apiKey = "";
        private String orgId = "";
        private Duration timeout = Duration.ofSeconds(30);
        private Integer criteriaAcuLimit = 3;
        private Integer remediationAcuLimit = 10;
        private boolean dryRun = false;

        public String getBaseUrl() {
            return baseUrl;
        }

        /** Owning organisation of the service user; every v3 session route is scoped to it. */
        public String getOrgId() {
            return orgId;
        }

        public void setOrgId(String orgId) {
            this.orgId = orgId;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

        public Integer getCriteriaAcuLimit() {
            return criteriaAcuLimit;
        }

        public void setCriteriaAcuLimit(Integer criteriaAcuLimit) {
            this.criteriaAcuLimit = criteriaAcuLimit;
        }

        public Integer getRemediationAcuLimit() {
            return remediationAcuLimit;
        }

        public void setRemediationAcuLimit(Integer remediationAcuLimit) {
            this.remediationAcuLimit = remediationAcuLimit;
        }

        public boolean isDryRun() {
            return dryRun;
        }

        public void setDryRun(boolean dryRun) {
            this.dryRun = dryRun;
        }
    }

    public static class Github {
        private String apiUrl = "https://api.github.com";
        private String token = "";
        private App app = new App();
        private String repo = "udaysagarn/superset";

        /** Repositories registered at startup, so a fresh container comes up with a working demo. */
        private List<String> repos = new ArrayList<>(List.of("udaysagarn/superset"));
        private String webhookSecret = "";
        private boolean pollingEnabled = true;
        private Duration pollInterval = Duration.ofSeconds(30);
        private String triggerLabel = "menD:fix";
        private String inProgressLabel = "menD:in-progress";
        private String prOpenLabel = "menD:pr-open";
        private String notCandidateLabel = "menD:not-a-candidate";
        private String doneLabel = "menD:done";
        private String needsHumanLabel = "menD:needs-human";
        private String unverifiedLabel = "menD:unverified";
        private String changesRequestedLabel = "menD:changes-requested";
        private boolean commentsEnabled = true;

        public String getApiUrl() {
            return apiUrl;
        }

        public void setApiUrl(String apiUrl) {
            this.apiUrl = apiUrl;
        }

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }

        /** GitHub App credentials; preferred over a personal access token when present. */
        public App getApp() {
            return app;
        }

        public void setApp(App app) {
            this.app = app;
        }

        public String getRepo() {
            return repo;
        }

        public List<String> getRepos() {
            return repos;
        }

        public void setRepos(List<String> repos) {
            this.repos = repos;
        }

        public void setRepo(String repo) {
            this.repo = repo;
        }

        public String getWebhookSecret() {
            return webhookSecret;
        }

        public void setWebhookSecret(String webhookSecret) {
            this.webhookSecret = webhookSecret;
        }

        public boolean isPollingEnabled() {
            return pollingEnabled;
        }

        public void setPollingEnabled(boolean pollingEnabled) {
            this.pollingEnabled = pollingEnabled;
        }

        public Duration getPollInterval() {
            return pollInterval;
        }

        public void setPollInterval(Duration pollInterval) {
            this.pollInterval = pollInterval;
        }

        public String getTriggerLabel() {
            return triggerLabel;
        }

        public void setTriggerLabel(String triggerLabel) {
            this.triggerLabel = triggerLabel;
        }

        public String getInProgressLabel() {
            return inProgressLabel;
        }

        public void setInProgressLabel(String inProgressLabel) {
            this.inProgressLabel = inProgressLabel;
        }

        public String getPrOpenLabel() {
            return prOpenLabel;
        }

        public void setPrOpenLabel(String prOpenLabel) {
            this.prOpenLabel = prOpenLabel;
        }

        public String getNotCandidateLabel() {
            return notCandidateLabel;
        }

        public void setNotCandidateLabel(String notCandidateLabel) {
            this.notCandidateLabel = notCandidateLabel;
        }

        public String getDoneLabel() {
            return doneLabel;
        }

        public void setDoneLabel(String doneLabel) {
            this.doneLabel = doneLabel;
        }

        public String getNeedsHumanLabel() {
            return needsHumanLabel;
        }

        public String getUnverifiedLabel() {
            return unverifiedLabel;
        }

        public void setUnverifiedLabel(String unverifiedLabel) {
            this.unverifiedLabel = unverifiedLabel;
        }

        public String getChangesRequestedLabel() {
            return changesRequestedLabel;
        }

        public void setChangesRequestedLabel(String changesRequestedLabel) {
            this.changesRequestedLabel = changesRequestedLabel;
        }

        public void setNeedsHumanLabel(String needsHumanLabel) {
            this.needsHumanLabel = needsHumanLabel;
        }

        public boolean isCommentsEnabled() {
            return commentsEnabled;
        }

        public void setCommentsEnabled(boolean commentsEnabled) {
            this.commentsEnabled = commentsEnabled;
        }
    }

    /**
     * GitHub App identity. An installation token is minted on demand from the app's private key and
     * expires after an hour, so nothing long-lived is held in the process.
     */
    public static class App {
        private String appId = "";
        private String installationId = "";
        private String privateKey = "";

        /** The app's URL slug, used to link an operator straight at its settings pages. */
        private String slug = "mend-bot-uday-demo";

        public String getSlug() {
            return slug;
        }

        public void setSlug(String slug) {
            this.slug = slug;
        }

        public String getAppId() {
            return appId;
        }

        public void setAppId(String appId) {
            this.appId = appId;
        }

        public String getInstallationId() {
            return installationId;
        }

        public void setInstallationId(String installationId) {
            this.installationId = installationId;
        }

        public String getPrivateKey() {
            return privateKey;
        }

        public void setPrivateKey(String privateKey) {
            this.privateKey = privateKey;
        }

        public boolean isConfigured() {
            return !appId.isBlank() && !installationId.isBlank() && !privateKey.isBlank();
        }
    }

    public static class Triage {
        private double minConfidence = 0.7;
        private int minBodyLength = 60;
        private List<String> labelDenylist = List.of("question", "discussion", "epic", "wontfix", "invalid");
        private int maxFilesInScope = 25;

        public double getMinConfidence() {
            return minConfidence;
        }

        public void setMinConfidence(double minConfidence) {
            this.minConfidence = minConfidence;
        }

        public int getMinBodyLength() {
            return minBodyLength;
        }

        public void setMinBodyLength(int minBodyLength) {
            this.minBodyLength = minBodyLength;
        }

        public List<String> getLabelDenylist() {
            return labelDenylist;
        }

        public void setLabelDenylist(List<String> labelDenylist) {
            this.labelDenylist = labelDenylist;
        }

        public int getMaxFilesInScope() {
            return maxFilesInScope;
        }

        public void setMaxFilesInScope(int maxFilesInScope) {
            this.maxFilesInScope = maxFilesInScope;
        }
    }

    /** The closed loop: reviewer feedback in, lessons out, lessons back into the next session. */
    public static class Learning {

        /** How often menD checks open pull requests for human reviews. */
        private Duration reviewPollInterval = Duration.ofMinutes(2);

        /** Rounds of reviewer feedback menD hands back before escalating to a human. */
        private int maxReviewRounds = 3;

        /** Run a retrospective session on terminal tasks that drew review feedback. */
        private boolean retrospectiveEnabled = true;

        private Integer retrospectiveAcuLimit = 2;

        /** Prompts are a budget: only the strongest lessons ride along. */
        private int maxLessonsInPrompt = 12;

        /** Chances a lesson gets before its record of not helping is held against it. */
        private int minApplicationsBeforeRetiring = 4;

        public Duration getReviewPollInterval() {
            return reviewPollInterval;
        }

        public void setReviewPollInterval(Duration reviewPollInterval) {
            this.reviewPollInterval = reviewPollInterval;
        }

        public int getMaxReviewRounds() {
            return maxReviewRounds;
        }

        public void setMaxReviewRounds(int maxReviewRounds) {
            this.maxReviewRounds = maxReviewRounds;
        }

        public boolean isRetrospectiveEnabled() {
            return retrospectiveEnabled;
        }

        public void setRetrospectiveEnabled(boolean retrospectiveEnabled) {
            this.retrospectiveEnabled = retrospectiveEnabled;
        }

        public Integer getRetrospectiveAcuLimit() {
            return retrospectiveAcuLimit;
        }

        public void setRetrospectiveAcuLimit(Integer retrospectiveAcuLimit) {
            this.retrospectiveAcuLimit = retrospectiveAcuLimit;
        }

        public int getMaxLessonsInPrompt() {
            return maxLessonsInPrompt;
        }

        public void setMaxLessonsInPrompt(int maxLessonsInPrompt) {
            this.maxLessonsInPrompt = maxLessonsInPrompt;
        }

        public int getMinApplicationsBeforeRetiring() {
            return minApplicationsBeforeRetiring;
        }

        public void setMinApplicationsBeforeRetiring(int minApplicationsBeforeRetiring) {
            this.minApplicationsBeforeRetiring = minApplicationsBeforeRetiring;
        }
    }

    /** How menD decides whether to believe a pull request. */
    public static class Verify {

        /** Workflow menD asks the target repository to run when its own CI proves nothing. */
        private String contractWorkflow = "mend-verify.yml";

        /** Check runs whose name starts with this are menD's own contract runs, not repo CI. */
        private String contractCheckPrefix = "menD";

        /** Last resort: a separate Devin session that only runs the commands and reports exit codes. */
        private boolean verifierSessionEnabled = true;

        private Integer verifierAcuLimit = 3;

        /** How long to wait for a tier to produce a verdict before dropping to the next one. */
        private Duration tierTimeout = Duration.ofMinutes(45);

        public String getContractWorkflow() {
            return contractWorkflow;
        }

        public void setContractWorkflow(String contractWorkflow) {
            this.contractWorkflow = contractWorkflow;
        }

        public String getContractCheckPrefix() {
            return contractCheckPrefix;
        }

        public void setContractCheckPrefix(String contractCheckPrefix) {
            this.contractCheckPrefix = contractCheckPrefix;
        }

        public boolean isVerifierSessionEnabled() {
            return verifierSessionEnabled;
        }

        public void setVerifierSessionEnabled(boolean verifierSessionEnabled) {
            this.verifierSessionEnabled = verifierSessionEnabled;
        }

        public Integer getVerifierAcuLimit() {
            return verifierAcuLimit;
        }

        public void setVerifierAcuLimit(Integer verifierAcuLimit) {
            this.verifierAcuLimit = verifierAcuLimit;
        }

        public Duration getTierTimeout() {
            return tierTimeout;
        }

        public void setTierTimeout(Duration tierTimeout) {
            this.tierTimeout = tierTimeout;
        }
    }

    public static class Engine {
        private boolean enabled = true;
        private Duration reconcileInterval = Duration.ofSeconds(15);

        /** Profiling is background work; it runs on a slower loop than remediation. */
        private Duration contextInterval = Duration.ofSeconds(60);

        private int maxConcurrentSessions = 4;
        private int maxAttempts = 2;
        private int maxNudges = 3;
        private Duration nudgeAfter = Duration.ofMinutes(10);
        private Duration sessionTimeout = Duration.ofHours(3);

        /**
         * How long a worker owns a task before other workers may take it over. Must comfortably
         * exceed {@link #reconcileInterval} so a healthy worker always heartbeats in time.
         */
        private Duration leaseDuration = Duration.ofMinutes(2);
        private Duration heartbeatInterval = Duration.ofSeconds(30);

        private Duration criteriaEta = Duration.ofMinutes(15);
        private Duration verifyEta = Duration.ofMinutes(30);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getReconcileInterval() {
            return reconcileInterval;
        }

        public void setReconcileInterval(Duration reconcileInterval) {
            this.reconcileInterval = reconcileInterval;
        }

        public Duration getContextInterval() {
            return contextInterval;
        }

        public void setContextInterval(Duration contextInterval) {
            this.contextInterval = contextInterval;
        }

        public int getMaxConcurrentSessions() {
            return maxConcurrentSessions;
        }

        public void setMaxConcurrentSessions(int maxConcurrentSessions) {
            this.maxConcurrentSessions = maxConcurrentSessions;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public int getMaxNudges() {
            return maxNudges;
        }

        public void setMaxNudges(int maxNudges) {
            this.maxNudges = maxNudges;
        }

        public Duration getNudgeAfter() {
            return nudgeAfter;
        }

        public void setNudgeAfter(Duration nudgeAfter) {
            this.nudgeAfter = nudgeAfter;
        }

        public Duration getSessionTimeout() {
            return sessionTimeout;
        }

        public void setSessionTimeout(Duration sessionTimeout) {
            this.sessionTimeout = sessionTimeout;
        }

        public Duration getLeaseDuration() {
            return leaseDuration;
        }

        public void setLeaseDuration(Duration leaseDuration) {
            this.leaseDuration = leaseDuration;
        }

        public Duration getHeartbeatInterval() {
            return heartbeatInterval;
        }

        public void setHeartbeatInterval(Duration heartbeatInterval) {
            this.heartbeatInterval = heartbeatInterval;
        }

        public Duration getCriteriaEta() {
            return criteriaEta;
        }

        public void setCriteriaEta(Duration criteriaEta) {
            this.criteriaEta = criteriaEta;
        }

        public Duration getVerifyEta() {
            return verifyEta;
        }

        public void setVerifyEta(Duration verifyEta) {
            this.verifyEta = verifyEta;
        }
    }
}
