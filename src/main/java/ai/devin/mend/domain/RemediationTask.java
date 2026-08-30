package ai.devin.mend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Duration;
import java.time.Instant;
import org.hibernate.annotations.ColumnDefault;

/** One remediation attempt lifecycle for one GitHub issue. */
@Entity
@Table(
        name = "remediation_task",
        uniqueConstraints = @UniqueConstraint(columnNames = {"repo", "issue_number"}),
        indexes = @Index(name = "idx_task_state", columnList = "state"))
public class RemediationTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private Long version;

    @Column(nullable = false)
    private String repo;

    @Column(name = "issue_number", nullable = false)
    private int issueNumber;

    @Column(length = 1024)
    private String issueTitle;

    private String issueUrl;

    @Column(length = 512)
    private String issueLabels;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private IssueState state = IssueState.DISCOVERED;

    @Lob
    @Column(name = "criteria_json")
    private String criteriaJson;

    private String criteriaHash;

    private Double confidence;

    @Column(length = 2048)
    private String exclusionReason;

    private String criteriaSessionId;
    private String criteriaSessionUrl;
    private String sessionId;
    private String sessionUrl;
    private String prUrl;
    private String ciStatus;

    @Column(length = 2048)
    private String lastError;

    @Lob
    private String outcomeJson;

    /** How the fix was proven, if it was; null until verification produces a verdict. */
    @Enumerated(EnumType.STRING)
    @Column(name = "verification_tier", length = 32)
    private Verification.Tier verificationTier;

    @Lob
    @Column(name = "verification_json")
    private String verificationJson;

    private String verifierSessionId;
    private String verifierSessionUrl;

    /** When the repository's menD contract workflow was asked to run for this pull request. */
    private Instant contractDispatchedAt;

    /** Newest human review or review comment already handed to the session; null until one arrives. */
    private Instant lastReviewAt;

    /** The reviewer feedback of the current round, as menD passed it to Devin. */
    @Lob
    private String feedbackJson;

    @ColumnDefault("0")
    @Column(nullable = false)
    private int reviewRounds;

    /** Set once the retrospective has turned this task's history into learnings. */
    @ColumnDefault("false")
    @Column(nullable = false)
    private boolean learningsExtracted;

    private String retrospectiveSessionId;

    private int attempts;
    private int nudges;
    private Integer acuBudget;

    /** Worker currently holding the lease on this task; null when unowned. */
    @Column(name = "owner_id", length = 128)
    private String ownerId;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @Column(name = "lease_acquired_at")
    private Instant leaseAcquiredAt;

    /** When the owner predicts this task reaches a terminal state; drives the overdue signal. */
    @Column(name = "eta_at")
    private Instant etaAt;

    /** How many times an expired lease was taken over from a dead worker. */
    @ColumnDefault("0")
    @Column(name = "lease_takeovers", nullable = false)
    private int leaseTakeovers;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    private Instant criteriaStartedAt;
    private Instant readyAt;
    private Instant dispatchedAt;
    private Instant prOpenedAt;
    private Instant completedAt;
    private Instant lastPolledAt;
    private Instant lastNudgedAt;

    protected RemediationTask() {}

    public RemediationTask(String repo, int issueNumber, String issueTitle, String issueUrl, String issueLabels) {
        this.repo = repo;
        this.issueNumber = issueNumber;
        this.issueTitle = issueTitle;
        this.issueUrl = issueUrl;
        this.issueLabels = issueLabels;
    }

    public String key() {
        return repo + "#" + issueNumber;
    }

    /** Wall-clock time from discovery to a pull request being opened. */
    public Duration timeToPr() {
        return prOpenedAt == null ? null : Duration.between(createdAt, prOpenedAt);
    }

    public boolean isLeased(Instant now) {
        return ownerId != null && leaseExpiresAt != null && leaseExpiresAt.isAfter(now);
    }

    /** True once the owner's own prediction of completion has passed. */
    public boolean isOverdue(Instant now) {
        return etaAt != null && !state.isTerminal() && now.isAfter(etaAt);
    }

    public Duration elapsed() {
        Instant end = completedAt != null ? completedAt : Instant.now();
        return Duration.between(createdAt, end);
    }

    public Long getId() {
        return id;
    }

    public String getRepo() {
        return repo;
    }

    public int getIssueNumber() {
        return issueNumber;
    }

    public String getIssueTitle() {
        return issueTitle;
    }

    public void setIssueTitle(String issueTitle) {
        this.issueTitle = issueTitle;
    }

    public String getIssueUrl() {
        return issueUrl;
    }

    public void setIssueUrl(String issueUrl) {
        this.issueUrl = issueUrl;
    }

    public String getIssueLabels() {
        return issueLabels;
    }

    public void setIssueLabels(String issueLabels) {
        this.issueLabels = issueLabels;
    }

    public IssueState getState() {
        return state;
    }

    public void setState(IssueState state) {
        this.state = state;
    }

    public String getCriteriaJson() {
        return criteriaJson;
    }

    public void setCriteriaJson(String criteriaJson) {
        this.criteriaJson = criteriaJson;
    }

    public String getCriteriaHash() {
        return criteriaHash;
    }

    public void setCriteriaHash(String criteriaHash) {
        this.criteriaHash = criteriaHash;
    }

    public Double getConfidence() {
        return confidence;
    }

    public void setConfidence(Double confidence) {
        this.confidence = confidence;
    }

    public String getExclusionReason() {
        return exclusionReason;
    }

    public void setExclusionReason(String exclusionReason) {
        this.exclusionReason = exclusionReason;
    }

    public String getCriteriaSessionId() {
        return criteriaSessionId;
    }

    public void setCriteriaSessionId(String criteriaSessionId) {
        this.criteriaSessionId = criteriaSessionId;
    }

    public String getCriteriaSessionUrl() {
        return criteriaSessionUrl;
    }

    public void setCriteriaSessionUrl(String criteriaSessionUrl) {
        this.criteriaSessionUrl = criteriaSessionUrl;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getSessionUrl() {
        return sessionUrl;
    }

    public void setSessionUrl(String sessionUrl) {
        this.sessionUrl = sessionUrl;
    }

    public String getPrUrl() {
        return prUrl;
    }

    public void setPrUrl(String prUrl) {
        this.prUrl = prUrl;
    }

    public String getCiStatus() {
        return ciStatus;
    }

    public void setCiStatus(String ciStatus) {
        this.ciStatus = ciStatus;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public Verification.Tier getVerificationTier() {
        return verificationTier;
    }

    public void setVerificationTier(Verification.Tier verificationTier) {
        this.verificationTier = verificationTier;
    }

    public String getVerificationJson() {
        return verificationJson;
    }

    public void setVerificationJson(String verificationJson) {
        this.verificationJson = verificationJson;
    }

    public String getVerifierSessionId() {
        return verifierSessionId;
    }

    public void setVerifierSessionId(String verifierSessionId) {
        this.verifierSessionId = verifierSessionId;
    }

    public String getVerifierSessionUrl() {
        return verifierSessionUrl;
    }

    public void setVerifierSessionUrl(String verifierSessionUrl) {
        this.verifierSessionUrl = verifierSessionUrl;
    }

    public Instant getLastReviewAt() {
        return lastReviewAt;
    }

    public void setLastReviewAt(Instant lastReviewAt) {
        this.lastReviewAt = lastReviewAt;
    }

    public String getFeedbackJson() {
        return feedbackJson;
    }

    public void setFeedbackJson(String feedbackJson) {
        this.feedbackJson = feedbackJson;
    }

    public int getReviewRounds() {
        return reviewRounds;
    }

    public void setReviewRounds(int reviewRounds) {
        this.reviewRounds = reviewRounds;
    }

    public boolean isLearningsExtracted() {
        return learningsExtracted;
    }

    public void setLearningsExtracted(boolean learningsExtracted) {
        this.learningsExtracted = learningsExtracted;
    }

    public String getRetrospectiveSessionId() {
        return retrospectiveSessionId;
    }

    public void setRetrospectiveSessionId(String retrospectiveSessionId) {
        this.retrospectiveSessionId = retrospectiveSessionId;
    }

    public Instant getContractDispatchedAt() {
        return contractDispatchedAt;
    }

    public void setContractDispatchedAt(Instant contractDispatchedAt) {
        this.contractDispatchedAt = contractDispatchedAt;
    }

    public String getOutcomeJson() {
        return outcomeJson;
    }

    public void setOutcomeJson(String outcomeJson) {
        this.outcomeJson = outcomeJson;
    }

    public int getAttempts() {
        return attempts;
    }

    public void setAttempts(int attempts) {
        this.attempts = attempts;
    }

    public int getNudges() {
        return nudges;
    }

    public void setNudges(int nudges) {
        this.nudges = nudges;
    }

    public Integer getAcuBudget() {
        return acuBudget;
    }

    public void setAcuBudget(Integer acuBudget) {
        this.acuBudget = acuBudget;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Instant getCriteriaStartedAt() {
        return criteriaStartedAt;
    }

    public void setCriteriaStartedAt(Instant criteriaStartedAt) {
        this.criteriaStartedAt = criteriaStartedAt;
    }

    public Instant getReadyAt() {
        return readyAt;
    }

    public void setReadyAt(Instant readyAt) {
        this.readyAt = readyAt;
    }

    public Instant getDispatchedAt() {
        return dispatchedAt;
    }

    public void setDispatchedAt(Instant dispatchedAt) {
        this.dispatchedAt = dispatchedAt;
    }

    public Instant getPrOpenedAt() {
        return prOpenedAt;
    }

    public void setPrOpenedAt(Instant prOpenedAt) {
        this.prOpenedAt = prOpenedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public Instant getLastPolledAt() {
        return lastPolledAt;
    }

    public void setLastPolledAt(Instant lastPolledAt) {
        this.lastPolledAt = lastPolledAt;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
    }

    public Instant getLeaseExpiresAt() {
        return leaseExpiresAt;
    }

    public void setLeaseExpiresAt(Instant leaseExpiresAt) {
        this.leaseExpiresAt = leaseExpiresAt;
    }

    public Instant getLeaseAcquiredAt() {
        return leaseAcquiredAt;
    }

    public void setLeaseAcquiredAt(Instant leaseAcquiredAt) {
        this.leaseAcquiredAt = leaseAcquiredAt;
    }

    public Instant getEtaAt() {
        return etaAt;
    }

    public void setEtaAt(Instant etaAt) {
        this.etaAt = etaAt;
    }

    public int getLeaseTakeovers() {
        return leaseTakeovers;
    }

    public void setLeaseTakeovers(int leaseTakeovers) {
        this.leaseTakeovers = leaseTakeovers;
    }

    public Instant getLastNudgedAt() {
        return lastNudgedAt;
    }

    public void setLastNudgedAt(Instant lastNudgedAt) {
        this.lastNudgedAt = lastNudgedAt;
    }
}
