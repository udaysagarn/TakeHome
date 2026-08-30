package ai.devin.mend.learning;

import static org.assertj.core.api.Assertions.assertThat;

import ai.devin.mend.domain.Learning;
import ai.devin.mend.domain.LearningRepository;
import ai.devin.mend.domain.LearningScope;
import ai.devin.mend.domain.LearningStatus;
import ai.devin.mend.domain.RecommendedAction;
import ai.devin.mend.domain.Retrospective;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/** The learning store earns its place only if lessons are deduped, reused, and eventually dropped. */
@SpringBootTest
@TestPropertySource(
        properties = {
            "mend.engine.enabled=false",
            "mend.github.polling-enabled=false",
            "mend.learning.max-lessons-in-prompt=3",
            "mend.learning.min-applications-before-retiring=2",
            "spring.datasource.url=jdbc:h2:mem:learning;DB_CLOSE_DELAY=-1",
            "spring.jpa.hibernate.ddl-auto=create-drop"
        })
class LearningServiceTest {

    private static final String REPO = "acme/superset";
    private static final String OTHER = "acme/airflow";

    @Autowired
    private LearningService learnings;

    @Autowired
    private LearningRepository repository;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
    }

    @Test
    void aLessonLearnedTwiceIsStoredOnce() {
        learnings.absorb(retrospective(lesson(LearningScope.REPO, "Add a Jest spec for component changes.", 0.6)),
                REPO, 1, "https://github.com/acme/superset/pull/1");
        learnings.absorb(
                retrospective(lesson(LearningScope.REPO, "  add a JEST spec for component changes!  ", 0.9)),
                REPO, 2, "https://github.com/acme/superset/pull/2");

        List<Learning> stored = learnings.byScope(LearningScope.REPO);
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).getConfidence()).isEqualTo(0.9); // the stronger evidence wins
    }

    @Test
    void repositoryLessonsStayInTheirRepositoryButGeneralOnesTravel() {
        learnings.absorb(retrospective(lesson(LearningScope.REPO, "Run npm audit from superset-frontend.", 0.8)),
                REPO, 1, null);
        learnings.absorb(retrospective(lesson(LearningScope.GENERAL, "Show the resolved-version diff.", 0.8)),
                REPO, 2, null);

        assertThat(learnings.lessonsFor(REPO)).contains("npm audit").contains("resolved-version diff");
        assertThat(learnings.lessonsFor(OTHER)).doesNotContain("npm audit").contains("resolved-version diff");
    }

    @Test
    void theSameLessonIsKeptPerRepositoryWhenItIsRepositorySpecific() {
        String text = "Run the linter before pushing.";
        learnings.absorb(retrospective(lesson(LearningScope.REPO, text, 0.5)), REPO, 1, null);
        learnings.absorb(retrospective(lesson(LearningScope.REPO, text, 0.5)), OTHER, 1, null);

        assertThat(learnings.byScope(LearningScope.REPO)).hasSize(2);
    }

    @Test
    void onlyTheStrongestLessonsRideAlongSoPromptsDoNotGrowWithoutBound() {
        for (int i = 0; i < 6; i++) {
            learnings.absorb(
                    retrospective(lesson(LearningScope.REPO, "Lesson number " + i + " about tests.", i / 10.0)),
                    REPO, i, null);
        }

        assertThat(learnings.lessonsFor(REPO).lines()).hasSize(3); // max-lessons-in-prompt=3
        assertThat(learnings.lessonsFor(REPO)).contains("Lesson number 5").doesNotContain("Lesson number 0");
    }

    @Test
    void aLessonThatKeepsBeingFollowedByRejectionIsRetiredButKeptForTheRecord() {
        learnings.absorb(retrospective(lesson(LearningScope.REPO, "Always squash the commits.", 0.9)), REPO, 1, null);

        learnings.markApplied(REPO);
        learnings.recordFeedbackDespite(REPO);
        learnings.markApplied(REPO);
        learnings.recordFeedbackDespite(REPO);

        assertThat(learnings.byScope(LearningScope.REPO)).isEmpty();
        assertThat(learnings.lessonsFor(REPO)).isEmpty();
        assertThat(learnings.retired())
                .singleElement()
                .satisfies(l -> {
                    assertThat(l.getStatus()).isEqualTo(LearningStatus.RETIRED);
                    assertThat(l.getRetiredAt()).isNotNull();
                    assertThat(l.getTimesApplied()).isEqualTo(2);
                });
    }

    @Test
    void aLessonThatHelpsKeepsItsPlace() {
        learnings.absorb(retrospective(lesson(LearningScope.REPO, "Pin the transitive dependency.", 0.9)), REPO, 1, null);

        learnings.markApplied(REPO);
        learnings.markApplied(REPO);
        learnings.markApplied(REPO);
        learnings.recordFeedbackDespite(REPO);

        assertThat(learnings.byScope(LearningScope.REPO)).hasSize(1);
        assertThat(learnings.byScope(LearningScope.REPO).get(0).getTimesApplied()).isEqualTo(3);
    }

    @Test
    void lessonsMenDCannotActOnItselfAreSurfacedForAHuman() {
        learnings.absorb(
                new Retrospective(
                        "summary",
                        List.of(
                                new Retrospective.Lesson(
                                        LearningScope.GENERAL,
                                        "knowledge",
                                        "Devin should always show the resolved-version diff.",
                                        "three reviewers asked",
                                        RecommendedAction.DEVIN_KNOWLEDGE,
                                        "promote to an org knowledge note",
                                        0.9),
                                new Retrospective.Lesson(
                                        LearningScope.REPO,
                                        "tests",
                                        "Add a Jest spec for component changes.",
                                        "alice's review",
                                        RecommendedAction.PROMPT_PREAMBLE,
                                        null,
                                        0.8))),
                REPO,
                3,
                "https://github.com/acme/superset/pull/3");

        assertThat(learnings.recommendedActions())
                .singleElement()
                .satisfies(l -> assertThat(l.getRecommendedAction()).isEqualTo(RecommendedAction.DEVIN_KNOWLEDGE));
        assertThat(learnings.active()).hasSize(2);
    }

    @Test
    void aRetrospectiveIsAllowedToFindNothingWorthKeeping() {
        assertThat(learnings.absorb(new Retrospective("nothing generalisable here", List.of()), REPO, 1, null))
                .isEmpty();
        assertThat(learnings.lessonsFor(REPO)).isEmpty();
    }

    @Test
    void provenanceIsStoredSoAnyLessonCanBeTracedBackToTheReviewThatCausedIt() {
        learnings.absorb(
                retrospective(lesson(LearningScope.REPO, "Keep migrations in their own pull request.", 0.7)),
                REPO,
                42,
                "https://github.com/acme/superset/pull/42");

        assertThat(learnings.byScope(LearningScope.REPO)).singleElement().satisfies(l -> {
            assertThat(l.getSourceRepo()).isEqualTo(REPO);
            assertThat(l.getSourceIssue()).isEqualTo(42);
            assertThat(l.getSourcePrUrl()).endsWith("/pull/42");
            assertThat(l.getEvidence()).isNotBlank();
        });
    }

    private static Retrospective retrospective(Retrospective.Lesson... lessons) {
        return new Retrospective("summary", List.of(lessons));
    }

    private static Retrospective.Lesson lesson(LearningScope scope, String text, double confidence) {
        return new Retrospective.Lesson(
                scope, "tests", text, "a human reviewer said so", RecommendedAction.PROMPT_PREAMBLE, null, confidence);
    }
}
