package ai.devin.mend.triage;

import ai.devin.mend.config.MendProperties;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Free, deterministic rejections applied before any Devin session is created. Keeps obviously
 * unsuitable issues from consuming ACUs.
 */
@Component
public class PreFilter {

    private final MendProperties props;

    public PreFilter(MendProperties props) {
        this.props = props;
    }

    /** @return the rejection reason, or empty when the issue may proceed to criteria synthesis. */
    public Optional<String> reject(String title, String body, List<String> labels) {
        MendProperties.Triage cfg = props.getTriage();

        String text = body == null ? "" : body.strip();
        if (text.length() < cfg.getMinBodyLength()) {
            return Optional.of("The issue body is %d characters; at least %d are needed to describe a verifiable problem."
                    .formatted(text.length(), cfg.getMinBodyLength()));
        }
        if (title == null || title.isBlank()) {
            return Optional.of("The issue has no title.");
        }

        List<String> denied = labels.stream()
                .map(l -> l.toLowerCase(Locale.ROOT))
                .filter(l -> cfg.getLabelDenylist().contains(l))
                .toList();
        if (!denied.isEmpty()) {
            return Optional.of("Labelled %s, which is on the denylist for autonomous remediation.".formatted(denied));
        }

        if (isPlaceholder(text)) {
            return Optional.of("The issue body looks like an unfilled template or placeholder text.");
        }
        return Optional.empty();
    }

    private static boolean isPlaceholder(String body) {
        String normalised = body.toLowerCase(Locale.ROOT);
        String stripped = normalised.replaceAll("(?s)```.*?```", "").replaceAll("[^a-z0-9]", "");
        if (stripped.length() < 40) {
            return true;
        }
        return List.of("todo", "tbd", "no response", "some bug", "placeholder").stream()
                .anyMatch(marker -> stripped.equals(marker.replace(" ", "")));
    }
}
