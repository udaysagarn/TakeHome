package ai.devin.mend.triage;

import ai.devin.mend.config.MendProperties;
import ai.devin.mend.domain.SuccessCriteria;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Establishes the success criteria for an issue, and refuses the issue when it cannot.
 *
 * <p>An issue reaches remediation only if there is a machine-checkable definition of done. Criteria
 * come either from a human-authored {@code devin-criteria} block in the issue body, or from a scoping
 * Devin session whose structured output is validated by {@link #gate(SuccessCriteria)}.
 */
@Service
public class SuccessCriteriaService {

    private static final Logger log = LoggerFactory.getLogger(SuccessCriteriaService.class);

    private static final Pattern EMBEDDED_BLOCK =
            Pattern.compile("```devin-criteria\\s*(.*?)```", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    private final ObjectMapper mapper;
    private final MendProperties props;

    public SuccessCriteriaService(ObjectMapper mapper, MendProperties props) {
        this.mapper = mapper;
        this.props = props;
    }

    /** Criteria authored by a human directly in the issue body, if present and parseable. */
    public Optional<SuccessCriteria> embeddedCriteria(String issueBody) {
        if (issueBody == null) {
            return Optional.empty();
        }
        Matcher matcher = EMBEDDED_BLOCK.matcher(issueBody);
        if (!matcher.find()) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readValue(matcher.group(1).strip(), SuccessCriteria.class));
        } catch (JsonProcessingException e) {
            log.warn("issue contains a devin-criteria block that failed to parse: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public SuccessCriteria parseStructuredOutput(JsonNode structuredOutput) {
        return mapper.convertValue(structuredOutput, SuccessCriteria.class);
    }

    /**
     * The gate. Returns the list of failed conditions; an empty list means the issue is a candidate.
     */
    public List<String> gate(SuccessCriteria criteria) {
        List<String> failures = new ArrayList<>();
        MendProperties.Triage cfg = props.getTriage();

        if (criteria == null) {
            return List.of("The scoping session returned no structured output.");
        }
        if (!criteria.isCandidate()) {
            failures.add("The scoping analysis concluded this is not automatable: "
                    + blankToDash(criteria.rationale()));
        }
        if (criteria.confidence() < cfg.getMinConfidence()) {
            failures.add("Confidence %.2f is below the required %.2f."
                    .formatted(criteria.confidence(), cfg.getMinConfidence()));
        }
        if (criteria.acceptanceCriteria().isEmpty()) {
            failures.add("No acceptance criteria could be derived, so there is no definition of done to verify.");
        }
        if (criteria.verificationCommands().isEmpty()) {
            failures.add("No verification commands could be derived, so a fix could not be proven.");
        }
        if (criteria.testPlan() == null || criteria.testPlan().isBlank()) {
            failures.add("No test plan was stated, so there is no agreement on which test proves the fix.");
        }
        if (!criteria.blockingUnknowns().isEmpty()) {
            failures.add("Blocking unknowns require a human answer: " + String.join("; ", criteria.blockingUnknowns()));
        }
        if (criteria.filesInScope().size() > cfg.getMaxFilesInScope()) {
            failures.add("Scope spans %d files, above the limit of %d for a single autonomous change."
                    .formatted(criteria.filesInScope().size(), cfg.getMaxFilesInScope()));
        }
        return failures;
    }

    public String toJson(SuccessCriteria criteria) {
        try {
            return mapper.writeValueAsString(criteria);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialise criteria", e);
        }
    }

    public SuccessCriteria fromJson(String json) {
        try {
            return mapper.readValue(json, SuccessCriteria.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialise criteria", e);
        }
    }

    /** Stable fingerprint of the contract, used as the idempotency key for dispatch. */
    public String hash(SuccessCriteria criteria) {
        String canonical = String.join("|", criteria.acceptanceCriteria())
                + "||"
                + String.join("|", criteria.verificationCommands());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String blankToDash(String value) {
        return value == null || value.isBlank() ? "no rationale given" : value;
    }
}
