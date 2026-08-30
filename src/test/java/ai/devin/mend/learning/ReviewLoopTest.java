package ai.devin.mend.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.devin.mend.devin.DevinApiClient;
import ai.devin.mend.devin.DevinDtos;
import ai.devin.mend.domain.IssueState;
import ai.devin.mend.domain.Learning;
import ai.devin.mend.domain.LearningRepository;
import ai.devin.mend.domain.LearningScope;
import ai.devin.mend.domain.RemediationTask;
import ai.devin.mend.domain.TaskEventRepository;
import ai.devin.mend.domain.TaskRepository;
import ai.devin.mend.engine.TaskService;
import ai.devin.mend.github.GitHubClient;
import ai.devin.mend.github.GitHubDtos;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;

/** The closed loop: a human rejects a pull request, menD answers, and the lesson outlives the task. */
@SpringBootTest
@TestPropertySource(
        properties = {
            "mend.engine.enabled=false",
            "mend.github.polling-enabled=false",
            "mend.github.repo=acme/superset",
            "mend.learning.max-review-rounds=2",
            "spring.datasource.url=jdbc:h2:mem:reviewloop;DB_CLOSE_DELAY=-1",
            "spring.jpa.hibernate.ddl-auto=create-drop"
        })
class ReviewLoopTest {

    private static final String REPO = "acme/superset";
    private static final String PR = "https://github.com/acme/superset/pull/7";

    @Autowired
    private ReviewLoop reviewLoop;

    @Autowired
    private LearningService learnings;

    @Autowired
    private TaskRepository tasks;

    @Autowired
    private TaskService taskService;

    @Autowired
    private TaskEventRepository events;

    @Autowired
    private LearningRepository learningRepository;

    @Autowired
    private ObjectMapper mapper;

    @MockBean
    private DevinApiClient devin;

    @MockBean
    private GitHubClient github;

    @BeforeEach
    void setUp() {
        tasks.deleteAll();
        events.deleteAll();
        learningRepository.deleteAll();
        when(github.isConfigured()).thenReturn(true);
        when(github.listReviews(anyString(), anyInt())).thenReturn(List.of());
        when(github.listReviewComments(anyString(), anyInt())).thenReturn(List.of());
    }

    @Test
    void aRejectionReopensTheTaskAndTheFeedbackReachesTheSessionThatWroteTheCode() {
        RemediationTask task = taskWithOpenPr(101);
        when(github.listReviews(REPO, 7))
                .thenReturn(List.of(review("CHANGES_REQUESTED", "alice", "Add a regression test.", minutesAgo(5))));

        reviewLoop.collectFeedback(reload(task));
        assertThat(reload(task).getState()).isEqualTo(IssueState.CHANGES_REQUESTED);
        assertThat(reload(task).getFeedbackJson()).contains("Add a regression test.");

        reviewLoop.respondToFeedback(reload(task));

        assertThat(reload(task).getState()).isEqualTo(IssueState.RUNNING);
        assertThat(reload(task).getReviewRounds()).isEqualTo(1);
        verify(devin).sendMessage(eq("devin-fix"), contains("Add a regression test."));
    }

    @Test
    void theSameReviewIsNeverActedOnTwice() {
        RemediationTask task = taskWithOpenPr(102);
        Instant submitted = minutesAgo(10);
        when(github.listReviews(REPO, 7))
                .thenReturn(List.of(review("CHANGES_REQUESTED", "alice", "Please split this up.", submitted)));

        reviewLoop.collectFeedback(reload(task));
        RemediationTask afterFirst = reload(task);
        assertThat(afterFirst.getLastReviewAt()).isEqualTo(submitted);

        taskService.transition(afterFirst, IssueState.RUNNING, "handed back", "test");
        reviewLoop.collectFeedback(reload(task));

        assertThat(reload(task).getState()).isEqualTo(IssueState.RUNNING);
    }

    @Test
    void menDsOwnCommentsCannotFeedTheLoop() {
        RemediationTask task = taskWithOpenPr(103);
        when(github.listReviews(REPO, 7))
                .thenReturn(List.of(new GitHubDtos.Review(
                        1L,
                        new GitHubDtos.User("mend-bot-uday-demo[bot]", "Bot"),
                        "CHANGES_REQUESTED",
                        "menD verification evidence",
                        PR,
                        minutesAgo(3))));

        reviewLoop.collectFeedback(reload(task));

        assertThat(reload(task).getState()).isEqualTo(IssueState.PR_OPEN);
        assertThat(reload(task).getFeedbackJson()).isNull();
    }

    @Test
    void inlineCommentsAreRecordedWithoutReopeningAPullRequestNobodyRejected() {
        RemediationTask task = taskWithOpenPr(104);
        when(github.listReviewComments(REPO, 7))
                .thenReturn(List.of(new GitHubDtos.ReviewComment(
                        2L,
                        new GitHubDtos.User("bob", "User"),
                        "This constant belongs in the config.",
                        "src/main/java/Foo.java",
                        42,
                        PR,
                        minutesAgo(2))));

        reviewLoop.collectFeedback(reload(task));

        RemediationTask after = reload(task);
        assertThat(after.getState()).isEqualTo(IssueState.PR_OPEN);
        assertThat(after.getFeedbackJson()).contains("src/main/java/Foo.java:42").contains("belongs in the config");
    }

    @Test
    void menDStopsGuessingOnceTheReviewerHasRejectedItTooManyTimes() {
        RemediationTask task = taskWithOpenPr(105);
        task.setReviewRounds(2); // mend.learning.max-review-rounds=2
        task = taskService.save(task);
        taskService.transition(task, IssueState.CHANGES_REQUESTED, "rejected again", "test");

        reviewLoop.respondToFeedback(reload(task));

        assertThat(reload(task).getState()).isEqualTo(IssueState.NEEDS_HUMAN);
        verify(devin, never()).sendMessage(anyString(), anyString());
    }

