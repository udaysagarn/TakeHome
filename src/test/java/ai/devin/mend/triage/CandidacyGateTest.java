package ai.devin.mend.triage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.devin.mend.config.MendProperties;
import ai.devin.mend.domain.SuccessCriteria;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class CandidacyGateTest {

    private final MendProperties props = new MendProperties();
    private final PreFilter preFilter = new PreFilter(props);
    private final SuccessCriteriaService criteria = new SuccessCriteriaService(new ObjectMapper(), props);

    private static final String REAL_BODY =
            """
            `npm audit` reports a high severity advisory for `nth-check` reachable from
            `superset-frontend/package-lock.json`. The transitive dependency should be bumped so the
            advisory no longer appears, without changing any application behaviour.
            """;

    @Test
    void placeholderIssuesAreRejectedWithoutSpendingAnyAcu() {
        assertThat(preFilter.reject("Test Bug", "Some bug", List.of()))
                .get()
                .asString()
                .contains("characters");
    }

    @Test
    void denylistedLabelsAreRejected() {
        assertThat(preFilter.reject("How does caching work?", REAL_BODY, List.of("question")))
                .get()
                .asString()
                .contains("denylist");
    }

    @Test
    void aSubstantiveIssuePassesThePreFilter() {
        assertThat(preFilter.reject("chore: bump nth-check", REAL_BODY, List.of("bug"))).isEmpty();
    }

    @Test
    void criteriaWithoutVerificationCommandsAreNotACandidate() {
        SuccessCriteria noProof = new SuccessCriteria(
                true, 0.95, "bump the dependency", List.of("advisory is gone"), List.of(), List.of(),
                "covered by the audit command", "low", List.of(), "");
        assertThat(criteria.gate(noProof)).anySatisfy(f -> assertThat(f).contains("verification commands"));
    }

    @Test
    void lowConfidenceIsNotACandidate() {
        SuccessCriteria unsure = new SuccessCriteria(
                true, 0.4, "maybe", List.of("advisory is gone"), List.of("npm audit"), List.of(),
                "covered by the audit command", "medium", List.of(), "");
        assertThat(criteria.gate(unsure)).anySatisfy(f -> assertThat(f).contains("Confidence"));
    }

    @Test
    void blockingUnknownsSendTheIssueBackToAHuman() {
        SuccessCriteria unknown = new SuccessCriteria(
                true, 0.9, "unclear", List.of("x"), List.of("npm test"), List.of(), "add a unit test", "low",
                List.of("which database dialects must keep working?"), "");
        assertThat(criteria.gate(unknown)).anySatisfy(f -> assertThat(f).contains("Blocking unknowns"));
    }

    @Test
    void anExplicitlyNonAutomatableVerdictIsHonoured() {
        SuccessCriteria refused = new SuccessCriteria(
                false, 0.9, "needs a product decision", List.of("x"), List.of("npm test"), List.of(),
                "add a unit test", "high", List.of(), "requires choosing a new chart default");
        assertThat(criteria.gate(refused))
                .anySatisfy(f -> assertThat(f).contains("requires choosing a new chart default"));
    }

    @Test
    void aCompleteContractPassesTheGate() {
        SuccessCriteria good = new SuccessCriteria(
                true,
                0.9,
                "bump nth-check past the advisory",
                List.of("npm audit reports no high severity advisory for nth-check"),
                List.of("npm audit --audit-level=high"),
                List.of("superset-frontend/package-lock.json"),
                "no test change: lockfile pin, proven by the existing npm audit check",
                "low",
                List.of(),
                "isolated lockfile change");
        assertThat(criteria.gate(good)).isEmpty();
        assertThat(criteria.hash(good)).hasSize(16);
    }

    @Test
    void aContractThatNeverSaysHowTheFixIsTestedIsNotACandidate() {
        SuccessCriteria untested = new SuccessCriteria(
                true,
                0.9,
                "fix the off-by-one in the pagination helper",
                List.of("the last page renders the remaining rows"),
                List.of("npm test -- pagination"),
                List.of("superset-frontend/src/components/Pagination.tsx"),
                "  ",
                "low",
                List.of(),
                "behaviour change with no agreed test");
        assertThat(criteria.gate(untested)).anySatisfy(f -> assertThat(f).contains("test plan"));
    }

    @Test
    void criteriaAuthoredByAHumanInTheIssueBodyAreUsedDirectly() {
        String body = REAL_BODY
                + """
                ```devin-criteria
                {
                  "is_candidate": true,
                  "confidence": 0.9,
                  "problem_restatement": "bump nth-check",
                  "acceptance_criteria": ["npm audit is clean at high severity"],
                  "verification_commands": ["npm audit --audit-level=high"],
                  "files_in_scope": ["superset-frontend/package-lock.json"],
                  "test_plan": "no test change: lockfile pin, proven by npm audit",
                  "risk": "low",
                  "blocking_unknowns": [],
                  "rationale": "lockfile only"
                }
                ```
                """;
        SuccessCriteria parsed = criteria.embeddedCriteria(body).orElseThrow();
        assertThat(parsed.isCandidate()).isTrue();
        assertThat(parsed.testPlan()).contains("npm audit");
        assertThat(parsed.acceptanceCriteria()).hasSize(1);
        assertThat(criteria.gate(parsed)).isEmpty();
    }

    @Test
    void anUnparseableCriteriaBlockFallsBackToScoping() {
        assertThat(criteria.embeddedCriteria(REAL_BODY + "\n```devin-criteria\nnot json\n```")).isEmpty();
    }

    @Test
    void aCorruptStoredContractFailsLoudlyRatherThanAsANullCriteria() {
        assertThatThrownBy(() -> criteria.fromJson("not json"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("deserialise");
    }
}
