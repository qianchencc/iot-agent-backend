package ltd.qianc.iotagent.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ltd.qianc.iotagent.auth.PrincipalInfo;
import ltd.qianc.iotagent.error.BusinessException;
import ltd.qianc.iotagent.iot.AuthorizedDevice;
import ltd.qianc.iotagent.iot.AuthorizedDeviceScope;
import ltd.qianc.iotagent.iot.IotSourceEnum;
import ltd.qianc.iotagent.iot.RequestIdentity;
import ltd.qianc.iotagent.task.TaskProjectionStore;
import ltd.qianc.iotagent.task.TaskReadScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

/**
 * Real-MySQL regression of the Runtime regionAttention rule over durable office
 * facts: strict per-region watermark comparison, the four exceptional statuses
 * only, and current device authorization through the viewer's read scope.
 */
@SpringBootTest(properties = {"agent.storage.mysql.enabled=true", "agent.storage.redis.enabled=false",
        "iot.identity.verify=false", "agent.heartbeat-ms=0", "agent.task.scanner.poll-ms=3600000",
        "AGENT_MYSQL_URL=${AGENT_TASK_FIXTURE_MYSQL_URL}",
        "AGENT_MYSQL_USERNAME=${AGENT_TASK_FIXTURE_MYSQL_USERNAME}",
        "AGENT_MYSQL_PASSWORD=${AGENT_TASK_FIXTURE_MYSQL_PASSWORD}"})
