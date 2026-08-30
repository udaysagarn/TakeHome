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
import jakarta.persistence.Version;
import java.time.Instant;
import org.hibernate.annotations.ColumnDefault;

/**
 * Something menD learned from a human reviewer, kept so the next issue does not repeat the mistake.
 *
 * <p>Learnings are split by {@link LearningScope}: repository learnings ride along with that
 * repository's profile into its own sessions, while general learnings are about how Devin should work
 * anywhere and carry a {@link RecommendedAction} naming where they really belong — an org knowledge
 * note, the repository's own instruction file, or menD's own backlog. A learning that keeps being
 * applied without reducing review feedback is retired rather than left to inflate every prompt.
 */
@Entity
@Table(
        name = "learning",
        indexes = {@Index(name = "idx_learning_repo", columnList = "repo,status")})
public class Learning {

    /** Twice-failed advice is not brought back a third time. */
    private static final int MAX_RETIREMENTS = 2;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private Long version;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private LearningScope scope = LearningScope.REPO;

    /** Owning repository for a {@link LearningScope#REPO} learning; null when general. */
    private String repo;

    /** Short handle for grouping, e.g. "tests" or "lockfiles". */
    @Column(length = 64)
    private String topic;

    /** The lesson itself, phrased as an instruction a future session can act on. */
    @Lob
    @Column(nullable = false)
    private String lesson;

    /** Why menD believes it: the review comment or PR that produced the lesson. */
    @Lob
    private String evidence;

    @Enumerated(EnumType.STRING)
    @Column(name = "recommended_action", length = 32)
    private RecommendedAction recommendedAction = RecommendedAction.PROMPT_PREAMBLE;

    /** Concrete next step for a human, when the recommendation is not menD's to carry out. */
    @Lob
    private String actionDetail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private LearningStatus status = LearningStatus.ACTIVE;

    /** Stable digest of the normalised lesson, so the same advice is never stored twice. */
    @Column(length = 64, unique = true)
    private String fingerprint;

    private String sourceRepo;
    private Integer sourceIssue;
    private String sourcePrUrl;

    @ColumnDefault("0")
    @Column(nullable = false)
    private int timesApplied;

    /** How often a pull request that used this learning still drew review feedback. */
    @ColumnDefault("0")
    @Column(nullable = false)
    private int timesFollowedByFeedback;

    /** How often this advice has been retired and then relearned from a later review. */
    @ColumnDefault("0")
    @Column(nullable = false)
    private int timesRetired;

    /** True when the lesson was retired while a human promotion was still outstanding. */
    @ColumnDefault("false")
    @Column(nullable = false)
    private boolean retiredBeforePromotion;

    private Double confidence;

    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();
    private Instant lastAppliedAt;
    private Instant retiredAt;

    protected Learning() {}

    public Learning(LearningScope scope, String repo, String topic, String lesson) {
        this.scope = scope;
        this.repo = scope == LearningScope.GENERAL ? null : repo;
        this.topic = topic;
        this.lesson = lesson;
    }

    /**
     * True once a learning has been applied enough times to judge it and has failed to keep reviewers
     * quiet more often than not. Prompts are a budget, not a scrapbook.
     */
    public boolean hasStoppedEarningItsPlace(int minApplications) {
        return timesApplied >= minApplications && timesFollowedByFeedback * 2 > timesApplied;
    }

    public void recordApplied(Instant when) {
        timesApplied++;
        lastAppliedAt = when;
        updatedAt = when;
    }

    public void recordFeedbackAfterUse(Instant when) {
        timesFollowedByFeedback++;
        updatedAt = when;
    }

    public void retire(Instant when) {
        status = LearningStatus.RETIRED;
        retiredBeforePromotion = needsHuman();
        timesRetired++;
        retiredAt = when;
        updatedAt = when;
    }

    /**
     * Brings retired advice back when a later review teaches it again, with its scorecard reset so it
     * is judged on the evidence from here on. Advice that has already failed twice stays retired.
     */
    public boolean reinstate(Instant when) {
        if (status != LearningStatus.RETIRED || timesRetired >= MAX_RETIREMENTS) {
            return false;
        }
        status = LearningStatus.ACTIVE;
        retiredAt = null;
        timesApplied = 0;
        timesFollowedByFeedback = 0;
        updatedAt = when;
        return true;
    }

    /** True when the recommendation is one only a human can carry out. */
    public boolean needsHuman() {
        return recommendedAction != RecommendedAction.PROMPT_PREAMBLE
                && recommendedAction != RecommendedAction.RETIRE;
    }

    public Long getId() {
        return id;
    }

    public LearningScope getScope() {
        return scope;
    }

    public void setScope(LearningScope scope) {
        this.scope = scope;
    }

    public String getRepo() {
        return repo;
    }

    public void setRepo(String repo) {
        this.repo = repo;
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public String getLesson() {
        return lesson;
    }

    public void setLesson(String lesson) {
        this.lesson = lesson;
    }

    public String getEvidence() {
        return evidence;
    }

    public void setEvidence(String evidence) {
        this.evidence = evidence;
    }

    public RecommendedAction getRecommendedAction() {
        return recommendedAction;
    }

    public void setRecommendedAction(RecommendedAction recommendedAction) {
        this.recommendedAction = recommendedAction;
    }

    public String getActionDetail() {
        return actionDetail;
    }

    public void setActionDetail(String actionDetail) {
        this.actionDetail = actionDetail;
    }

    public LearningStatus getStatus() {
        return status;
    }

    public void setStatus(LearningStatus status) {
        this.status = status;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public void setFingerprint(String fingerprint) {
        this.fingerprint = fingerprint;
    }

    public String getSourceRepo() {
        return sourceRepo;
    }

    public void setSourceRepo(String sourceRepo) {
        this.sourceRepo = sourceRepo;
    }

    public Integer getSourceIssue() {
        return sourceIssue;
    }

    public void setSourceIssue(Integer sourceIssue) {
        this.sourceIssue = sourceIssue;
    }

    public String getSourcePrUrl() {
        return sourcePrUrl;
    }

    public void setSourcePrUrl(String sourcePrUrl) {
        this.sourcePrUrl = sourcePrUrl;
    }

    public int getTimesApplied() {
        return timesApplied;
    }

    public int getTimesFollowedByFeedback() {
        return timesFollowedByFeedback;
    }

    public int getTimesRetired() {
        return timesRetired;
    }

    public boolean isRetiredBeforePromotion() {
        return retiredBeforePromotion;
    }

    public Double getConfidence() {
        return confidence;
    }

    public void setConfidence(Double confidence) {
        this.confidence = confidence;
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

    public Instant getLastAppliedAt() {
        return lastAppliedAt;
    }

    public Instant getRetiredAt() {
        return retiredAt;
    }
}
