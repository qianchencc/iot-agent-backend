package ltd.qianc.iotagent.observation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ltd.qianc.iotagent.auth.PrincipalInfo;
import ltd.qianc.iotagent.iot.AuthorizedDevice;
import ltd.qianc.iotagent.iot.AuthorizedDeviceScope;
import ltd.qianc.iotagent.iot.IotAdapter;
import ltd.qianc.iotagent.iot.IotSourceEnum;
import ltd.qianc.iotagent.iot.RequestIdentity;
import ltd.qianc.iotagent.task.TaskFacade;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import reactor.core.publisher.Mono;

/**
 * 真实 /api/agent/regions 与 /regions/{id}/seen 的 HTTP 回归：无任何办公室任务且从未查看时
 * 不得出现异常关注（修复前基线把“未读”直接当“有异常”，本用例对其保持红）；目录里的第一条
 * 真实异常任务记录才点亮关注；同租户不同查看者的已读水印相互隔离；确认后再出现的异常记录
 * 无需刷新目录即可重新点亮；本地完整授权批次撤销的区域不可再确认。走生产
 * ObservationAdapter组合与真实MySQL任务/已读事实，目录使用明确的本地批次夹具。
 */
@SpringBootTest(properties = {"agent.storage.mysql.enabled=true", "agent.storage.redis.enabled=false",
        "iot.identity.verify=false", "agent.heartbeat-ms=0", "agent.task.scanner.poll-ms=3600000",
        "iot.gateway.base-url=https://fixture.example.invalid",
        "agent.local-data.credential-key=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        "spring.datasource.url=${AGENT_TASK_FIXTURE_MYSQL_URL}",
        "spring.datasource.username=${AGENT_TASK_FIXTURE_MYSQL_USERNAME}",
        "spring.datasource.password=${AGENT_TASK_FIXTURE_MYSQL_PASSWORD}"})
