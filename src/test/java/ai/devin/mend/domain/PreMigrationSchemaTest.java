package ai.devin.mend.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.StreamUtils;

/**
 * A database created before Flyway owned the schema holds the tables — with Hibernate's native ENUM
 * columns — but no history table. Every deployment carrying a data volume from that era is upgraded
 * in place, so menD has to adopt and repair such a database rather than refuse to start.
 */
@SpringBootTest(
        properties = {
            "mend.engine.enabled=false",
            "mend.github.polling-enabled=false",
            "mend.github.repos="
        })
class PreMigrationSchemaTest {

    @TempDir
    static Path database;

    @Autowired
    private Flyway flyway;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private TaskRepository tasks;

    @DynamicPropertySource
    static void preMigrationDatabase(DynamicPropertyRegistry registry) {
        String url = "jdbc:h2:file:" + database.resolve("mend") + ";AUTO_SERVER=TRUE";
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                Statement statement = connection.createStatement()) {
            statement.execute(preMigrationSchema());
        } catch (SQLException e) {
            throw new IllegalStateException("could not seed the pre-migration schema", e);
        }
        registry.add("spring.datasource.url", () -> url);
    }

    private static String preMigrationSchema() {
        try {
            return StreamUtils.copyToString(
                    new ClassPathResource("db/pre-migration-h2-schema.sql").getInputStream(),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void adoptsASchemaThatPredatesTheMigrations() throws SQLException {
        MigrationInfo[] applied = flyway.info().applied();
        assertThat(applied[0].getType().name()).isEqualTo("BASELINE");
        assertThat(applied[0].getVersion().getVersion()).isEqualTo("1");
        assertThat(flyway.info().pending()).isEmpty();

        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet columns = statement.executeQuery(
                        "select data_type from information_schema.columns"
                                + " where table_schema = 'PUBLIC' and data_type = 'ENUM'")) {
            assertThat(columns.next())
                    .describedAs("every enum-backed column is varchar after the upgrade")
                    .isFalse();
        }

        RemediationTask task = new RemediationTask(
                "acme/superset", 1, "title", "https://example.invalid/1", "bug");
        task.setState(IssueState.DISCOVERED);
        assertThat(tasks.saveAndFlush(task).getId()).isNotNull();
    }
}
