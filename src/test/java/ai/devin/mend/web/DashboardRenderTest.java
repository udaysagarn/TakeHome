package ai.devin.mend.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.devin.mend.domain.IssueState;
import ai.devin.mend.domain.LearningRepository;
import ai.devin.mend.domain.LearningScope;
import ai.devin.mend.domain.RecommendedAction;
import ai.devin.mend.domain.RemediationTask;
import ai.devin.mend.domain.Repository;
import ai.devin.mend.domain.RepositoryRegistry;
import ai.devin.mend.domain.Retrospective;
import ai.devin.mend.domain.TaskRepository;
import ai.devin.mend.domain.Verification;
import ai.devin.mend.engine.TaskService;
import ai.devin.mend.learning.LearningService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Renders the monitoring view against populated state so template errors fail the build. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
        properties = {
            "mend.engine.enabled=false",
            "mend.github.polling-enabled=false",
            "spring.datasource.url=jdbc:h2:mem:dashboard;DB_CLOSE_DELAY=-1",
            "spring.jpa.hibernate.ddl-auto=create-drop"
        })
class DashboardRenderTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TaskRepository tasks;

    @Autowired
    private TaskService taskService;

    @Autowired
    private RepositoryRegistry repositories;

    @Autowired
    private LearningService learnings;

    @Autowired
    private LearningRepository learningRepository;

    @BeforeEach
    void seed() {
        tasks.deleteAll();
        repositories.deleteAll();
        learningRepository.deleteAll();
        repositories.save(new Repository("acme", "superset"));
        repositories.save(new Repository("acme", "airflow"));
        RemediationTask succeeded = task(101, "chore(frontend): bump vulnerable transitive dependency");
        succeeded.setConfidence(0.92);
        succeeded.setSessionUrl("https://app.devin.ai/sessions/abc");
        succeeded.setPrUrl("https://github.com/acme/superset/pull/9");
        succeeded.setCiStatus("PASSED");
        succeeded = taskService.save(succeeded);
        succeeded = taskService.transition(succeeded, IssueState.CRITERIA_PENDING, "scoping", "test");
        succeeded = taskService.transition(succeeded, IssueState.READY, "criteria established", "test");
        succeeded = taskService.transition(succeeded, IssueState.DISPATCHED, "session", "test");
        succeeded = taskService.transition(succeeded, IssueState.PR_OPEN, "pr", "test");
        succeeded = taskService.transition(succeeded, IssueState.VERIFYING, "ci", "test");
        taskService.transition(succeeded, IssueState.SUCCEEDED, "CI green", "test");

        RemediationTask excluded = task(102, "Rethink the chart picker");
        excluded.setExclusionReason("No verification commands could be derived, so a fix could not be proven.");
        taskService.transition(taskService.save(excluded), IssueState.NOT_A_CANDIDATE, "gate failed", "test");

        RemediationTask unverified = task(104, "fix(charts): guard against a null time grain");
        unverified.setPrUrl("https://github.com/acme/superset/pull/11");
        unverified.setCiStatus("UNAVAILABLE");
        unverified.setVerificationTier(Verification.Tier.NONE);
        unverified.setVerificationJson(
                """
                {"tier":"NONE","verdict":"UNAVAILABLE",\
                "summary":"The repository has no required checks and the contract workflow is not merged.",\
                "commands":[],"check_url":null}""");
        unverified.setReviewRounds(1);
        unverified.setFeedbackJson(
                "CHANGES_REQUESTED by @alice: please add a regression test\n\n"
                        + "superset/utils/date_parser.py:88 by @alice: this branch is still unguarded");
        unverified = taskService.save(unverified);
        unverified = taskService.transition(unverified, IssueState.READY, "criteria", "test");
        unverified = taskService.transition(unverified, IssueState.DISPATCHED, "session", "test");
        unverified = taskService.transition(unverified, IssueState.PR_OPEN, "pr", "test");
        unverified = taskService.transition(unverified, IssueState.VERIFYING, "verifying", "test");
        taskService.transition(unverified, IssueState.UNVERIFIED, "no independent evidence", "test");

        RemediationTask running = task(103, "fix(sqllab): stop swallowing driver errors");
        running.setSessionUrl("https://app.devin.ai/sessions/def");
        running = taskService.save(running);
        running = taskService.transition(running, IssueState.READY, "criteria in body", "test");
        running = taskService.transition(running, IssueState.DISPATCHED, "session", "test");
        taskService.transition(running, IssueState.RUNNING, "working", "test");
    }

    private RemediationTask task(int number, String title) {
        return new RemediationTask(
                "acme/superset", number, title, "https://github.com/acme/superset/issues/" + number, "bug");
    }

    @Test
    void theMonitoringViewRendersEveryPanel() throws Exception {
        mvc.perform(get("/pipeline"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("chore(frontend): bump")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Rethink the chart picker")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/css/devin.css")));
    }

    @Test
    void theOverviewLandsOnTheProductStoryAndEveryRegisteredRepository() throws Exception {
        String html = mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(html)
                .contains("acme/superset", "acme/airflow", "/img/architecture.svg", "/repositories/new")
                .contains("/pipeline?repo=acme/superset");
    }

    @Test
    void theRegistrationGuideExplainsEveryPermissionItAsksFor() throws Exception {
        String html = mvc.perform(get("/repositories/new"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(html).contains("Issues", "Pull requests", "Contents", "Checks", "Metadata", "menD:fix");
    }

    @Test
    void theBoardCanBeNarrowedToOneRepository() throws Exception {
        String superset = mvc.perform(get("/pipeline").param("repo", "acme/superset"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(superset).contains("chore(frontend): bump");

        String airflow = mvc.perform(get("/pipeline").param("repo", "acme/airflow"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(airflow).doesNotContain("chore(frontend): bump").contains("No issues ingested yet");
    }

    @Test
    void theLiveFragmentRendersForHtmxPolling() throws Exception {
        String html = mvc.perform(get("/fragments/live"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(html).contains("SUCCEEDED", "NOT_A_CANDIDATE", "RUNNING");
    }

    @Test
    void theSummaryApiCountsOutcomes() throws Exception {
        mvc.perform(get("/api/summary"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"succeeded\":1")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"excluded\":1")));
    }

    @Test
    void theLearningStorePageSeparatesRepositoryLessonsFromGeneralOnes() throws Exception {
        learnings.absorb(
                new Retrospective(
                        "summary",
                        List.of(
                                new Retrospective.Lesson(
                                        LearningScope.REPO,
                                        "tests",
                                        "Add a Jest spec beside every component change.",
                                        "alice's review",
                                        RecommendedAction.PROMPT_PREAMBLE,
                                        null,
                                        0.9),
                                new Retrospective.Lesson(
                                        LearningScope.GENERAL,
                                        "lockfiles",
                                        "Show the resolved-version diff for lockfile changes.",
                                        "three reviewers asked",
                                        RecommendedAction.DEVIN_KNOWLEDGE,
                                        "promote to an org-wide knowledge note",
                                        0.8))),
                "acme/superset",
                101,
                "https://github.com/acme/superset/pull/9");

        String html = mvc.perform(get("/learnings"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(html)
                .contains("Add a Jest spec beside every component change.")
                .contains("Show the resolved-version diff for lockfile changes.")
                .contains("Devin knowledge note")
                .contains("acme/superset");
    }

    @Test
    void theMarkdownReportIsGeneratedForLeadership() throws Exception {
        String report = mvc.perform(get("/api/report"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(report)
                .contains(
                        "# Autonomous remediation report",
                        "Remediated (independently verified)",
                        "issues/101");
    }

    @Test
    void anUnverifiedTaskIsOnTheBoardAndCountedApartFromRemediated() throws Exception {
        String html = mvc.perform(get("/pipeline"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(html)
                .contains("Unverified")
                .contains("fix(charts): guard against a null time grain")
                .contains("UNVERIFIED");

        // one succeeded, one unverified, nothing escalated: honest rate is 50%, not 100%.
        mvc.perform(get("/api/summary"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"unverified\":1")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"successRatePct\":50.0")));
    }

    @Test
    void theBoardHasAColumnForEveryStateAnIssueCanRestIn() throws Exception {
        String html = mvc.perform(get("/pipeline"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(html).contains("In review", "Unverified", "Excluded / escalated");
    }

    @Test
    void theTaskPageShowsWhyAFixIsUnverifiedAndWhatTheReviewerSaid() throws Exception {
        long id = tasks.findAll().stream()
                .filter(t -> t.getIssueNumber() == 104)
                .findFirst()
                .orElseThrow()
                .getId();
        String html = mvc.perform(get("/tasks/" + id))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(html)
                .contains("Verification evidence")
                .contains("NONE")
                .contains("UNAVAILABLE")
                .contains("nothing independent of the session that wrote the code")
                .contains("The repository has no required checks")
                .contains("Human review")
                .contains("CHANGES_REQUESTED by @alice: please add a regression test")
                .doesNotContain("{&quot;body&quot;");
    }

    @Test
    void theReportExplainsWhyEachUnverifiedFixIsUnverified() throws Exception {
        String report = mvc.perform(get("/api/report"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String unverifiedSection = report.substring(report.indexOf("## Unverified"));
        assertThat(unverifiedSection).contains("The repository has no required checks");
    }

    @Test
    void aSucceededTaskWithoutStructuredEvidenceSaysSoRatherThanClaimingNothingReported() throws Exception {
        long id = tasks.findAll().stream()
                .filter(t -> t.getIssueNumber() == 101)
                .findFirst()
                .orElseThrow()
                .getId();
        String html = mvc.perform(get("/tasks/" + id))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(html)
                .contains("closed before menD recorded structured evidence")
                .doesNotContain("Nothing independent has reported");
    }

    @Test
    void anExcludedTaskShowsNoVerificationSectionBecauseNothingWasEverAttempted() throws Exception {
        long id = tasks.findAll().stream()
                .filter(t -> t.getIssueNumber() == 102)
                .findFirst()
                .orElseThrow()
                .getId();
        String html = mvc.perform(get("/tasks/" + id))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(html).doesNotContain("Verification evidence");
    }

    @Test
    void theReportNamesEveryRegisteredRepositoryAndListsUnverifiedWorkSeparately() throws Exception {
        String report = mvc.perform(get("/api/report"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(report)
                .contains("`acme/superset`", "`acme/airflow`")
                .contains("## Unverified")
                .contains("pull/11");
    }

    @Test
    void aReasonContainingAPipeCannotBreakTheReportTable() throws Exception {
        RemediationTask piped = task(105, "Investigate flaky test");
        piped.setExclusionReason("Needs a human | the acceptance bar is a judgement call");
        taskService.transition(taskService.save(piped), IssueState.NOT_A_CANDIDATE, "gate", "test");

        String report = mvc.perform(get("/api/report"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(report).contains("Needs a human \\| the acceptance bar");
    }

    @Test
    void anUnknownTaskGetsTheProductsOwn404RatherThanWhitelabel() throws Exception {
        mvc.perform(get("/tasks/999999")).andExpect(status().isNotFound());

        // MockMvc resolves the 404 without the container's error dispatch, so render what a real
        // servlet container would forward to next.
        String html = mvc.perform(get("/error")
                        .requestAttr("jakarta.servlet.error.status_code", 404)
                        .requestAttr("jakarta.servlet.error.request_uri", "/tasks/999999"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(html)
                .contains("Nothing here")
                .contains("/css/devin.css")
                .doesNotContain("Whitelabel Error Page");
    }
}
