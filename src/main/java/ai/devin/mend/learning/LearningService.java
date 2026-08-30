package ai.devin.mend.learning;

import ai.devin.mend.config.MendProperties;
import ai.devin.mend.domain.Learning;
import ai.devin.mend.domain.LearningRepository;
import ai.devin.mend.domain.LearningScope;
import ai.devin.mend.domain.LearningStatus;
import ai.devin.mend.domain.RecommendedAction;
import ai.devin.mend.domain.Retrospective;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The closed loop: what human reviewers say on menD's pull requests becomes durable advice that later
 * sessions are primed with.
 *
 * <p>Two things keep this from degenerating into an ever-growing prompt. Lessons are deduplicated by
 * a fingerprint of their normalised text, so the same advice is never stored twice; and every lesson
 * records whether the pull requests that used it still drew review feedback, so advice that does not
 * earn its place is retired rather than accumulated.
 */
@Service
public class LearningService {

    private static final Logger log = LoggerFactory.getLogger(LearningService.class);

    private final LearningRepository learnings;
    private final MendProperties props;

    public LearningService(LearningRepository learnings, MendProperties props) {
        this.learnings = learnings;
        this.props = props;
    }

    /**
     * The lessons injected into a repository's sessions: its own, plus the general ones menD applies
     * everywhere. Capped, highest confidence first, so the prompt stays a prompt.
     */
    @Transactional(readOnly = true)
    public String lessonsFor(String repo) {
        List<Learning> applicable = applicableTo(repo);
        if (applicable.isEmpty()) {
            return "";
        }
        return applicable.stream()
                .map(l -> "- [%s] %s".formatted(l.getTopic() == null ? "general" : l.getTopic(), l.getLesson()))
                .collect(Collectors.joining("\n"));
    }

    /** Records that the lessons for a repository went into a session, for the earn-its-place count. */
    @Transactional
    public void markApplied(String repo) {
        Instant now = Instant.now();
        for (Learning learning : applicableTo(repo)) {
            learning.recordApplied(now);
        }
    }

    /**
     * Records that a pull request drew reviewer feedback despite the lessons it was primed with, and
     * retires the ones that have had enough chances.
     */
    @Transactional
    public void recordFeedbackDespite(String repo) {
        Instant now = Instant.now();
        int minApplications = props.getLearning().getMinApplicationsBeforeRetiring();
        for (Learning learning : applicableTo(repo)) {
            learning.recordFeedbackAfterUse(now);
            if (learning.hasStoppedEarningItsPlace(minApplications)) {
                learning.retire(now);
                log.info(
                        "retired learning {} — applied {} times, still followed by feedback {} times",
                        learning.getId(),
                        learning.getTimesApplied(),
                        learning.getTimesFollowedByFeedback());
            }
        }
    }

    /** Folds a retrospective's lessons into the store, ignoring ones already known. */
    @Transactional
    public List<Learning> absorb(Retrospective retrospective, String repo, Integer issueNumber, String prUrl) {
        List<Learning> stored = new ArrayList<>();
        for (Retrospective.Lesson lesson : retrospective.lessons()) {
            if (lesson.lesson() == null || lesson.lesson().isBlank()) {
                continue;
            }
            String fingerprint = fingerprint(lesson.scope(), repo, lesson.lesson());
            Optional<Learning> existing = learnings.findByFingerprint(fingerprint);
            if (existing.isPresent()) {
                Learning known = existing.get();
                known.setConfidence(higher(known.getConfidence(), lesson.confidence()));
                known.setUpdatedAt(Instant.now());
                continue;
            }
            LearningScope scope = lesson.scope() == null ? LearningScope.REPO : lesson.scope();
            Learning learning = new Learning(scope, repo, lesson.topic(), lesson.lesson().strip());
            learning.setEvidence(lesson.evidence());
            learning.setRecommendedAction(
                    lesson.recommendedAction() == null ? RecommendedAction.PROMPT_PREAMBLE : lesson.recommendedAction());
            learning.setActionDetail(lesson.actionDetail());
            learning.setConfidence(lesson.confidence());
            learning.setFingerprint(fingerprint);
            learning.setSourceRepo(repo);
            learning.setSourceIssue(issueNumber);
            learning.setSourcePrUrl(prUrl);
            stored.add(learnings.save(learning));
        }
        return stored;
    }

    @Transactional(readOnly = true)
    public List<Learning> active() {
        return learnings.findByStatusOrderByUpdatedAtDesc(LearningStatus.ACTIVE);
    }

    /**
     * Active learnings whose recommendation menD cannot carry out itself — promoting to org-wide Devin
     * knowledge, editing the repository's instruction file, or fixing menD. These are the loop's output
     * to humans, and the dashboard shows them as such.
     */
    @Transactional(readOnly = true)
    public List<Learning> recommendedActions() {
        return active().stream()
                .filter(l -> l.getRecommendedAction() != RecommendedAction.PROMPT_PREAMBLE
                        && l.getRecommendedAction() != RecommendedAction.RETIRE)
                .sorted(Comparator.comparing(Learning::getUpdatedAt).reversed())
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Learning> byScope(LearningScope scope) {
        return learnings.findByScopeAndStatus(scope, LearningStatus.ACTIVE).stream()
                .sorted(Comparator.comparing(Learning::getUpdatedAt).reversed())
                .toList();
    }

    /** Kept for the audit trail: advice menD tried and stopped believing. */
    @Transactional(readOnly = true)
    public List<Learning> retired() {
        return learnings.findByStatusOrderByUpdatedAtDesc(LearningStatus.RETIRED);
    }

    private List<Learning> applicableTo(String repo) {
        List<Learning> applicable = new ArrayList<>(learnings.findByRepoAndStatus(repo, LearningStatus.ACTIVE));
        applicable.addAll(learnings.findByScopeAndStatus(LearningScope.GENERAL, LearningStatus.ACTIVE));
        return applicable.stream()
                .sorted(Comparator.comparing(
                        (Learning l) -> l.getConfidence() == null ? 0.5 : l.getConfidence())
                        .reversed())
                .limit(props.getLearning().getMaxLessonsInPrompt())
                .toList();
    }

    private static Double higher(Double a, Double b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return Math.max(a, b);
    }

    /**
     * Deduplication key. Case, punctuation and whitespace are stripped so the same advice phrased
     * twice does not become two lessons; general lessons are keyed without a repository so they are
     * shared rather than relearned per repo.
     */
    static String fingerprint(LearningScope scope, String repo, String lesson) {
        String normalised = lesson.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").strip();
        String keyed = (scope == LearningScope.GENERAL ? "general" : repo) + "|" + normalised;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(keyed.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JDK", e);
        }
    }
}
