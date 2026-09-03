package ai.devin.mend.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.devin.mend.domain.AccessState;
import ai.devin.mend.domain.IssueState;
import ai.devin.mend.domain.RemediationTask;
import ai.devin.mend.domain.Repository;
import ai.devin.mend.domain.RepositoryRegistry;
import ai.devin.mend.domain.SuccessCriteria;
import ai.devin.mend.domain.TaskRepository;
import ai.devin.mend.engine.Orchestrator;
import ai.devin.mend.engine.TaskService;
import ai.devin.mend.github.GitHubClient;
import ai.devin.mend.github.GitHubDtos;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Pins the public JSON contract — routes, field names and status codes — because the wiki documents
 * it and scripts depend on it. A rename that breaks a consumer should break the build first.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
        properties = {
            "mend.engine.enabled=false",
            "mend.github.polling-enabled=false",
            "mend.github.repos=",
            "spring.datasource.url=jdbc:h2:mem:apicontract;DB_CLOSE_DELAY=-1"
        })
class ApiContractTest {

    private static final String SLUG = "acme/superset";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TaskRepository tasks;

    @Autowired
    private TaskService taskService;

    @Autowired
    private RepositoryRegistry repositories;

    @Autowired
    private ObjectMapper json;

    @MockBean
    private GitHubClient github;

    @MockBean
    private Orchestrator orchestrator;

    private long taskId;

    @BeforeEach
    void seed() {
        tasks.deleteAll();
        repositories.deleteAll();

        when(github.isConfigured()).thenReturn(true);
        when(github.getRepo(anyString())).thenReturn(Optional.of(remoteRepo()));
        when(github.installationRepos()).thenReturn(List.of(remoteRepo()));
        when(github.installationPermissions()).thenReturn(Map.of("issues", "write", "pull_requests", "write"));
        when(github.defaultRepo()).thenReturn(SLUG);

        Repository repository = new Repository("acme", "superset");
        repository.markValidated("master", null);
        repository.setTriggerLabel("menD:fix");
        repositories.save(repository);

        RemediationTask task = new RemediationTask(
                SLUG, 108, "Replace explicit any", "https://github.com/acme/superset/issues/108", "menD:fix");
        task.setConfidence(0.86);
        task.setCriteriaJson(criteriaJson());
        task.setCriteriaHash("b7d5bee864f20b14");
        task = tasks.save(task);
        task = taskService.transition(task, IssueState.READY, "criteria in body", "test");
        taskId = task.getId();
    }

    @Test
    void summaryCarriesTheKpisTheWikiDocuments() throws Exception {
        mvc.perform(get("/api/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.inFlight").value(1))
                .andExpect(jsonPath("$.succeeded").value(0))
                .andExpect(jsonPath("$.unverified").value(0))
                .andExpect(jsonPath("$.excluded").value(0))
                .andExpect(jsonPath("$.escalated").value(0))
                .andExpect(jsonPath("$.acuBudgeted").exists())
                .andExpect(jsonPath("$.engineerHoursAvoided").exists());
    }

