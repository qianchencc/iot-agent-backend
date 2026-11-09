package ltd.qianc.iotagent.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import ltd.qianc.iotagent.auth.PrincipalInfo;
import ltd.qianc.iotagent.iot.AuthorizedDevice;
import ltd.qianc.iotagent.iot.AuthorizedDeviceScope;
import ltd.qianc.iotagent.iot.IotSourceEnum;
import ltd.qianc.iotagent.iot.RequestIdentity;
import ltd.qianc.iotagent.task.TaskProjectionStore;
import ltd.qianc.iotagent.task.TaskReadScope;
import ltd.qianc.iotagent.task.TaskRecurrence;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

public class TaskCalendarProjectionTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final PrincipalInfo principal = new PrincipalInfo("Bearer fixture", "FIXTURE", "fixture-member", "fixture-realm");
    private TaskProjectionStore reader;

    @BeforeEach void setUp() throws Exception {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:calendar_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE ALIAS JSON_EXTRACT FOR 'ltd.qianc.iotagent.storage.TaskCalendarProjectionTest.extract'");
        jdbc.execute("CREATE ALIAS JSON_UNQUOTE FOR 'ltd.qianc.iotagent.storage.TaskCalendarProjectionTest.unquote'");
        jdbc.execute("CREATE TABLE task_facts(task_id VARCHAR(64),version BIGINT,status VARCHAR(32),payload_json VARCHAR(20000),create_time TIMESTAMP,update_time TIMESTAMP)");
        jdbc.execute("CREATE TABLE task_phases(task_id VARCHAR(64),due_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE task_plans(plan_id VARCHAR(64),version BIGINT,status VARCHAR(32),tenant_code VARCHAR(128),source VARCHAR(16),next_at TIMESTAMP,next_date DATE,payload_json VARCHAR(20000),create_time TIMESTAMP,update_time TIMESTAMP)");
        jdbc.execute("CREATE TABLE task_plan_occurrences(plan_id VARCHAR(64),occurrence_date DATE,task_id VARCHAR(64),status VARCHAR(32),override_payload_json VARCHAR(20000))");
        var identity = new RequestIdentity(principal.authorization(), principal.tenantCode(), IotSourceEnum.COMPANY, principal.ownerKey());
        var scope = TaskReadScope.of(AuthorizedDeviceScope.of(identity, IotSourceEnum.COMPANY,
                List.of(new AuthorizedDevice("fixture-light", "lighting", "示例照明", "示例楼层", false))));
        var payload = mapper.createObjectNode();
        payload.put("source", "company").put("tenantCode", "FIXTURE").put("ownerKey", principal.ownerKey()).put("label", "fixture-weekdays");
        payload.putObject("repeat").put("frequency", "weekdays");
        var phase = payload.putArray("phases").addObject();
        phase.put("at", "2026-11-05T02:26:00Z");
        phase.putArray("actions").addObject().put("deviceId", "fixture-light").put("kind", "lighting")
                .putObject("command").put("closeOrOpen", 1);
        jdbc.update("INSERT INTO task_plans VALUES ('fixture-plan',1,'ENABLED','FIXTURE','company',?,'2026-11-10',?,?,?)",
                Timestamp.from(Instant.parse("2026-11-10T02:26:00Z")), payload.toString(),
                Timestamp.from(boundary("2026-11-05")), Timestamp.from(boundary("2026-11-09")));
        reader = TaskFactStoresFactory.taskProjections(jdbc, mapper, (viewer, origin) -> scope);
    }

    @Test void listAndMonthGridContainEveryWeekdayInsteadOfOneOccurrencePerWindow() {
        assertThat(dates("2026-11-09", "2026-11-17"))
                .containsExactly("2026-11-10", "2026-11-11", "2026-11-12", "2026-11-13", "2026-11-16");
        assertThat(dates("2026-11-09", "2027-01-10"))
                .contains("2026-11-11", "2026-12-01", "2027-01-08");
        assertThat(dates("2026-11-30", "2027-01-04"))
                .contains("2026-12-01", "2026-12-31", "2027-01-01");
    }

    @Test void pageBoundaryOnSundayDoesNotInventAWeekendOccurrence() {
        assertThat(dates("2027-01-10", "2027-01-14"))
                .containsExactly("2027-01-11", "2027-01-12", "2027-01-13");
        assertThat(dates("2026-11-14", "2026-11-16")).isEmpty();
    }

    private List<String> dates(String from, String to) {
        return reader.list(principal, "company", boundary(from), boundary(to)).stream()
                .map(row -> String.valueOf(((Map<?, ?>) row.get("occurrence")).get("date"))).toList();
    }
    private static Instant boundary(String date) { return LocalDate.parse(date).atStartOfDay(TaskRecurrence.ZONE).toInstant(); }
    public static String extract(String value, String path) throws Exception { return new ObjectMapper().readTree(value).path(path.substring(2)).toString(); }
    public static String unquote(String value) throws Exception { return new ObjectMapper().readTree(value).asText(); }
}
