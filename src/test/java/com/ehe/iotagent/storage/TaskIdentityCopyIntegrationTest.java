package com.ehe.iotagent.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import com.ehe.iotagent.schema.SchemaMigrations;
import com.ehe.iotagent.task.TaskExecutionIdentityStore;
import com.ehe.iotagent.task.TaskExecutionIdentityStoreFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** MySQL INSERT SELECT ambiguity is not observable with the H2 identity fixtures. */
@EnabledIfEnvironmentVariable(named = "AGENT_TASK_FIXTURE_MYSQL_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "AGENT_TASK_FIXTURE_MYSQL_USERNAME", matches = ".+")
@EnabledIfEnvironmentVariable(named = "AGENT_TASK_FIXTURE_MYSQL_PASSWORD", matches = ".+")
class TaskIdentityCopyIntegrationTest {
    @Test void planIdentityCopiesIdempotentlyAndRemainsDecryptable() {
        var source = new DriverManagerDataSource(System.getenv("AGENT_TASK_FIXTURE_MYSQL_URL"),
                System.getenv("AGENT_TASK_FIXTURE_MYSQL_USERNAME"), System.getenv("AGENT_TASK_FIXTURE_MYSQL_PASSWORD"));
        var jdbc = new JdbcTemplate(source);
        var migrations = new SchemaMigrations(source);
        migrations.ensureSharedFacts();
        migrations.ensureTaskLifecycle();
        var identities = TaskExecutionIdentityStoreFactory.jdbc(jdbc, new ObjectMapper(), "ab".repeat(32));
        String planId = "fixture-plan-" + UUID.randomUUID();
        String taskId = "fixture-task-" + UUID.randomUUID();
        var expected = new TaskExecutionIdentityStore.TaskExecutionIdentity("FIXTURE", "fixture-owner", "Bearer fixture-identity");
        try {
            identities.saveForPlan(planId, expected);
            identities.copyPlanIdentityToTask(planId, taskId);
            identities.copyPlanIdentityToTask(planId, taskId);
            assertThat(identities.loadForTask(taskId)).contains(expected);
        } finally {
            identities.deleteForTask(taskId);
            identities.deleteForTask(planId);
        }
    }
}
