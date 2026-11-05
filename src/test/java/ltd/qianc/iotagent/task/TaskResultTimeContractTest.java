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

class TaskResultTimeContractTest {
    @Test
    void reportTimeCannotBeEarlierThanItsLastCompletedOperation() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Instant proposedAt = Instant.parse("2026-11-04T07:28:00Z");
        Instant firstAt = Instant.parse("2026-11-04T07:30:00Z");
        Instant lastAt = Instant.parse("2026-11-04T07:31:00Z");
        Instant endedAt = Instant.parse("2026-11-04T07:31:05Z");
        var definition = mapper.readTree("{\"actions\":[{\"deviceId\":\"light\",\"kind\":\"lighting\"}]}");
        var result = mapper.readTree("{\"devices\":{\"light\":{\"outcome\":\"unconfirmed\"}}}");
        var rows = List.of(
                new TaskDetailProjector.PhaseRow("off", firstAt, "COMPLETED", definition, result, firstAt, firstAt.plusSeconds(2), null),
                new TaskDetailProjector.PhaseRow("on", lastAt, "COMPLETED", definition, result, lastAt, endedAt, null));
        TaskDetailProjector projector = new TaskDetailProjector(mapper, id -> rows);
        RequestIdentity identity = new RequestIdentity("Bearer isolated", "TENANT", IotSourceEnum.COMPANY);
        TaskReadScope scope = TaskReadScope.of(AuthorizedDeviceScope.of(identity, IotSourceEnum.COMPANY,
                List.of(new AuthorizedDevice("light", "lighting", "灯", "办公室", true))));
        var task = new TaskDetailProjector.TaskSource("task", 4, "COMPLETED",
                mapper.readTree("{\"source\":\"company\",\"ownerKey\":\"owner\"}"), proposedAt, proposedAt);
        Map<?, ?> report = (Map<?, ?>) projector.detail(task, scope, "owner", Map.of()).get("report");
        assertThat(report.get("generatedAt")).isEqualTo("2026-11-04T07:31:05Z");
    }
}
