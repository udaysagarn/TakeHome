package ai.devin.mend.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.devin.mend.domain.IssueState;
import ai.devin.mend.domain.LearningRepository;
import ai.devin.mend.domain.RemediationTask;
import ai.devin.mend.domain.TaskRepository;
import ai.devin.mend.engine.Reconciler;
import ai.devin.mend.github.GitHubDtos;
import ai.devin.mend.ingest.IssuePoller;
import ai.devin.mend.learning.ReviewLoop;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Runs the credential-free demo the way a contributor does — file the four scenarios, let the loops
 * turn — and asserts each one lands where {@code deploy/simulate.sh} promises. This is the demo's
 * regression test: if a scenario stops landing on its documented state, the build fails rather than
 * the laptop demo.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("sandbox")
@TestPropertySource(
        properties = {
            "mend.engine.enabled=true",
            "mend.github.polling-enabled=true",
            "mend.github.repo=acme/superset",
            "mend.github.repos=acme/superset",
            "spring.datasource.url=jdbc:h2:mem:sandboxworkflow;DB_CLOSE_DELAY=-1"
        })
class SandboxWorkflowTest {

    private static final String REPO = "acme/superset";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private IssuePoller poller;

    @Autowired
    private Reconciler reconciler;

    @Autowired
    private TaskRepository tasks;

    @Autowired
    private LearningRepository learnings;

    @Autowired
    private ReviewLoop reviewLoop;

    @Autowired
    private SandboxHub hub;

    @Test
    void everyDocumentedScenarioLandsOnTheStateTheRunbookPromises() throws Exception {
        mvc.perform(post("/api/sandbox/issues/all")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(4));

        settle();

        assertThat(stateOf(SandboxScenario.CLEAN_FIX)).isEqualTo(IssueState.SUCCEEDED);
        assertThat(stateOf(SandboxScenario.NOT_A_CANDIDATE)).isEqualTo(IssueState.NOT_A_CANDIDATE);
        assertThat(stateOf(SandboxScenario.UNVERIFIED)).isEqualTo(IssueState.UNVERIFIED);
        assertThat(stateOf(SandboxScenario.REVIEW_THEN_FIX)).isEqualTo(IssueState.SUCCEEDED);
    }

    @Test
    void theRejectedPullRequestTeachesMenDSomethingItCanReuse() throws Exception {
        mvc.perform(post("/api/sandbox/issues").param("scenario", "REVIEW_THEN_FIX"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.number").isNumber());

        settle();

        RemediationTask task = taskFor(SandboxScenario.REVIEW_THEN_FIX);
        assertThat(task.getReviewRounds()).isPositive();
        assertThat(learnings.findAll()).isNotEmpty();
        assertThat(labelsOn(task)).containsExactly("menD:done");
    }

    @Test
    void theSandboxOverviewAdvertisesEveryScenarioAndTheSimulatedRepository() throws Exception {
        mvc.perform(get("/api/sandbox"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repository").value(REPO))
                .andExpect(jsonPath("$.scenarios.length()").value(SandboxScenario.values().length))
                .andExpect(jsonPath("$.state").exists());
    }

    @Test
    void reviewingAPullRequestThatWasNeverOpenedIsRejectedRatherThanInvented() throws Exception {
        mvc.perform(post("/api/sandbox/pulls/4242/request-changes"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("no simulated pull request #4242"));
    }

    @Test
    void aHumanCanPlayTheReviewerAndMenDAnswersTheirComment() throws Exception {
        mvc.perform(post("/api/sandbox/issues").param("scenario", "CLEAN_FIX")).andExpect(status().isOk());
        settle();

        RemediationTask task = taskFor(SandboxScenario.CLEAN_FIX);
        int pullNumber = Integer.parseInt(task.getPrUrl().substring(task.getPrUrl().lastIndexOf('/') + 1));

        mvc.perform(post("/api/sandbox/pulls/" + pullNumber + "/request-changes")
                        .contentType("application/json")
                        .content("{\"reviewer\":\"staff-engineer\",\"body\":\"add a spec next to the component\"}"))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------- stubbing

    /** Turns both loops until the pipeline stops changing, standing in for the demo's wall clock. */
    private void settle() {
        for (int tick = 0; tick < 40; tick++) {
            poller.poll();
            reconciler.tick();
            reviewLoop.tick();
        }
    }

    private RemediationTask taskFor(SandboxScenario scenario) {
        return tasks.findAll().stream()
                .filter(task -> REPO.equals(task.getRepo()))
                .filter(task -> scenario == hub.scenario(task.getRepo(), task.getIssueNumber()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no task was created for " + scenario));
    }

    private List<String> labelsOn(RemediationTask task) {
        return hub.issue(task.getRepo(), task.getIssueNumber()).orElseThrow().labels().stream()
                .map(GitHubDtos.Label::name)
                .toList();
    }

    private IssueState stateOf(SandboxScenario scenario) {
        return taskFor(scenario).getState();
    }

}
