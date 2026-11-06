package ltd.qianc.iotagent.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named = "AGENT_SCHEMA_TEST_MYSQL_URL", matches = ".+")
class MySqlLegacyPhaseUpgradeTest {
    @Test
    void sharedStartupRepairsLegacyPhaseColumnsBeforeMysqlCommentAlterations() throws Exception {
        String url = System.getenv("AGENT_SCHEMA_TEST_MYSQL_URL");
        String username = System.getenv("AGENT_MYSQL_USERNAME");
        String password = System.getenv("AGENT_MYSQL_PASSWORD");
        String schema = "phase_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        try (var admin = DriverManager.getConnection(url, username, password);
             var statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + schema);
            try {
                String isolatedUrl = url.replaceFirst("(jdbc:mysql://[^/]+/)[^?]*", "$1" + schema);
                var source = new DriverManagerDataSource(isolatedUrl, username, password);
                var jdbc = new JdbcTemplate(source);
                jdbc.execute("CREATE TABLE task_phases (id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY, "
                        + "phase_id VARCHAR(64) NOT NULL, task_id VARCHAR(64) NOT NULL, source VARCHAR(16) NOT NULL, "
                        + "version BIGINT UNSIGNED NOT NULL, status VARCHAR(32) NOT NULL, due_at DATETIME NOT NULL, "
                        + "payload_json JSON NOT NULL, claimed_at DATETIME NULL, ended_at DATETIME NULL, "
                        + "create_time DATETIME NOT NULL, update_time DATETIME NOT NULL, UNIQUE KEY uk_task_phase_id (phase_id))");
                jdbc.update("INSERT INTO task_phases(phase_id,task_id,source,version,status,due_at,payload_json,"
                        + "create_time,update_time) VALUES('phase-1','task-1','office',1,'COMPLETED',CURRENT_TIMESTAMP,"
                        + "'{\"source\":\"office\"}',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
                var migrations = new SchemaMigrations(source);
                migrations.ensureSharedFacts();
                migrations.ensureTaskLifecycle();
                migrations.ensureSharedFacts();
                migrations.ensureTaskLifecycle();
                assertThat(new ObjectMapper().readTree(jdbc.queryForObject(
                        "SELECT definition_json FROM task_phases WHERE phase_id='phase-1'", String.class)))
                        .isEqualTo(new ObjectMapper().readTree("{\"source\":\"office\"}"));
                assertThat(jdbc.queryForMap("SELECT status,result_json,failure_reason FROM task_phases WHERE phase_id='phase-1'"))
                        .containsEntry("status", "COMPLETED").containsEntry("result_json", null)
                        .containsEntry("failure_reason", null);
            } finally {
                statement.execute("DROP DATABASE " + schema);
            }
        }
    }
}
