package ai.devin.mend.devin;

import ai.devin.mend.domain.DevinCredentialVerdict;
import ai.devin.mend.domain.DevinCredentialVerdicts;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;

/**
 * Turns the outcome of calls menD was making anyway into a verdict on its Devin credential.
 *
 * <p>{@code DevinApiClient.isConfigured()} only says the key is present. A revoked, mistyped or
 * wrong-organisation key is indistinguishable from a working one until Devin refuses it, and a
 * refusal is invisible on the dashboard: the task simply never leaves {@code DISPATCHED}. So the
 * refusal is recorded here and the credential alarm reads it.
 *
 * <p>Only 401 and 403 count. A 404 is a missing session, and a timeout or a 5xx is Devin being
 * unreachable — neither says anything about the credential, and treating them as a credential
 * failure would send an operator to rotate a key that was fine.
 *
 * <p>Both writers run through a {@link TransactionTemplate} rather than {@code @Transactional}: the
 * commit has to happen *inside* the try, because this is bookkeeping on the way out of an API call
 * and a failure to commit must never replace the caller's result — least of all turn a successful
 * {@code createSession} into a failure and have the same session created twice on the next tick.
 */
@Service
public class DevinCredentialMonitor {

    private static final Logger log = LoggerFactory.getLogger(DevinCredentialMonitor.class);

    private final DevinCredentialVerdicts verdicts;
    private final TransactionTemplate ownTransaction;

    public DevinCredentialMonitor(DevinCredentialVerdicts verdicts, PlatformTransactionManager transactions) {
        this.verdicts = verdicts;
        this.ownTransaction = new TransactionTemplate(transactions);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Records a refusal, if this is one. */
    public void refused(String operation, RuntimeException e) {
        if (!isCredentialRefusal(e)) {
            return;
        }
        int status = ((HttpClientErrorException) e).getStatusCode().value();
        try {
            ownTransaction.executeWithoutResult(tx -> {
                DevinCredentialVerdict verdict = row();
                if (!verdict.isUsable()) {
                    return;
                }
                // The reason is rendered on pages nothing authenticates, so it names the status only.
                verdict.reject("Devin refused menD's credential on " + operation + " with " + status
                        + ". Check DEVIN_API_KEY and DEVIN_ORG_ID.");
                verdicts.save(verdict);
                // Never the response body: a refusal can echo the credential it refused.
                log.warn("Devin credential refused on {} with {}", operation, status);
            });
        } catch (RuntimeException bookkeeping) {
            // Either another replica recorded the same refusal, or the write itself failed. Neither
            // may replace the API error the caller is about to handle.
            log.warn("could not record the Devin credential refusal: {}", bookkeeping.toString());
        }
    }

    /** Records that the credential worked, clearing a refusal recorded earlier. */
    public void accepted() {
        try {
            ownTransaction.executeWithoutResult(tx -> {
                DevinCredentialVerdict verdict =
                        verdicts.findById(DevinCredentialVerdict.ID).orElse(null);
                if (verdict == null || verdict.isUsable()) {
                    // The common case: no row is written while nothing has ever gone wrong.
                    return;
                }
                verdict.accept();
                verdicts.save(verdict);
                log.info("Devin credential accepted again");
            });
        } catch (RuntimeException bookkeeping) {
            // Another replica cleared it first, or the write failed; the call itself still worked.
            log.warn("could not clear the Devin credential refusal: {}", bookkeeping.toString());
        }
    }

    /** Why the Devin credential cannot be used, or empty when nothing has refused it. */
    @Transactional(readOnly = true)
    public Optional<String> refusal() {
        return verdicts
                .findById(DevinCredentialVerdict.ID)
                .filter(verdict -> !verdict.isUsable())
                .map(DevinCredentialVerdict::getReason);
    }

    private DevinCredentialVerdict row() {
        return verdicts.findById(DevinCredentialVerdict.ID).orElseGet(DevinCredentialVerdict::new);
    }

    private static boolean isCredentialRefusal(RuntimeException e) {
        return e instanceof HttpClientErrorException.Unauthorized || e instanceof HttpClientErrorException.Forbidden;
    }
}
