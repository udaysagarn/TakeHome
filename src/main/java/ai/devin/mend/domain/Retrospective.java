package ai.devin.mend.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;

/** Structured output of the retrospective session: what one remediation should teach the next one. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record Retrospective(String summary, List<Lesson> lessons) {

    public Retrospective {
        lessons = lessons == null ? List.of() : List.copyOf(lessons);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Lesson(
            LearningScope scope,
            String topic,
            String lesson,
            String evidence,
            RecommendedAction recommendedAction,
            String actionDetail,
            Double confidence) {}

    public static final String JSON_SCHEMA =
            """
            {
              "type": "object",
              "additionalProperties": false,
              "required": ["summary", "lessons"],
              "properties": {
                "summary": {"type": "string", "description": "One paragraph on how this remediation went."},
                "lessons": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "additionalProperties": false,
                    "required": ["scope", "topic", "lesson", "evidence", "recommended_action", "confidence"],
                    "properties": {
                      "scope": {"type": "string", "enum": ["REPO", "GENERAL"]},
                      "topic": {"type": "string", "description": "Short handle, e.g. tests, lockfiles, migrations."},
                      "lesson": {"type": "string", "description": "An instruction a future session can act on."},
                      "evidence": {"type": "string", "description": "The review comment or fact it came from."},
                      "recommended_action": {
                        "type": "string",
                        "enum": ["PROMPT_PREAMBLE", "DEVIN_KNOWLEDGE", "REPO_INSTRUCTIONS", "MEND_BACKLOG"]
                      },
                      "action_detail": {"type": "string", "description": "Concrete next step for a human, if any."},
                      "confidence": {"type": "number", "minimum": 0, "maximum": 1}
                    }
                  }
                }
              }
            }
            """;
}
