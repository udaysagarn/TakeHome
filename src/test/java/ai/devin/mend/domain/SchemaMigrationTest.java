package ai.devin.mend.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * The migrated schema has to hold every state the code can produce. Hibernate runs in validate
 * mode, so this also proves the baseline migration still matches the entities.
 */
@SpringBootTest
@TestPropertySource(
        properties = {
            "mend.engine.enabled=false",
            "mend.github.polling-enabled=false",
            "mend.github.repos=",
            "spring.datasource.url=jdbc:h2:mem:schemamigration;DB_CLOSE_DELAY=-1"
        })
class SchemaMigrationTest {

    @Autowired
    private TaskRepository tasks;

    @Autowired
    private TaskEventRepository events;

    @Test
    void everyStateRoundTrips() {
        int issue = 1;
        for (IssueState state : IssueState.values()) {
            RemediationTask task = new RemediationTask(
                    "acme/superset", issue++, "title", "https://example.invalid/1", "bug");
            task.setState(state);
            tasks.saveAndFlush(task);

            events.saveAndFlush(new TaskEvent(
                    task.getId(), task.key(), IssueState.DISCOVERED, state, "migration test", "test"));
        }

        assertThat(tasks.count()).isEqualTo(IssueState.values().length);
        assertThat(events.count()).isEqualTo(IssueState.values().length);
    }
}
