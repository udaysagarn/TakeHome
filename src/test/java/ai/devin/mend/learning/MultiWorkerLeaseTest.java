package ai.devin.mend.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import ai.devin.mend.config.MendProperties;
import ai.devin.mend.devin.DevinApiClient;
import ai.devin.mend.devin.DevinDtos;
import ai.devin.mend.domain.IndexState;
import ai.devin.mend.domain.IssueState;
import ai.devin.mend.domain.LearningRepository;
import ai.devin.mend.domain.RemediationTask;
import ai.devin.mend.domain.Repository;
import ai.devin.mend.domain.RepositoryContextRepository;
import ai.devin.mend.domain.RepositoryRegistry;
import ai.devin.mend.domain.TaskEventRepository;
import ai.devin.mend.domain.TaskRepository;
import ai.devin.mend.engine.EngineControl;
import ai.devin.mend.engine.LeaseManager;
import ai.devin.mend.engine.Notifier;
import ai.devin.mend.engine.PromptBuilder;
import ai.devin.mend.engine.TaskService;
import ai.devin.mend.github.GitHubClient;
import ai.devin.mend.registry.ContextReconciler;
import ai.devin.mend.registry.ContextService;
import ai.devin.mend.registry.RepositoryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Two menD replicas running the same scheduled loops. Both loops that create a Devin session outside
 * the reconciler — repository profiling and the retrospective — claim through {@link LeaseManager}
 * before spending anything, so the money is spent once no matter how many workers wake up together.
 *
 * <p>The scheduled intervals are pushed out of the way so the container's own loops cannot
 * contribute a session while the test races its two hand-built workers.
 */
@SpringBootTest
@TestPropertySource(
        properties = {
            "mend.engine.enabled=true",
            "mend.engine.context-interval=PT1H",
            "mend.engine.reconcile-interval=PT1H",
            "mend.engine.heartbeat-interval=PT1H",
            "mend.learning.review-poll-interval=PT1H",
            "mend.github.polling-enabled=false",
            "mend.github.repos=",
            "spring.datasource.url=jdbc:h2:mem:multiworker;DB_CLOSE_DELAY=-1"
        })
class MultiWorkerLeaseTest {

    private static final String SLUG = "acme/superset";

    @Autowired
    private RepositoryService registry;

    @Autowired
    private ContextService context;

    @Autowired
    private ReviewLoop reviewLoop;

    @Autowired
    private TaskService taskService;

    @Autowired
    private LearningService learnings;

    @Autowired
    private PromptBuilder prompts;

    @Autowired
    private Notifier notifier;

    @Autowired
    private RepositoryRegistry repositories;

    @Autowired
    private RepositoryContextRepository contexts;

    @Autowired
    private TaskRepository tasks;

    @Autowired
    private TaskEventRepository events;

    @Autowired
    private LearningRepository learningRepository;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private MendProperties props;

    @Autowired
    private EngineControl control;

    @Autowired
    private PlatformTransactionManager txManager;

    @MockBean
    private DevinApiClient devin;

    @MockBean
    private GitHubClient github;

    private final AtomicInteger sessionsCreated = new AtomicInteger();

    @BeforeEach
    void setUp() {
        contexts.deleteAll();
        repositories.deleteAll();
        events.deleteAll();
        tasks.deleteAll();
        learningRepository.deleteAll();
        sessionsCreated.set(0);
        when(devin.isConfigured()).thenReturn(true);
        when(devin.createSession(anyString(), anyString(), anyList(), any(), any(), anyString()))
                .thenAnswer(invocation -> session("devin-" + sessionsCreated.incrementAndGet()));
        when(github.isConfigured()).thenReturn(true);
        when(github.listReviews(anyString(), any(Integer.class))).thenReturn(List.of());
        when(github.listReviewComments(anyString(), any(Integer.class))).thenReturn(List.of());
    }

    @Test
    void twoWorkersProfilingTheSameRepositoryCreateExactlyOneSession() throws Exception {
        Repository repository = operationalRepository();
        List<ContextReconciler> workers = List.of(contextWorker(), contextWorker());

        race(workers.stream()
                .<Runnable>map(worker -> worker::tick)
                .toList());

        assertThat(sessionsCreated).hasValue(1);
        Repository stored = repositories.findById(repository.getId()).orElseThrow();
        assertThat(stored.getIndexState()).isEqualTo(IndexState.INDEXING);
        assertThat(stored.getContextSessionId()).isEqualTo("devin-1");
        assertThat(stored.isLeased(Instant.now())).isTrue();
    }

    @Test
    void aWorkerWithoutTheProfileLeaseNeverStartsASecondSession() {
        Repository repository = operationalRepository();
        ContextReconciler first = contextWorker();
        ContextReconciler second = contextWorker();

        inTransaction(first::tick);
        inTransaction(second::tick);

        assertThat(sessionsCreated).hasValue(1);
        assertThat(repositories.findById(repository.getId()).orElseThrow().getContextSessionId())
                .isEqualTo("devin-1");
    }