@EnabledIfEnvironmentVariable(named = "AGENT_TASK_FIXTURE_MYSQL_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "AGENT_TASK_FIXTURE_MYSQL_USERNAME", matches = ".+")
@EnabledIfEnvironmentVariable(named = "AGENT_TASK_FIXTURE_MYSQL_PASSWORD", matches = ".+")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RegionAttentionReadIntegrationTest {
    private static final Instant BASE = Instant.parse("2031-01-06T08:00:00Z");

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper mapper;

    private final List<String> tenants = new ArrayList<>();

    @AfterEach
    void cleanupOwnRows() {
        for (String tenant : tenants) {
            for (String id : jdbc.queryForList("SELECT task_id FROM task_facts "
                    + "WHERE JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.tenantCode'))=?", String.class, tenant)) {
                jdbc.update("DELETE FROM task_phases WHERE task_id=?", id);
                jdbc.update("DELETE FROM task_facts WHERE task_id=?", id);
            }
        }
    }

    @Test
    void tenantWithoutTasksNeverReportsAttention() {
        String tenant = newTenant();
        PrincipalInfo principal = principalOf(tenant);
        TaskProjectionStore reader = reader(principal,
                officeScope(principal, device("attn-light-a", "attn-region-a")));

        assertThat(reader.regionAttention(principal, Map.of())).isEmpty();
        assertThat(reader.regionAttention(principal, Map.of("attn-region-a", BASE))).isEmpty();
    }

    @Test
    void firstAbnormalTaskMarksOnlyUnacknowledgedRegions() throws Exception {
        String tenant = newTenant();
        PrincipalInfo principal = principalOf(tenant);
        TaskProjectionStore reader = reader(principal, officeScope(principal,
                device("attn-light-a", "attn-region-a"), device("attn-light-b", "attn-region-b")));
        Instant updated = BASE.plusSeconds(60);
        insertTask("attn-failed-1", tenant, "office", "FAILED",
                List.of("attn-region-a", "attn-region-b"), List.of("attn-light-a"), updated);

        assertThat(reader.regionAttention(principal, Map.of()))
                .containsExactlyInAnyOrder("attn-region-a", "attn-region-b");
        assertThat(reader.regionAttention(principal, Map.of("attn-region-a", updated)))
                .containsExactly("attn-region-b");
        assertThat(reader.regionAttention(principal,
                Map.of("attn-region-a", updated, "attn-region-b", updated.plusSeconds(1)))).isEmpty();
    }

    @Test
    void acknowledgedRegionReappearsOnlyAfterLaterUpdate() throws Exception {
        String tenant = newTenant();
        PrincipalInfo principal = principalOf(tenant);
        TaskProjectionStore reader = reader(principal,
                officeScope(principal, device("attn-light-a", "attn-region-a")));
        Instant first = BASE.plusSeconds(120);
        insertTask("attn-partial-1", tenant, "office", "PARTIAL", List.of("attn-region-a"),
                List.of("attn-light-a"), first);
        assertThat(reader.regionAttention(principal, Map.of("attn-region-a", first))).isEmpty();

        Instant later = first.plusSeconds(300);
        jdbc.update("UPDATE task_facts SET update_time=?, version=version+1 WHERE task_id=?",
                Timestamp.from(later), tenant + "-attn-partial-1");

        assertThat(reader.regionAttention(principal, Map.of("attn-region-a", first)))
                .containsExactly("attn-region-a");
    }

    @Test
    void everyExceptionalStatusCounts() throws Exception {
        String tenant = newTenant();
        PrincipalInfo principal = principalOf(tenant);
        TaskProjectionStore reader = reader(principal, officeScope(principal,
                device("attn-light-p", "attn-region-partial"), device("attn-light-f", "attn-region-failed"),
                device("attn-light-i", "attn-region-interrupted"), device("attn-light-m", "attn-region-missed")));
        insertTask("attn-exc-p", tenant, "office", "PARTIAL", List.of("attn-region-partial"),
                List.of("attn-light-p"), BASE);
        insertTask("attn-exc-f", tenant, "office", "FAILED", List.of("attn-region-failed"),
                List.of("attn-light-f"), BASE.plusSeconds(1));
        insertTask("attn-exc-i", tenant, "office", "INTERRUPTED", List.of("attn-region-interrupted"),
                List.of("attn-light-i"), BASE.plusSeconds(2));
        insertTask("attn-exc-m", tenant, "office", "MISSED", List.of("attn-region-missed"),
                List.of("attn-light-m"), BASE.plusSeconds(3));

        assertThat(reader.regionAttention(principal, Map.of())).containsExactlyInAnyOrder(
                "attn-region-partial", "attn-region-failed", "attn-region-interrupted", "attn-region-missed");
    }

    @Test
    void nonExceptionalStatusesNeverCount() throws Exception {
        String tenant = newTenant();
        PrincipalInfo principal = principalOf(tenant);
        TaskProjectionStore reader = reader(principal,
                officeScope(principal, device("attn-light-a", "attn-region-a")));
        int offset = 0;
        for (String status : List.of("SCHEDULED", "DISPATCHED", "UNCONFIRMED", "EXECUTING", "COMPLETED", "CANCELLED")) {
            insertTask("attn-normal-" + offset++, tenant, "office", status, List.of("attn-region-a"),
                    List.of("attn-light-a"), BASE.plusSeconds(offset));
        }

        assertThat(reader.regionAttention(principal, Map.of())).isEmpty();
    }

    @Test
    void foreignTenantCompanySourceForeignRegionAndUnauthorizedDeviceNeverCount() throws Exception {
        String tenant = newTenant();
        String foreign = newTenant();
        PrincipalInfo principal = principalOf(tenant);
        TaskProjectionStore reader = reader(principal,
                officeScope(principal, device("attn-light-a", "attn-region-a")));
        int offset = 0;
        insertTask("attn-iso-tenant", foreign, "office", "FAILED", List.of("attn-region-a"),
                List.of("attn-light-a"), BASE.plusSeconds(offset++));
        insertTask("attn-iso-source", tenant, "company", "FAILED", List.of("attn-region-a"),
                List.of("attn-light-a"), BASE.plusSeconds(offset++));
        insertTask("attn-iso-region", tenant, "office", "FAILED", List.of("attn-region-elsewhere"),
                List.of("attn-light-a"), BASE.plusSeconds(offset++));
        insertTask("attn-iso-device", tenant, "office", "FAILED", List.of("attn-region-a"),
                List.of("attn-light-stranger"), BASE.plusSeconds(offset++));

        assertThat(reader.regionAttention(principal, Map.of())).isEmpty();
    }

    @Test
    void partiallyVisibleTaskStillCountsOnAnyAuthorizedDevice() throws Exception {
        String tenant = newTenant();
        PrincipalInfo principal = principalOf(tenant);
        TaskProjectionStore reader = reader(principal,
                officeScope(principal, device("attn-light-a", "attn-region-a")));
        insertTask("attn-partial-visible", tenant, "office", "FAILED", List.of("attn-region-a"),
                List.of("attn-light-stranger", "attn-light-a"), BASE.plusSeconds(30));

        assertThat(reader.regionAttention(principal, Map.of("attn-region-a", BASE)))
                .containsExactly("attn-region-a");
    }

    @Test
    void unavailableScopeFailsClosed() {
        String tenant = newTenant();
        PrincipalInfo principal = principalOf(tenant);
        TaskProjectionStore reader = TaskFactStoresFactory.taskProjections(jdbc, mapper,
                (viewer, source) -> {
                    throw new BusinessException("AUTHORIZED_SCOPE_UNAVAILABLE", "当前设备范围暂不可用", 503);
                });

        assertThatThrownBy(() -> reader.regionAttention(principal, Map.of()))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).code())
                        .isEqualTo("AUTHORIZED_SCOPE_UNAVAILABLE"));
    }

    // ------------------------------------------------------------- fixtures

    private String newTenant() {
        String tenant = "attn-" + UUID.randomUUID();
        tenants.add(tenant);
        return tenant;
    }

    private PrincipalInfo principalOf(String tenant) {
        return new PrincipalInfo("Bearer attn-fixture", tenant, "member", "gateway");
    }

    private TaskProjectionStore reader(PrincipalInfo principal, TaskReadScope scope) {
        return TaskFactStoresFactory.taskProjections(jdbc, mapper, (viewer, source) -> scope);
    }

    private TaskReadScope officeScope(PrincipalInfo principal, AuthorizedDevice... devices) {
        RequestIdentity identity = new RequestIdentity(principal.authorization(), principal.tenantCode(),
                IotSourceEnum.OFFICE, principal.ownerKey());
        return TaskReadScope.of(AuthorizedDeviceScope.of(identity, IotSourceEnum.OFFICE, List.of(devices)));
    }

    private static AuthorizedDevice device(String id, String region) {
        return new AuthorizedDevice(id, "lighting", "灯-" + id, "26楼", false,
                null, null, null, null, List.of(), List.of(region), Map.of());
    }

    /** One durable office task fact with a single phase over the given devices. */
    private void insertTask(String taskId, String tenant, String source, String status,
                            List<String> regionIds, List<String> deviceIds, Instant updatedAt) throws Exception {
        taskId = tenant + "-" + taskId;
        Instant at = updatedAt.truncatedTo(ChronoUnit.SECONDS);
        List<Map<String, Object>> actions = new ArrayList<>();
        for (String deviceId : deviceIds) {
            actions.add(Map.of("deviceId", deviceId, "kind", "lighting"));
        }
        String definition = mapper.writeValueAsString(Map.of("at", at.toString(), "actions", actions));
        String payload = mapper.writeValueAsString(Map.of("source", source, "tenantCode", tenant,
                "ownerKey", "fixture-owner", "label", "区域关注回归", "regionIds", regionIds,
                "phases", List.of(mapper.readTree(definition))));
        jdbc.update("INSERT INTO task_facts(task_id,version,status,payload_json,idempotency_key,create_time,update_time) "
                        + "VALUES (?,1,?,?,?,?,?)", taskId, status, payload, taskId,
                Timestamp.from(at), Timestamp.from(at));
        jdbc.update("INSERT INTO task_phases(phase_id,task_id,source,version,status,due_at,payload_json,definition_json,create_time,update_time) "
                        + "VALUES (?,?,?,1,'EXECUTING',?,?,?,NOW(),NOW())",
                "attn-phase-" + UUID.randomUUID(), taskId, source, Timestamp.from(at), definition, definition);
    }
}
