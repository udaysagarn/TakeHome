package ai.devin.mend.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;

/**
 * Evidence that something other than the session which wrote the code agrees the fix works.
 *
 * <p>The tier records how strong that evidence is, so a green KPI can never be quietly backed by the
 * remediation session's own say-so: when no tier can produce a verdict the task lands in {@link
 * IssueState#UNVERIFIED} rather than {@link IssueState#SUCCEEDED}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record Verification(
        Tier tier, Verdict verdict, String summary, List<CommandResult> commands, String checkUrl) {

    /** Ordered strongest first; the verifier stops at the first tier that can answer. */
    public enum Tier {

        /** The target repository's own required checks. Authoritative, costs menD nothing. */
        REPO_CI,

        /** The menD verification contract workflow, running the agreed commands in the repo's CI. */
        CONTRACT_WORKFLOW,

        /** A separate Devin session at the PR head that only runs commands and reports exit codes. */
        VERIFIER_SESSION,

        /** Nothing independent was available. */
        NONE
    }

    public enum Verdict {
        PASSED,
        FAILED,
        PENDING,
        UNAVAILABLE
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CommandResult(String command, int exitCode, String output) {

        public boolean passed() {
            return exitCode == 0;
        }
    }

    public Verification {
        commands = commands == null ? List.of() : List.copyOf(commands);
    }

    public static Verification unavailable(String why) {
        return new Verification(Tier.NONE, Verdict.UNAVAILABLE, why, List.of(), null);
    }

    public boolean isIndependent() {
        return tier != Tier.NONE && verdict != Verdict.UNAVAILABLE;
    }

    /** Human-readable provenance, used verbatim in the GitHub comment and on the task page. */
    public String provenance() {
        return switch (tier) {
            case REPO_CI -> "the repository's own required checks";
            case CONTRACT_WORKFLOW -> "the menD verification contract workflow in the repository";
            case VERIFIER_SESSION -> "a separate Devin session that only ran the commands";
            case NONE -> "nothing independent of the session that wrote the code";
        };
    }

    /** Structured output schema for the verifier session; it reports results, it never writes code. */
    public static final String JSON_SCHEMA =
            """
            {
              "type": "object",
              "additionalProperties": false,
              "required": ["all_passed", "summary", "commands"],
              "properties": {
                "all_passed": {"type": "boolean"},
                "summary": {"type": "string", "description": "One paragraph on what was run and what it proved."},
                "commands": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "additionalProperties": false,
                    "required": ["command", "exit_code", "output"],
                    "properties": {
                      "command": {"type": "string"},
                      "exit_code": {"type": "integer"},
                      "output": {"type": "string", "description": "Last ~40 lines of output, verbatim."}
                    }
                  }
                }
              }
            }
            """;

    /** Structured output of the verifier session, before it is folded into a {@link Verification}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record VerifierReport(boolean allPassed, String summary, List<CommandResult> commands) {

        public VerifierReport {
            commands = commands == null ? List.of() : List.copyOf(commands);
        }
    }
}
