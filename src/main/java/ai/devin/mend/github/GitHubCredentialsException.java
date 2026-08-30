package ai.devin.mend.github;

/**
 * The configured GitHub App credentials cannot be used. Extends {@link IllegalArgumentException} so
 * registration reports the reason on the form and in the API instead of a stack trace, and carries a
 * message an operator can act on without ever repeating key material.
 */
public class GitHubCredentialsException extends IllegalArgumentException {

    public GitHubCredentialsException(String message) {
        super(message);
    }
}
