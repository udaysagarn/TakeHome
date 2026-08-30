package ai.devin.mend.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.devin.mend.domain.EnginePauses;
import ai.devin.mend.engine.EngineControl;
import ai.devin.mend.engine.Orchestrator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The pause switch as an operator meets it: the JSON route scripts use, and the form in the
 * navigation that has to say what it will do and land back on the page it was clicked from.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
        properties = {
            "mend.engine.enabled=true",
            "mend.github.polling-enabled=false",
            "mend.github.repos=",
            "spring.datasource.url=jdbc:h2:mem:enginepauseweb;DB_CLOSE_DELAY=-1"
        })
class EnginePauseWebTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private EngineControl control;

    @Autowired
    private EnginePauses pauses;

    @MockBean
    private Orchestrator orchestrator;

    @BeforeEach
    void setUp() {
        pauses.deleteAll();
    }

    @Test
    void theApiReportsAndChangesTheState() throws Exception {
        mvc.perform(get("/api/engine"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paused").value(false))
                .andExpect(jsonPath("$.off").value(false));

        mvc.perform(post("/api/engine").param("paused", "true").param("reason", "no spend tonight"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paused").value(true))
                .andExpect(jsonPath("$.reason").value("no spend tonight"));

        assertThat(control.paused()).isTrue();

        mvc.perform(post("/api/engine").param("paused", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paused").value(false));

        assertThat(control.paused()).isFalse();
    }

    @Test
    void theNavigationOffersToPauseWhileRunningAndToResumeOncePaused() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(">Pause<")));

        control.pause("operator", "the demo is over");

        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Paused — resume")))
                .andExpect(content().string(containsString("No new work is being started")));
    }

    @Test
    void theFormSendsTheOperatorBackToThePageTheyPausedFrom() throws Exception {
        mvc.perform(post("/engine").param("paused", "true").param("from", "flows").param("repo", "acme/superset"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/flows?repo=acme/superset"));

        assertThat(control.paused()).isTrue();

        mvc.perform(post("/engine").param("paused", "false").param("from", "learnings"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/learnings"));

        assertThat(control.paused()).isFalse();
    }

    @Test
    void aPageKeyTheNavigationDoesNotKnowGoesToTheOverviewRatherThanOffSite() throws Exception {
        mvc.perform(post("/engine").param("paused", "true").param("from", "https://example.invalid/phish"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/"));
    }

    @Test
    void theBoardFragmentCarriesThePauseNoticeSoItAppearsWithoutAReload() throws Exception {
        control.pause("operator", "the demo is over");

        mvc.perform(get("/fragments/live"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("No new work is being started")));
    }
}
