package com.ehe.iotagent.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.ehe.iotagent.auth.PrincipalInfo;
import com.ehe.iotagent.iot.AuthorizedDevice;
import com.ehe.iotagent.iot.AuthorizedDeviceScope;
import com.ehe.iotagent.iot.IotSourceEnum;
import com.ehe.iotagent.iot.RequestIdentity;
import com.ehe.iotagent.task.TaskProjectionStore;
import com.ehe.iotagent.task.TaskReadScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest(properties = {"agent.storage.mysql.enabled=true", "agent.storage.redis.enabled=false",
        "iot.identity.verify=false", "agent.heartbeat-ms=0", "agent.task.scanner.poll-ms=3600000",
        "spring.datasource.url=${AGENT_TASK_FIXTURE_MYSQL_URL}",
        "spring.datasource.username=${AGENT_TASK_FIXTURE_MYSQL_USERNAME}",
        "spring.datasource.password=${AGENT_TASK_FIXTURE_MYSQL_PASSWORD}"})
@EnabledIfEnvironmentVariable(named = "AGENT_TASK_FIXTURE_MYSQL_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "AGENT_TASK_FIXTURE_MYSQL_USERNAME", matches = ".+")
@EnabledIfEnvironmentVariable(named = "AGENT_TASK_FIXTURE_MYSQL_PASSWORD", matches = ".+")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TaskProjectionReadIntegrationTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;

    @Test
    void absentTimeBoundsDoNotBecomeAnOutOfRangeMysqlDatetime() throws Exception {
        String id = "projection-" + UUID.randomUUID();
        PrincipalInfo principal = new PrincipalInfo("Bearer projection-fixture", id, "member", "gateway");
        RequestIdentity identity = new RequestIdentity(principal.authorization(), id, IotSourceEnum.COMPANY,
                principal.ownerKey());
        TaskReadScope scope = TaskReadScope.of(AuthorizedDeviceScope.of(identity, IotSourceEnum.COMPANY,
                List.of(new AuthorizedDevice("light-1", "lighting", "灯", "26楼", false))));
        TaskProjectionStore reader = TaskFactStoresFactory.taskProjections(jdbc, mapper, (viewer, source) -> scope);
        Instant at = Instant.now().plusSeconds(3600);
        String definition = mapper.writeValueAsString(Map.of("at", at.toString(), "actions",
                List.of(Map.of("deviceId", "light-1", "kind", "lighting"))));
        String payload = mapper.writeValueAsString(Map.of("source", "company", "tenantCode", id,
                "ownerKey", principal.ownerKey(), "label", "无时间边界的任务摘要", "phases",
                List.of(mapper.readTree(definition))));
        try {
            jdbc.update("INSERT INTO task_facts(task_id,version,status,payload_json,idempotency_key,create_time,update_time) "
                            + "VALUES (?,1,'SCHEDULED',?,?,NOW(),NOW())", id, payload, id);
            jdbc.update("INSERT INTO task_phases(phase_id,task_id,source,version,status,due_at,payload_json,definition_json,create_time,update_time) "
                            + "VALUES (?,?,'company',1,'SCHEDULED',?,?,?,NOW(),NOW())",
                    id + "-phase", id, Timestamp.from(at), definition, definition);

            assertThat(reader.listByIdentity(identity, null, null)).singleElement()
                    .satisfies(row -> assertThat(row).containsEntry("id", id)
                            .containsEntry("label", "无时间边界的任务摘要"));
            assertThat(reader.listByIdentity(identity, at.plusSeconds(1), null)).isEmpty();
        } finally {
            jdbc.update("DELETE FROM task_phases WHERE task_id=?", id);
            jdbc.update("DELETE FROM task_facts WHERE task_id=?", id);
        }
    }
}
