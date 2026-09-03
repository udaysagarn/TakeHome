package ai.devin.mend.web;

import ai.devin.mend.domain.Repository;
import ai.devin.mend.registry.RepositoryService;
import org.springframework.stereotype.Component;

/**
 * The one way an operator registers a repository, whether from the form or from {@code /api}. Both
 * entry points render the same two outcomes, so the decision of what counts as a rejection lives
 * here rather than in each controller.
 */
@Component
public class RegisterRepositoryCommand {

    private final RepositoryService registry;

    public RegisterRepositoryCommand(RepositoryService registry) {
        this.registry = registry;
    }

    public Outcome execute(String slug) {
        try {
            return new Registered(registry.register(slug));
        } catch (IllegalArgumentException e) {
            return new Rejected(e.getMessage());
        }
    }

    public sealed interface Outcome permits Registered, Rejected {}

    /** Saved and validated; {@code repository.accessState} carries the access verdict. */
    public record Registered(Repository repository) implements Outcome {}

    /** Not saved: the slug was not something menD could register. */
    public record Rejected(String reason) implements Outcome {}
}