@AutoConfigureMockMvc
@Import(RegionAttentionHttpIntegrationTest.FixtureOfficeIot.class)
@EnabledIfEnvironmentVariable(named = "AGENT_TASK_FIXTURE_MYSQL_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "AGENT_TASK_FIXTURE_MYSQL_USERNAME", matches = ".+")
@EnabledIfEnvironmentVariable(named = "AGENT_TASK_FIXTURE_MYSQL_PASSWORD", matches = ".+")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RegionAttentionHttpIntegrationTest {
    private static final TypeReference<Map<String, Object>> BODY = new TypeReference<>() {
    };
    private static final String REGION_A = "office-region-alpha";
    private static final String REGION_B = "office-region-beta";
    private static final String DEVICE_A = "rgn-http-light-a";
    private static final String DEVICE_B = "rgn-http-light-b";
    private static final String TENANT = "rgn-http-" + UUID.randomUUID();
    private static final String CREATOR = "Bearer rgn-http-creator-" + UUID.randomUUID();
    private static final String VIEWER = "Bearer rgn-http-viewer-" + UUID.randomUUID();

    @Autowired
    private MockMvc http;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TaskFacade tasks;

    private final List<String> taskIds = new ArrayList<>();


    @Test
    void regionAttentionTracksDurableAbnormalTasksPerViewerReadmark() throws Exception {
        try {
            // 真实读目录按现有公开入口预热：普通 HTTP 读不得同步爬上游。
            tasks.loadScope(identity(CREATOR));
            tasks.loadScope(identity(VIEWER));

            // 显式目录请求只给名称，不带任何关注标记。
            Map<String, Object> directory = awaitRegions(CREATOR, true);
            assertThat(regionIds(directory)).containsExactlyInAnyOrder(REGION_A, REGION_B);
            assertThat(rows(directory)).allSatisfy(row -> assertThat(row).doesNotContainKey("attention"));

            // 修复前的假阳性：无任务、从未查看 => 关注必须为 false（基线为 true，保持红）。
            Map<String, Object> initial = awaitRegions(CREATOR, false);
            assertThat(attention(initial, REGION_A)).as("无任务且从未查看时不应点亮关注").isFalse();
            assertThat(attention(initial, REGION_B)).as("无任务且从未查看时不应点亮关注").isFalse();

            // 非异常状态永不点亮；同一目录里的第一条真实异常记录点亮所属区域。
            insertTask("unconfirmed-b", "UNCONFIRMED", List.of(REGION_B), List.of(DEVICE_B),
                    Instant.now().minus(2, ChronoUnit.HOURS));
            insertTask("failed-a", "FAILED", List.of(REGION_A), List.of(DEVICE_A),
                    Instant.now().minus(1, ChronoUnit.HOURS));
            Map<String, Object> abnormal = regionsNow(CREATOR);
            assertThat(attention(abnormal, REGION_A)).as("真实 FAILED 记录应点亮其区域").isTrue();
            assertThat(attention(abnormal, REGION_B)).as("UNCONFIRMED 不是异常状态").isFalse();

            // 真实已读接口清除本查看者的关注；未知区域必须如实 404。
            seen(CREATOR, REGION_A).andExpect(status().isOk())
                    .andExpect(jsonPath("$.regionId").value(REGION_A))
                    .andExpect(jsonPath("$.seenAt").exists());
            seen(CREATOR, "office-region-ghost").andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("REGION_NOT_FOUND"));
            Map<String, Object> acknowledged = regionsNow(CREATOR);
            assertThat(attention(acknowledged, REGION_A)).as("确认后关注应熄灭").isFalse();
            assertThat(attention(acknowledged, REGION_B)).isFalse();

            // 已读水印按查看者隔离：另一查看者仍能看到同一条异常记录。
            Map<String, Object> isolated = awaitRegions(VIEWER, false);
            assertThat(attention(isolated, REGION_A)).as("其他查看者的已读不屏蔽异常").isTrue();
            assertThat(attention(isolated, REGION_B)).isFalse();

            // 确认之后再次更新的异常记录重新点亮，且不依赖目录刷新。
            insertTask("failed-a-later", "FAILED", List.of(REGION_A), List.of(DEVICE_A),
                    Instant.now().plus(1, ChronoUnit.HOURS));
            Map<String, Object> later = regionsNow(CREATOR);
            assertThat(attention(later, REGION_A)).as("确认后的新异常应重新点亮").isTrue();
            assertThat(attention(later, REGION_B)).isFalse();

            // 已发布本地批次撤销的区域不可再确认；仍有权的区域照常确认。
            FixtureOfficeIot.regions = List.of(FixtureOfficeIot.INITIAL_REGIONS.getFirst());
            seen(CREATOR, REGION_B).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("REGION_NOT_FOUND"));
            seen(CREATOR, REGION_A).andExpect(status().isOk())
                    .andExpect(jsonPath("$.regionId").value(REGION_A));
        } finally {
            for (String taskId : taskIds) {
                jdbc.update("DELETE FROM task_phases WHERE task_id=?", taskId);
                jdbc.update("DELETE FROM task_facts WHERE task_id=?", taskId);
            }
            jdbc.update("DELETE FROM region_seen WHERE tenant_code=?", TENANT);
            FixtureOfficeIot.regions = FixtureOfficeIot.INITIAL_REGIONS;
        }
    }

    // ------------------------------------------------------------- HTTP 帮助

    private org.springframework.test.web.servlet.ResultActions seen(String authorization, String regionId)
            throws Exception {
        return http.perform(post("/api/agent/regions/{regionId}/seen", regionId)
                .header(HttpHeaders.AUTHORIZATION, authorization)
                .header("Tenantcode", TENANT));
    }

    /** 冷目录首次响应可能处于准备中；对初始 HTTP 准备做有界等待，之后断言一律即时读取。 */
    private Map<String, Object> awaitRegions(String authorization, boolean directory) throws Exception {
        long deadline = System.currentTimeMillis() + 20000;
        while (true) {
            Map<String, Object> parsed = performRegions(authorization, directory);
            if (parsed.get("regions") instanceof List<?> regionRows && !regionRows.isEmpty()) return parsed;
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException("区域目录未在限期内就绪: " + parsed);
            }
            Thread.sleep(200);
        }
    }

    private Map<String, Object> regionsNow(String authorization) throws Exception {
        Map<String, Object> parsed = performRegions(authorization, false);
        assertThat(parsed.get("regions")).as("区域响应应已持有目录").isNotNull();
        return parsed;
    }

    private Map<String, Object> performRegions(String authorization, boolean directory) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/agent/regions")
                .header(HttpHeaders.AUTHORIZATION, authorization)
                .header("Tenantcode", TENANT);
        if (directory) request = request.queryParam("section", "directory");
        String body = http.perform(request).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return mapper.readValue(body, BODY);
    }

    private static List<String> regionIds(Map<String, Object> body) {
        List<String> ids = new ArrayList<>();
        for (Map<String, Object> row : rows(body)) ids.add(String.valueOf(row.get("id")));
        return ids;
    }

    private static boolean attention(Map<String, Object> body, String regionId) {
        for (Map<String, Object> row : rows(body)) {
            if (!regionId.equals(row.get("id"))) continue;
            assertThat(row.get("attention")).as("区域 %s 应带布尔关注标记", regionId).isInstanceOf(Boolean.class);
            return (Boolean) row.get("attention");
        }
        throw new AssertionError("目录中缺少区域 " + regionId + ": " + body);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> body) {
        assertThat(body.get("regions")).as("区域响应应包含 regions 数组").isInstanceOf(List.class);
        return (List<Map<String, Object>>) body.get("regions");
    }

    // ------------------------------------------------------------- 事实与身份

    private static RequestIdentity identity(String authorization) {
        PrincipalInfo principal = new PrincipalInfo(authorization, TENANT);
        return new RequestIdentity(principal.authorization(), principal.tenantCode(),
                IotSourceEnum.OFFICE, principal.ownerKey());
    }

    /** 一条持久的办公室任务事实（含单阶段与目标设备），与真实投影读取的行形状一致。 */
    private void insertTask(String suffix, String status, List<String> regionIds,
            List<String> deviceIds, Instant updatedAt) throws Exception {
        String taskId = TENANT + "-" + suffix;
        Instant at = updatedAt.truncatedTo(ChronoUnit.SECONDS);
        List<Map<String, Object>> actions = deviceIds.stream()
                .map(deviceId -> Map.<String, Object>of("deviceId", deviceId, "kind", "lighting")).toList();
        String definition = mapper.writeValueAsString(Map.of("at", at.toString(), "actions", actions));
        String payload = mapper.writeValueAsString(Map.of("source", "office", "tenantCode", TENANT,
                "ownerKey", identity(CREATOR).ownerKey(), "label", "区域关注HTTP回归", "regionIds", regionIds,
                "phases", List.of(mapper.readTree(definition))));
        jdbc.update("INSERT INTO task_facts(task_id,version,status,payload_json,idempotency_key,create_time,update_time) "
                        + "VALUES (?,1,?,?,?,?,?)", taskId, status, payload, taskId,
                Timestamp.from(at), Timestamp.from(at));
        taskIds.add(taskId);
        jdbc.update("INSERT INTO task_phases(phase_id,task_id,source,version,status,due_at,payload_json,"
                        + "definition_json,create_time,update_time) VALUES (?,?,?,1,'EXECUTING',?,?,?,NOW(),NOW())",
                "rgn-phase-" + UUID.randomUUID(), taskId, "office", Timestamp.from(at), definition, definition);
    }

    /** 本地Office授权批次夹具；不监听端口、不访问客户数据来源。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class FixtureOfficeIot {
        static final List<ltd.qianc.iotagent.iot.OfficeRegion> INITIAL_REGIONS = List.of(
                new ltd.qianc.iotagent.iot.OfficeRegion(REGION_A, "Fixture Region A"),
                new ltd.qianc.iotagent.iot.OfficeRegion(REGION_B, "Fixture Region B"));
        static volatile List<ltd.qianc.iotagent.iot.OfficeRegion> regions = INITIAL_REGIONS;
        @Bean
        @Primary
        IotAdapter fixtureOfficeDeviceScope() {
            return new IotAdapter() {
                @Override public Mono<AuthorizedDeviceScope> loadAuthorizedDeviceScope(RequestIdentity identity) {
                    return Mono.just(AuthorizedDeviceScope.of(identity, identity.source(), List.of(
                            device(DEVICE_A, "走廊照明", REGION_A), device(DEVICE_B, "会议区照明", REGION_B))));
                }
                @Override public Mono<ltd.qianc.iotagent.iot.DeviceDirectoryBatch> readDirectory(RequestIdentity identity) {
                    List<ltd.qianc.iotagent.iot.OfficeRegion> current = regions;
                    return loadAuthorizedDeviceScope(identity).map(scope -> new ltd.qianc.iotagent.iot.DeviceDirectoryBatch(
                            scope.retain(device -> current.stream().anyMatch(region -> device.officeIds().contains(region.id()))), current));
                }
            };
        }

        private static AuthorizedDevice device(String id, String name, String regionId) {
            return new AuthorizedDevice(id, "lighting", name, "数智工程研究院", false,
                    null, null, null, null, List.of(), List.of(regionId), Map.of());
        }
    }

}