    @Test
    void aPullRequestClosedWithoutMergingIsAFailureSignalNotASuccess() {
        RemediationTask task = taskWithOpenPr(106);

        String result = reviewLoop.onPullRequestEvent(REPO, PR, true);

        assertThat(result).contains("escalated");
        assertThat(reload(task).getState()).isEqualTo(IssueState.NEEDS_HUMAN);
    }

    @Test
    void aWebhookForAPullRequestMenDDoesNotOwnIsIgnored() {
        assertThat(reviewLoop.onPullRequestEvent(REPO, "https://github.com/acme/superset/pull/999", false))
                .startsWith("ignored");
    }

    @Test
    void theRetrospectiveTurnsReviewFeedbackIntoLessonsTheNextSessionWillSee() {
        RemediationTask task = taskWithOpenPr(107);
        task.setFeedbackJson("CHANGES_REQUESTED by @alice: tests missing");
        task = taskService.save(task);
        taskService.transition(task, IssueState.SUCCEEDED, "merged after revisions", "test");

        when(devin.isConfigured()).thenReturn(true);
        when(devin.createSession(anyString(), anyString(), anyList(), any(), any(), anyString()))
                .thenReturn(session("devin-retro", null));
        reviewLoop.retrospect(reload(task));
        assertThat(reload(task).getRetrospectiveSessionId()).isEqualTo("devin-retro");
        assertThat(reload(task).isLearningsExtracted()).isFalse();

        when(devin.getSession("devin-retro")).thenReturn(Optional.of(session("devin-retro", retrospectiveJson())));
        reviewLoop.retrospect(reload(task));

        assertThat(reload(task).isLearningsExtracted()).isTrue();
        assertThat(learnings.byScope(LearningScope.REPO))
                .extracting(Learning::getLesson)
                .contains("Add a Jest spec beside every component change in superset-frontend.");
        assertThat(learnings.lessonsFor(REPO)).contains("Jest spec").contains("resolved-version diff");
    }

    @Test
    void aRetrospectiveThatNeverProducesOutputDoesNotLeaveTheTaskWaitingForever() {
        RemediationTask task = taskWithOpenPr(108);
        task.setFeedbackJson("CHANGES_REQUESTED by @alice: no");
        task.setRetrospectiveSessionId("devin-retro-dead");
        task = taskService.save(task);
        taskService.transition(task, IssueState.UNVERIFIED, "nothing could prove it", "test");

        when(devin.isConfigured()).thenReturn(true);
        when(devin.getSession("devin-retro-dead")).thenReturn(Optional.empty());
        reviewLoop.retrospect(reload(task));

        assertThat(reload(task).isLearningsExtracted()).isTrue();
    }

    // ------------------------------------------------------------- fixtures

    private RemediationTask taskWithOpenPr(int issueNumber) {
        RemediationTask task = new RemediationTask(
                REPO,
                issueNumber,
                "chore: bump nth-check",
                "https://github.com/acme/superset/issues/" + issueNumber,
                "");
        task.setSessionId("devin-fix");
        task.setPrUrl(PR);
        task = taskService.save(task);
        taskService.transition(task, IssueState.CRITERIA_PENDING, "scoping", "test");
        taskService.transition(reload(task), IssueState.READY, "accepted", "test");
        taskService.transition(reload(task), IssueState.DISPATCHED, "dispatched", "test");
        taskService.transition(reload(task), IssueState.PR_OPEN, "pull request opened", "test");
        return reload(task);
    }

    private RemediationTask reload(RemediationTask task) {
        return tasks.findById(task.getId()).orElseThrow();
    }

    private GitHubDtos.Review review(String state, String login, String body, Instant submittedAt) {
        return new GitHubDtos.Review(
                System.nanoTime(), new GitHubDtos.User(login, "User"), state, body, PR, submittedAt);
    }

    private static Instant minutesAgo(int minutes) {
        return Instant.now().minus(minutes, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MILLIS);
    }

    private DevinDtos.SessionDetails session(String id, JsonNode structuredOutput) {
        return new DevinDtos.SessionDetails(
                id,
                "https://app.devin.ai/sessions/" + id,
                "running",
                structuredOutput == null ? "working" : "finished",
                "menD retrospective",
                List.of(),
                structuredOutput,
                List.of(),
                0.0,
                null,
                null);
    }

    private JsonNode retrospectiveJson() {
        var repoLesson = mapper.createObjectNode();
        repoLesson.put("scope", "REPO");
        repoLesson.put("topic", "tests");
        repoLesson.put("lesson", "Add a Jest spec beside every component change in superset-frontend.");
        repoLesson.put("evidence", "alice rejected the pull request for a missing regression test");
        repoLesson.put("recommended_action", "PROMPT_PREAMBLE");
        repoLesson.put("action_detail", "inject into this repository's sessions");
        repoLesson.put("confidence", 0.9);

        var generalLesson = mapper.createObjectNode();
        generalLesson.put("scope", "GENERAL");
        generalLesson.put("topic", "lockfiles");
        generalLesson.put("lesson", "Show the resolved-version diff whenever a pull request touches a lockfile.");
        generalLesson.put("evidence", "reviewers asked what actually changed");
        generalLesson.put("recommended_action", "DEVIN_KNOWLEDGE");
        generalLesson.put("action_detail", "worth a knowledge note for the whole organisation");
        generalLesson.put("confidence", 0.7);

        var node = mapper.createObjectNode();
        node.put("summary", "the reviewer wanted tests and a clearer lockfile diff");
        node.set("lessons", mapper.createArrayNode().add(repoLesson).add(generalLesson));
        return node;
    }
}
