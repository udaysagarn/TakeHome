package ai.devin.mend.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;

/** Structured output a remediation session must return, asserting itself against the criteria. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record RemediationOutcome(
        boolean remediated,
        String prUrl,
        String summary,
        List<String> filesChanged,
        List<CriterionResult> criteriaResults,
        List<String> testsChanged,
        String testEvidence,
        List<String> commandsRun,
        double confidence,
        String blockedReason) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CriterionResult(String criterion, boolean satisfied, String evidence) {}

    public RemediationOutcome {
        filesChanged = filesChanged == null ? List.of() : List.copyOf(filesChanged);
        criteriaResults = criteriaResults == null ? List.of() : List.copyOf(criteriaResults);
        testsChanged = testsChanged == null ? List.of() : List.copyOf(testsChanged);
        commandsRun = commandsRun == null ? List.of() : List.copyOf(commandsRun);
    }

    public boolean allCriteriaSatisfied() {
        return !criteriaResults.isEmpty() && criteriaResults.stream().allMatch(CriterionResult::satisfied);
    }

    public static final String JSON_SCHEMA =
            """
            {
              "type": "object",
              "additionalProperties": false,
              "required": ["remediated", "pr_url", "summary", "files_changed", "criteria_results",
                           "tests_changed", "test_evidence", "commands_run", "confidence", "blocked_reason"],
              "properties": {
                "remediated": {"type": "boolean"},
                "pr_url": {"type": "string", "description": "URL of the opened pull request, or empty string."},
                "summary": {"type": "string"},
                "files_changed": {"type": "array", "items": {"type": "string"}},
                "criteria_results": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "additionalProperties": false,
                    "required": ["criterion", "satisfied", "evidence"],
                    "properties": {
                      "criterion": {"type": "string"},
                      "satisfied": {"type": "boolean"},
                      "evidence": {"type": "string", "description": "Command output or diff excerpt proving the result."}
                    }
                  }
                },
                "tests_changed": {
                  "type": "array",
                  "items": {"type": "string"},
                  "description": "Test files added or edited by this change. Empty only when the test plan said no test change was warranted."
                },
                "test_evidence": {
                  "type": "string",
                  "description": "How the tests prove the fix: the test run output, and for a new test, confirmation that it fails without the fix. If no test was added, the justification."
                },
                "commands_run": {"type": "array", "items": {"type": "string"}},
                "confidence": {"type": "number", "minimum": 0, "maximum": 1},
                "blocked_reason": {"type": "string", "description": "Empty unless the session could not finish."}
              }
            }
            """;
}
