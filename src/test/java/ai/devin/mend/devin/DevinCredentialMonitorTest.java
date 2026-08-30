package ai.devin.mend.devin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import ai.devin.mend.domain.DevinCredentialVerdicts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * The verdict has to survive the process that learned it: the replica refused while dispatching is
 * not necessarily the one serving the page an operator opens.
 */
@SpringBootTest
@TestPropertySource(
        properties = {
            "mend.engine.enabled=false",
            "mend.github.polling-enabled=false",
            "mend.github.repos=",
            "spring.datasource.url=jdbc:h2:mem:devincred;DB_CLOSE_DELAY=-1"
        })
class DevinCredentialMonitorTest {

    @Autowired
    private DevinCredentialMonitor monitor;

    @Autowired
    private DevinCredentialVerdicts verdicts;

    @BeforeEach
    void setUp() {
        verdicts.deleteAll();
    }

    @Test
    void nothingIsClaimedUntilDevinHasActuallyAnswered() {
        assertThat(monitor.refusal()).isEmpty();
        assertThat(verdicts.findAll()).isEmpty();
    }

    @Test
    void aRefusalIsRecordedNamingTheStatusAndTheKeysToCheck() {
        monitor.refused("createSession", HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "", null, null, null));

        assertThat(monitor.refusal().orElseThrow())
                .contains("Devin refused menD's credential on createSession with 401")
                .contains("DEVIN_API_KEY");
    }

    @Test
    void aForbiddenKeyCountsButAMissingSessionOrAnOutageDoesNot() {
        monitor.refused("getSession", HttpClientErrorException.create(HttpStatus.NOT_FOUND, "", null, null, null));
        monitor.refused("getSession", HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "", null, null, null));
        monitor.refused("getSession", new ResourceAccessException("connect timed out"));
        assertThat(monitor.refusal()).isEmpty();

        monitor.refused("getSession", HttpClientErrorException.create(HttpStatus.FORBIDDEN, "", null, null, null));
        assertThat(monitor.refusal()).isPresent();
    }

    @Test
    void aCallThatSucceedsClearsTheRefusalWithoutMendProbingAnything() {
        monitor.refused("createSession", HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "", null, null, null));
        assertThat(monitor.refusal()).isPresent();

        monitor.accepted();

        assertThat(monitor.refusal()).isEmpty();
    }

    @Test
    void aDatabaseThatCannotCommitTheVerdictNeverBreaksTheCallThatProducedIt() {
        PlatformTransactionManager cannotCommit = new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
                throw new TransactionSystemException("could not commit");
            }

            @Override
            public void rollback(TransactionStatus status) {}
        };
        DevinCredentialMonitor broken = new DevinCredentialMonitor(verdicts, cannotCommit);

        assertThatCode(() -> {
                    broken.refused(
                            "createSession",
                            HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "", null, null, null));
                    broken.accepted();
                })
                .doesNotThrowAnyException();
    }

    @Test
    void theStoredReasonSaysNothingThatCouldNotBeShownOnAnUnauthenticatedPage() {
        HttpClientErrorException refusal = HttpClientErrorException.create(
                HttpStatus.UNAUTHORIZED,
                "Unauthorized",
                null,
                """
                {"detail":"invalid api key cog_live_deadbeef for org org-secret"}"""
                        .getBytes(),
                null);

        monitor.refused("createSession", refusal);

        assertThat(monitor.refusal().orElseThrow())
                .doesNotContain("cog_live_deadbeef")
                .doesNotContain("org-secret")
                .doesNotContain("Unauthorized");
    }
}
