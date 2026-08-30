package ai.devin.mend.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;

/**
 * The verifiable contract an issue must satisfy before Devin is allowed to attempt it, and which the
 * remediation session is then held to.
 *
 * <p>Produced either by a {@code devin-criteria} block authored by a human in the issue body, or
 * synthesised by a scoping Devin session via structured output.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SuccessCriteria(
        boolean isCandidate,
        double confidence,
        String problemRestatement,
        List<String> acceptanceCriteria,
        List<String> verificationCommands,
        List<String> filesInScope,
        String testPlan,
        String risk,
        List<String> blockingUnknowns,
        String rationale) {

    public SuccessCriteria {
        acceptanceCriteria = acceptanceCriteria == null ? List.of() : List.copyOf(acceptanceCriteria);
        verificationCommands = verificationCommands == null ? List.of() : List.copyOf(verificationCommands);
        filesInScope = filesInScope == null ? List.of() : List.copyOf(filesInScope);
        blockingUnknowns = blockingUnknowns == null ? List.of() : List.copyOf(blockingUnknowns);
    }

    /** JSON Schema (Draft 7) handed to the Devin API as {@code structured_output_schema}. */
    public static final String JSON_SCHEMA =
            """
            {
              "type": "object",
              "additionalProperties": false,
              "required": ["is_candidate", "confidence", "problem_restatement", "acceptance_criteria",
                           "verification_commands", "files_in_scope", "test_plan", "risk",
                           "blocking_unknowns", "rationale"],
              "properties": {
                "is_candidate": {
                  "type": "boolean",
                  "description": "True only if the issue can be remediated and objectively verified without further human input."
                },
                "confidence": {"type": "number", "minimum": 0, "maximum": 1},
                "problem_restatement": {"type": "string"},
                "acceptance_criteria": {
                  "type": "array",
                  "items": {"type": "string"},
                  "description": "Objectively checkable statements that must all hold once the issue is fixed."
                },
                "verification_commands": {
                  "type": "array",
                  "items": {"type": "string"},
                  "description": "Commands runnable in the repository that prove the acceptance criteria."
                },
                "files_in_scope": {"type": "array", "items": {"type": "string"}},
                "test_plan": {
                  "type": "string",
                  "description": "Which automated test proves the fix: the test file and case to add or change, following the repository's existing test conventions. If no test change is warranted (e.g. a dependency pin proven by an audit command), say so and name the existing check that covers it."
                },
                "risk": {"type": "string", "enum": ["low", "medium", "high"]},
                "blocking_unknowns": {
                  "type": "array",
                  "items": {"type": "string"},
                  "description": "Questions only a human can answer. Non-empty means the issue is not a candidate."
                },
                "rationale": {"type": "string"}
              }
            }
            """;
}
