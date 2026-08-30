package ai.devin.mend.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The PostgreSQL twin of {@link PreMigrationSchemaTest}, and the only way to prove the PostgreSQL
 * migrations: on PostgreSQL {@code ddl-auto} writes each enum-backed column as a varchar pinned by a
 * CHECK constraint listing the values it knew about, which {@code alter table} carries across the
 * type change. Left in place, adding a state — {@code UNVERIFIED} and {@code CHANGES_REQUESTED} were
 * both added after the first release — would fail to insert on an adopted database.
 *
 * <p>The starting point is Hibernate's own output rather than a hand-written fixture, so the test
 * cannot drift from what a pre-Flyway deployment actually has on disk.
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgresPreMigrationSchemaTest {

    private static final Map<String, String> ENUM_COLUMNS = Map.of(
            "remediation_task", "state",
            "task_event", "to_state",
            "repository", "access_state",
            "repository_context", "kind",
            "learning", "recommended_action");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @BeforeAll
    static void migrateASchemaHibernateWrote() throws SQLException {
        exportHibernateSchema();
        rewindToThePreFlywayEntities();
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration/postgresql")
                .baselineOnMigrate(true)
                .baselineVersion("1")
                .load()
                .migrate();
    }

    /**
     * The entities have moved on since ddl-auto owned the schema, so the export is newer than any
     * database that needs adopting. Undo what the later migrations added — today only V2's lease
     * columns — to land on the schema a pre-Flyway deployment really holds. A migration that adds a
     * column without this fixup fails here loudly rather than silently testing nothing.
     */
    private static void rewindToThePreFlywayEntities() throws SQLException {
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            statement.execute("alter table repository"
                    + " drop column owner_id,"
                    + " drop column lease_acquired_at,"
                    + " drop column lease_expires_at,"
                    + " drop column lease_takeovers");
            // an operator's own rule on an enum-backed column: intent, not ddl-auto's value list
            statement.execute("alter table learning"
                    + " add constraint learning_status_not_blank check (char_length(status) > 0)");
        }
    }

    /** Recreates the pre-Flyway schema exactly as {@code ddl-auto} would have written it. */
    private static void exportHibernateSchema() {
        StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
                .applySetting(AvailableSettings.JAKARTA_JDBC_URL, POSTGRES.getJdbcUrl())
                .applySetting(AvailableSettings.JAKARTA_JDBC_USER, POSTGRES.getUsername())
                .applySetting(AvailableSettings.JAKARTA_JDBC_PASSWORD, POSTGRES.getPassword())
                .applySetting(AvailableSettings.HBM2DDL_AUTO, "create")
                // the naming Spring Boot applies, so the export matches a real deployment
                .applySetting(
                        AvailableSettings.PHYSICAL_NAMING_STRATEGY,
                        new CamelCaseToUnderscoresNamingStrategy())
                .build();
        try (SessionFactory ignored = new MetadataSources(registry)
                .addAnnotatedClass(RemediationTask.class)
                .addAnnotatedClass(TaskEvent.class)
                .addAnnotatedClass(Repository.class)
                .addAnnotatedClass(RepositoryContext.class)
                .addAnnotatedClass(Learning.class)
                .buildMetadata()
                .buildSessionFactory()) {
            // building the factory runs ddl-auto, which is the schema under test
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    @Test
    void leavesNoCheckConstraintPinningTheEnumValues() throws SQLException {
        List<String> pinned = new ArrayList<>();
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            for (Map.Entry<String, String> column : ENUM_COLUMNS.entrySet()) {
                try (ResultSet constraints = statement.executeQuery(
                        "select c.conname from pg_constraint c"
                                + " join pg_class rel on rel.oid = c.conrelid"
                                + " join pg_attribute a on a.attrelid = c.conrelid and a.attnum = c.conkey[1]"
                                + " where c.contype = 'c' and rel.relname = '" + column.getKey() + "'"
                                + " and a.attname = '" + column.getValue() + "'")) {
                    while (constraints.next()) {
                        pinned.add(column.getKey() + "." + column.getValue() + " " + constraints.getString(1));
                    }
                }
            }
        }
        assertThat(pinned)
                .describedAs("adding a state must be a code change, never a schema change")
                .isEmpty();
    }

    @Test
    void acceptsAStateTheExportedSchemaNeverKnewAbout() {
        assertThatCode(() -> {
                    try (Connection connection = connect();
                            Statement statement = connection.createStatement()) {
                        statement.executeUpdate(
                                "insert into remediation_task"
                                        + " (repo, issue_number, issue_title, state, attempts, nudges,"
                                        + " review_rounds, learnings_extracted, lease_takeovers,"
                                        + " created_at, updated_at)"
                                        + " values ('acme/superset', 1, 'title', 'A_STATE_FROM_THE_FUTURE',"
                                        + " 0, 0, 0, false, 0, now(), now())");
                    }
                })
                .doesNotThrowAnyException();
    }

    @Test
    void keepsAConstraintSomebodyWroteOnTheSameColumn() throws SQLException {
        try (Connection connection = connect();
                Statement statement = connection.createStatement();
                ResultSet constraint = statement.executeQuery(
                        "select 1 from pg_constraint where conname = 'learning_status_not_blank'")) {
            assertThat(constraint.next())
                    .describedAs("only ddl-auto's enum value list is disposable")
                    .isTrue();
        }
    }

    @Test
    void narrowsTheEnumColumnsToTheDeclaredWidths() throws SQLException {
        try (Connection connection = connect();
                Statement statement = connection.createStatement();
                ResultSet columns = statement.executeQuery(
                        "select data_type, character_maximum_length from information_schema.columns"
                                + " where table_name = 'remediation_task' and column_name = 'state'")) {
            assertThat(columns.next()).isTrue();
            assertThat(columns.getString(1)).isEqualTo("character varying");
            assertThat(columns.getInt(2)).isEqualTo(32);
        }
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
