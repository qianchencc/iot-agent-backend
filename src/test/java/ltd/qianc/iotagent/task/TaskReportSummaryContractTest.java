package ltd.qianc.iotagent.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import ltd.qianc.iotagent.iot.AuthorizedDevice;
import ltd.qianc.iotagent.iot.AuthorizedDeviceScope;
import ltd.qianc.iotagent.iot.IotSourceEnum;
import ltd.qianc.iotagent.iot.RequestIdentity;
import org.junit.jupiter.api.Test;

/**
 * Consumer-visible report summary contract: a legal zero-alert window keeps its
 * null latest/windowLatest facts through detail projections and summaries, and
 * meter-report facts with null fields feed the energy impact without losing
 * the nulls.
 */
class TaskReportSummaryContractTest {
    private static final Instant PHASE_AT = Instant.parse("2026-11-04T07:30:00Z");
    private static final Instant PHASE_END = Instant.parse("2026-11-04T07:31:05Z");

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void zeroAlertObservationsKeepNullLatestFieldsAndStillSummarize() throws Exception {
        var definition = mapper.readTree("""
                {"read":{"ac":{"mode":"adjust_to_26","action":{"setpoint":26}},
                 "devices":[{"id":"ac-1","kind":"ac","name":"会议室空调","location":"26楼"}]}}""");
        var result = mapper.readTree("""
                {"observations":[
                  {"type":"alerts","windowDays":7,
                   "window":{"from":"2026-10-28T16:00:00Z","to":"2026-11-04T16:00:00Z","timezone":"Asia/Shanghai"},
                   "total":0,"uniqueDevices":0,"byType":{"smoke":0},"byDevice":{},
                   "coverageComplete":true,"summaryStatus":"complete","failures":[],
                   "latest":null,"windowLatest":null,"records":[]},
                  {"type":"ac_inspection","mode":"adjust_to_26","criteria":{"setpoint":{"value":26}},
                   "observedAt":"2026-11-04T07:40:00Z",
                   "devices":[{"deviceId":"ac-1","outcome":"observed","disposition":"not_needed",
                     "setpoint":26,"observedAt":"2026-11-04T07:40:00Z"}]}],
                 "devices":{"ac-1":{"outcome":"observed"}}}""");
        var row = new TaskDetailProjector.PhaseRow("inspect", PHASE_AT, "COMPLETED",
                definition, result, PHASE_AT, PHASE_END, null);
        TaskDetailProjector projector = new TaskDetailProjector(mapper, id -> List.of(row));
        TaskReadScope scope = scopeOf("ac-1", "ac", "会议室空调");
        var task = new TaskDetailProjector.TaskSource("task", 4, "COMPLETED",
                mapper.readTree("{\"source\":\"company\",\"ownerKey\":\"owner\"}"), PHASE_AT, PHASE_END);

        Map<?, ?> report = (Map<?, ?>) projector.detail(task, scope, "owner", Map.of()).get("report");

        Map<?, ?> alerts = observationOf(report, "alerts");
        assertThat(entries(alerts))
                .containsEntry("total", 0)
                .containsEntry("latest", null)
                .containsEntry("windowLatest", null)
                .containsEntry("coverageComplete", true);
        Map<?, ?> summary = (Map<?, ?>) report.get("summary");
        assertThat(summary).isNotNull();
        assertThat(summary.get("checkpoints")).isEqualTo(1L);
        assertThat(entries(summary.get("devices")))
                .containsEntry("total", 1L)
                .containsEntry("satisfied", 1L)
                .containsEntry("adjusted", 0L)
                .containsEntry("failed", 0L)
                .containsEntry("unverified", 0L);
        assertThat(summary.get("energyImpact")).isNull();
    }