    @Test
    void statesReportsEveryStateIncludingTheZeroes() throws Exception {
        mvc.perform(get("/api/states"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.READY").value(1))
                .andExpect(jsonPath("$.SUCCEEDED").value(0))
                .andExpect(jsonPath("$.UNVERIFIED").value(0))
                .andExpect(jsonPath("$.NOT_A_CANDIDATE").value(0));
    }

    @Test
    void taskRowsCarryTheDocumentedFields() throws Exception {
        mvc.perform(get("/api/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(taskId))
                .andExpect(jsonPath("$[0].repo").value(SLUG))
                .andExpect(jsonPath("$[0].issueNumber").value(108))
                .andExpect(jsonPath("$[0].state").value("READY"))
                .andExpect(jsonPath("$[0].bucket").value("in_flight"))
                .andExpect(jsonPath("$[0].confidence").value(0.86))
                .andExpect(jsonPath("$[0].attempts").value(0));
    }

    @Test
    void taskDetailExposesTheCriteriaContractAndTheLease() throws Exception {
        mvc.perform(get("/api/tasks/" + taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.criteria.acceptance_criteria[0]").value("The defect no longer reproduces"))
                .andExpect(jsonPath("$.criteria.verification_commands[0]").value("npm test -- --run"))
                .andExpect(jsonPath("$.criteriaHash").value("b7d5bee864f20b14"))
                .andExpect(jsonPath("$.lease.status").exists())
                .andExpect(jsonPath("$.lease.takeovers").value(0));
    }

    @Test
    void timelineIsTheAppendOnlyHistory() throws Exception {
        mvc.perform(get("/api/tasks/" + taskId + "/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].taskKey").value(SLUG + "#108"))
                .andExpect(jsonPath("$[0].fromState").value("DISCOVERED"))
                .andExpect(jsonPath("$[0].toState").value("READY"))
                .andExpect(jsonPath("$[0].actor").value("test"))
                .andExpect(jsonPath("$[0].occurredAt").exists());
    }

    @Test
    void unknownTaskIsFourOhFourEverywhere() throws Exception {
        mvc.perform(get("/api/tasks/9999")).andExpect(status().isNotFound());
        mvc.perform(post("/api/tasks/9999/cancel")).andExpect(status().isNotFound());
        mvc.perform(post("/api/repositories/9999/validate")).andExpect(status().isNotFound());
    }

    @Test
    void cancellingReturnsTheNewState() throws Exception {
        mvc.perform(post("/api/tasks/" + taskId + "/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("CANCELLED"));
    }

    @Test
    void ingestQueuesTheIssueAndAnswersWithItsKey() throws Exception {
        GitHubDtos.Issue issue = new GitHubDtos.Issue(
                108, "Replace explicit any", "body", "open", "https://github.com/acme/superset/issues/108",
                List.of(), null, null, null);
        when(github.getIssue(SLUG, 108)).thenReturn(Optional.of(issue));
        when(orchestrator.onTriggerLabel(anyString(), any())).thenReturn(tasks.findById(taskId).orElseThrow());

        mvc.perform(post("/api/issues/108/ingest").param("repo", SLUG))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.task").value(SLUG + "#108"));
    }

    @Test
    void ingestOfAnIssueGitHubCannotSeeIsFourOhFour() throws Exception {
        when(github.getIssue(anyString(), anyInt())).thenReturn(Optional.empty());
        mvc.perform(post("/api/issues/404/ingest").param("repo", SLUG)).andExpect(status().isNotFound());
    }

    @Test
    void registrationRejectsAMalformedSlugWithAReason() throws Exception {
        mvc.perform(post("/api/repositories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repo\":\"notaslug\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("expected owner/name, got: notaslug"));
    }

    @Test
    void registrationWithoutASlugIsABadRequestNotAServerError() throws Exception {
        mvc.perform(post("/api/repositories").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("repo: repo is required"));
        mvc.perform(post("/api/repositories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repo\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("repo: repo is required"));
        mvc.perform(post("/api/repositories").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("request body is not valid JSON"));
        mvc.perform(get("/api/repositories")).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void cancellingAFinishedTaskIsAConflictWithAReasonNotAServerError() throws Exception {
        RemediationTask done = new RemediationTask(SLUG, 109, "t", "https://github.com/acme/superset/issues/109", "menD:fix");
        done.setState(IssueState.SUCCEEDED);
        done = tasks.save(done);

        mvc.perform(post("/api/tasks/" + done.getId() + "/cancel"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(
                        "illegal transition for " + SLUG + "#109: SUCCEEDED -> CANCELLED"));
        assertThat(tasks.findById(done.getId()).orElseThrow().getState()).isEqualTo(IssueState.SUCCEEDED);
    }

    @Test
    void registrationIsIdempotentAndReturnsTheAccessVerdict() throws Exception {
        mvc.perform(post("/api/repositories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repo\":\"" + SLUG + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.owner").value("acme"))
                .andExpect(jsonPath("$.accessState").value(AccessState.VALIDATED.name()))
                .andExpect(jsonPath("$.indexState").exists());

        mvc.perform(get("/api/repositories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void learningsAlwaysAnswerWithTheThreeDocumentedLists() throws Exception {
        mvc.perform(get("/api/learnings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").isArray())
                .andExpect(jsonPath("$.recommendedActions").isArray())
                .andExpect(jsonPath("$.retired").isArray());
    }

    @Test
    void reportIsMarkdown() throws Exception {
        mvc.perform(get("/api/report"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/markdown"));
    }

    private String criteriaJson() {
        try {
            return json.writeValueAsString(new SuccessCriteria(
                            true,
                            0.86,
                            "A bounded change",
                            List.of("The defect no longer reproduces"),
                            List.of("npm test -- --run"),
                            List.of("package-lock.json"),
                            "Extend the existing suite",
                            "low",
                            List.of(),
                    "Machine-checkable"));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static GitHubDtos.Repo remoteRepo() {
        return new GitHubDtos.Repo(
                1L, "superset", SLUG, "https://github.com/" + SLUG, "master", false, false, "public", true, "Python");
    }
}
