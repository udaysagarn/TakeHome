package ai.devin.mend.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Incremental refresh is only worth having if a commit invalidates the slices it actually touches
 * and nothing else — otherwise every push costs a full profile rebuild.
 */
class ContextKindTest {

    @Test
    void aLockfileChangeAgesTheStackSliceOnly() {
        assertThat(ContextKind.STACK.invalidatedBy("superset-frontend/package.json")).isTrue();
        assertThat(ContextKind.CI.invalidatedBy("superset-frontend/package.json")).isFalse();
        assertThat(ContextKind.TESTS.invalidatedBy("superset-frontend/package.json")).isFalse();
    }

    @Test
    void aWorkflowChangeAgesTheCiSlice() {
        assertThat(ContextKind.CI.invalidatedBy(".github/workflows/build.yml")).isTrue();
        assertThat(ContextKind.STACK.invalidatedBy(".github/workflows/build.yml")).isFalse();
    }

    @Test
    void agentInstructionFilesAgeTheRulesSlice() {
        assertThat(ContextKind.AGENT_RULES.invalidatedBy("CLAUDE.md")).isTrue();
        assertThat(ContextKind.AGENT_RULES.invalidatedBy("AGENTS.md")).isTrue();
        assertThat(ContextKind.AGENT_RULES.invalidatedBy("codex.md")).isTrue();
        assertThat(ContextKind.AGENT_RULES.invalidatedBy(".cursor/rules/style.mdc")).isTrue();
        assertThat(ContextKind.AGENT_RULES.invalidatedBy(".agents/skills/deploy/SKILL.md")).isTrue();
    }

    @Test
    void ordinarySourceChangesInvalidateNothing() {
        for (ContextKind kind : ContextKind.values()) {
            assertThat(kind.invalidatedBy("superset/models/core.py"))
                    .as("%s should ignore ordinary source files", kind)
                    .isFalse();
            assertThat(kind.invalidatedBy("superset-frontend/src/components/Chart.tsx"))
                    .as("%s should ignore ordinary source files", kind)
                    .isFalse();
        }
    }

    @Test
    void testDirectoriesAgeTheTestSliceWhereverTheyLive() {
        assertThat(ContextKind.TESTS.invalidatedBy("tests/unit_tests/conftest.py")).isTrue();
        assertThat(ContextKind.TESTS.invalidatedBy("superset-frontend/spec/helpers/setup.ts"))
                .isTrue();
    }

    @Test
    void blankPathsAreIgnored() {
        assertThat(ContextKind.STACK.invalidatedBy(null)).isFalse();
        assertThat(ContextKind.STACK.invalidatedBy("  ")).isFalse();
    }
}