    @Test
    void anExpiredProfileLeaseIsTakenOverRatherThanStrandingTheProfile() {
        Repository repository = operationalRepository();
        inTransaction(contextWorker()::tick);

        Repository indexing = repositories.findById(repository.getId()).orElseThrow();
        indexing.setLeaseExpiresAt(Instant.now().minusSeconds(30));
        repositories.saveAndFlush(indexing);

        LeaseManager survivor = worker();
        Repository claimed = inTransactionGet(() -> survivor
                .claimRepository(repositories.findById(repository.getId()).orElseThrow())
                .orElseThrow());

        assertThat(claimed.getOwnerId()).isEqualTo(survivor.workerId());
        assertThat(claimed.getLeaseTakeovers()).isEqualTo(1);
        assertThat(claimed.getContextSessionId()).isEqualTo("devin-1");
        assertThat(sessionsCreated).hasValue(1);
    }

    @Test
    void twoWorkersRetrospectingTheSameSettledTaskCreateExactlyOneSession() throws Exception {
        RemediationTask task = settledTaskWithFeedback();
        List<ReviewLoop> workers = List.of(reviewWorker(), reviewWorker());

        race(workers.stream()
                .<Runnable>map(worker -> () -> worker.retrospectUnderLease(
                        tasks.findById(task.getId()).orElseThrow()))
                .toList());

        assertThat(sessionsCreated).hasValue(1);
        assertThat(tasks.findById(task.getId()).orElseThrow().getRetrospectiveSessionId())
                .isEqualTo("devin-1");
    }

    @Test
    void theRetrospectiveLeaseIsGivenBackSoTheNextTickCanReadTheSession() {
        RemediationTask task = settledTaskWithFeedback();

        reviewLoop.retrospectUnderLease(tasks.findById(task.getId()).orElseThrow());

        RemediationTask after = tasks.findById(task.getId()).orElseThrow();
        assertThat(after.getRetrospectiveSessionId()).isEqualTo("devin-1");
        assertThat(after.getOwnerId()).isNull();
        assertThat(after.getLeaseExpiresAt()).isNull();
    }

    // ------------------------------------------------------------- fixtures

    /** Runs every worker at once and fails the test if any of them threw. */
    private void race(List<Runnable> workers) throws Exception {
        CyclicBarrier start = new CyclicBarrier(workers.size());
        try (ExecutorService pool = Executors.newFixedThreadPool(workers.size())) {
            List<Callable<Void>> attempts = IntStream.range(0, workers.size())
                    .<Callable<Void>>mapToObj(i -> () -> {
                        start.await();
                        inTransaction(workers.get(i));
                        return null;
                    })
                    .toList();
            pool.invokeAll(attempts).forEach(MultiWorkerLeaseTest::get);
        }
    }

    /** A worker with its own identity: a second replica, as far as the lease columns can tell. */
    private LeaseManager worker() {
        return new LeaseManager(tasks, repositories, props);
    }

    private ContextReconciler contextWorker() {
        return new ContextReconciler(registry, context, worker(), control);
    }

    private ReviewLoop reviewWorker() {
        return new ReviewLoop(
                tasks, taskService, worker(), github, devin, prompts, notifier, learnings, mapper, control, props);
    }

    private Repository operationalRepository() {
        Repository repository = new Repository("acme", "superset");
        repository.markValidated("master", null);
        return repositories.saveAndFlush(repository);
    }

    private RemediationTask settledTaskWithFeedback() {
        RemediationTask task = new RemediationTask(
                SLUG, 42, "chore: bump nth-check", "https://github.com/" + SLUG + "/issues/42", "");
        task.setPrUrl("https://github.com/" + SLUG + "/pull/42");
        task.setSessionId("devin-fix");
        task.setState(IssueState.SUCCEEDED);
        task.setFeedbackJson("CHANGES_REQUESTED by @alice: add a regression test");
        return tasks.saveAndFlush(task);
    }

    private void inTransaction(Runnable work) {
        new TransactionTemplate(txManager).executeWithoutResult(status -> work.run());
    }

    private <T> T inTransactionGet(Supplier<T> work) {
        return new TransactionTemplate(txManager).execute(status -> work.get());
    }

    private static DevinDtos.SessionDetails session(String id) {
        return new DevinDtos.SessionDetails(
                id,
                "https://app.devin.ai/sessions/" + id,
                "running",
                null,
                "menD",
                List.of("mend"),
                null,
                List.of(),
                0.0,
                null,
                null);
    }

    private static <T> T get(Future<T> future) {
        try {
            return future.get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
