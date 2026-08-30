package ai.devin.mend.web;

import java.util.List;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Puts the credential alarm on every rendered page, so an operator meets it wherever they land
 * rather than only on the page that happens to list repositories.
 */
@ControllerAdvice(assignableTypes = DashboardController.class)
public class CredentialAdvice {

    private final CredentialHealth credentials;

    public CredentialAdvice(CredentialHealth credentials) {
        this.credentials = credentials;
    }

    @ModelAttribute("credentialProblems")
    public List<CredentialHealth.Problem> credentialProblems() {
        return credentials.problems();
    }
}
