package ai.devin.d1.domain;

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

    private int attempts;
    private int nudges;
    private Integer acuBudget;

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

    public Instant getLastNudgedAt() {
        return lastNudgedAt;
    }

    public void setLastNudgedAt(Instant lastNudgedAt) {
        this.lastNudgedAt = lastNudgedAt;
    }
}