    @Test
    void meterReportNullSpanEndIsCopiedThroughIntoTheStatisticsEstimate() throws Exception {
        var meterDefinition = mapper.readTree("""
                {"read":{"devices":[{"id":"meter-1","kind":"energy","name":"总电表","location":"配电室"}]}}""");
        var meterResult = mapper.readTree("""
                {"observations":[
                  {"type":"meter_report","observedAt":"2026-11-05T08:00:00Z",
                   "report":{"kind":"statistics",
                     "params":{"kind":"statistics","scopeLevel":"selection","meterIds":"meter-1",
                       "from":"2026-11-04T00:00:00Z","to":null,
                       "span":{"from":"2026-11-04T00:00:00Z","to":null}},
                     "coverage":{"dataMeters":1,"truncated":false},
                     "total":{"value":12.5,"unit":"度"}}}],
                 "devices":{"meter-1":{"outcome":"observed"}}}""");
        var inspectDefinition = mapper.readTree("""
                {"read":{"ac":{"mode":"adjust_to_26","action":{"setpoint":26}},
                 "devices":[{"id":"ac-1","kind":"ac","name":"会议室空调","location":"26楼"}]}}""");
        var inspectResult = mapper.readTree("""
                {"observations":[
                  {"type":"ac_inspection","mode":"adjust_to_26","criteria":{"setpoint":{"value":26}},
                   "observedAt":"2026-11-04T07:40:00Z",
                   "devices":[{"deviceId":"ac-1","outcome":"adjusted","disposition":"adjusted",
                     "observedAt":"2026-11-04T07:40:00Z"}]}],
                 "devices":{"ac-1":{"outcome":"adjusted"}}}""");
        var rows = List.of(
                new TaskDetailProjector.PhaseRow("meter", PHASE_AT, "COMPLETED",
                        meterDefinition, meterResult, PHASE_AT, PHASE_END, null),
                new TaskDetailProjector.PhaseRow("inspect", PHASE_AT.plusSeconds(600), "COMPLETED",
                        inspectDefinition, inspectResult, PHASE_AT.plusSeconds(600), PHASE_END, null));
        TaskDetailProjector projector = new TaskDetailProjector(mapper, id -> rows);
        TaskReadScope scope = TaskReadScope.of(AuthorizedDeviceScope.of(
                new RequestIdentity("Bearer isolated", "TENANT", IotSourceEnum.COMPANY), IotSourceEnum.COMPANY,
                List.of(new AuthorizedDevice("meter-1", "energy", "总电表", "配电室", true),
                        new AuthorizedDevice("ac-1", "ac", "会议室空调", "26楼", true))));
        var task = new TaskDetailProjector.TaskSource("task", 5, "COMPLETED",
                mapper.readTree("{\"source\":\"company\",\"ownerKey\":\"owner\"}"), PHASE_AT, PHASE_END);

        Map<?, ?> report = (Map<?, ?>) projector.detail(task, scope, "owner", Map.of()).get("report");

        assertThat(observationOf(report, "meter_report")).isNotNull();
        Map<?, ?> summary = (Map<?, ?>) report.get("summary");
        assertThat(summary).isNotNull();
        assertThat(summary.get("checkpoints")).isEqualTo(1L);
        assertThat(entries(summary.get("devices")))
                .containsEntry("total", 1L)
                .containsEntry("adjusted", 1L);
        Map<?, ?> impact = (Map<?, ?>) summary.get("energyImpact");
        assertThat(entries(impact))
                .isNotNull()
                .containsEntry("status", "estimated")
                .containsEntry("basis", "statistics")
                .containsEntry("meterCount", 1L)
                .containsEntry("from", "2026-11-04T00:00:00Z")
                .containsEntry("to", null)
                .containsEntry("value", 12.5)
                .containsEntry("unit", "度")
                .containsEntry("estimated", true);
    }

    private TaskReadScope scopeOf(String deviceId, String kind, String name) {
        RequestIdentity identity = new RequestIdentity("Bearer isolated", "TENANT", IotSourceEnum.COMPANY);
        return TaskReadScope.of(AuthorizedDeviceScope.of(identity, IotSourceEnum.COMPANY,
                List.of(new AuthorizedDevice(deviceId, kind, name, "26楼", true))));
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> entries(Object map) {
        assertThat(map).isNotNull();
        return (Map<Object, Object>) map;
    }

    private static Map<?, ?> observationOf(Map<?, ?> report, String type) {
        for (Object phase : (List<?>) report.get("phases")) {
            if (!(phase instanceof Map<?, ?> phaseView)) continue;
            if (!(phaseView.get("observations") instanceof List<?> observations)) continue;
            for (Object block : observations) {
                if (block instanceof Map<?, ?> observation && type.equals(observation.get("type"))) {
                    return observation;
                }
            }
        }
        return null;
    }
}
