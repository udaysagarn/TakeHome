package ai.devin.mend.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.devin.mend.github.GitHubDtos;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The dashboard links to whatever url the GitHub client reports. In the sandbox that url has to
 * resolve to something menD itself can show, otherwise the demo clicks through to a github.com 404.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("sandbox")
@TestPropertySource(
        properties = {
            "mend.engine.enabled=false",
            "mend.github.polling-enabled=false",
            "spring.datasource.url=jdbc:h2:mem:sandboxpages;DB_CLOSE_DELAY=-1",
            "spring.jpa.hibernate.ddl-auto=create-drop"
        })
class SandboxPagesControllerTest {

    private static final String REPO = "acme/superset";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SandboxHub hub;

    @Test
    void aSimulatedIssueIsServedByMendRatherThanLinkedIntoGithub() throws Exception {
        GitHubDtos.Issue issue = hub.fileIssue(REPO, SandboxScenario.CLEAN_FIX, "menD:fix");
        hub.comment(REPO, issue.number(), "Acceptance criteria: npm audit exits 0");

        assertThat(issue.htmlUrl()).isEqualTo("/sandbox/acme/superset/issues/" + issue.number());

        mvc.perform(get(issue.htmlUrl()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(issue.title())))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("npm audit exits 0")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("only inside menD's sandbox")));
    }

    @Test
    void aSimulatedPullRequestShowsItsBranchChecksAndReviews() throws Exception {
        GitHubDtos.Issue issue = hub.fileIssue(REPO, SandboxScenario.REVIEW_THEN_FIX, "menD:fix");
        GitHubDtos.PullRequest pull = hub.openPullRequest(REPO, issue.number());

        assertThat(pull.htmlUrl()).isEqualTo("/sandbox/acme/superset/pull/" + pull.number());

        mvc.perform(get(pull.htmlUrl()))
                .andExpect(status().isOk())
                .andExpect(content()
                        .string(org.hamcrest.Matchers.containsString("mend/issue-" + issue.number())))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("CHANGES_REQUESTED")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("#" + issue.number())));
    }

    @Test
    void aPullRequestNumberTheSandboxNeverMintedIsANotFoundRatherThanABlankPage() throws Exception {
        mvc.perform(get("/sandbox/acme/superset/pull/424242")).andExpect(status().isNotFound());
    }
}
