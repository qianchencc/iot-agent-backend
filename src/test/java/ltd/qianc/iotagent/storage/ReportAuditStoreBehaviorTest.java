package ltd.qianc.iotagent.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import ltd.qianc.iotagent.audit.AuditEventFact;
import ltd.qianc.iotagent.audit.AuditEventQuery;
import ltd.qianc.iotagent.audit.AuditEventStore;
import ltd.qianc.iotagent.audit.AuditMember;
import ltd.qianc.iotagent.audit.AuditMemberIndex;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

/**
 * 审计存储行为回归（隔离 MySQL）：追加幂等且重复事件不覆盖原内容；
 * 列表查询过滤/游标全部下推且有界；成员索引按可信身份分组并按活跃度排序。
 */
@SpringBootTest(properties = {"agent.storage.mysql.enabled=true", "agent.storage.redis.enabled=false",
        "iot.identity.verify=false", "agent.heartbeat-ms=0", "agent.task.scanner.poll-ms=3600000",
        "spring.datasource.url=${AGENT_TASK_FIXTURE_MYSQL_URL}",
        "spring.datasource.username=${AGENT_TASK_FIXTURE_MYSQL_USERNAME}",
        "spring.datasource.password=${AGENT_TASK_FIXTURE_MYSQL_PASSWORD}"})
@EnabledIfEnvironmentVariable(named = "AGENT_TASK_FIXTURE_MYSQL_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "AGENT_TASK_FIXTURE_MYSQL_USERNAME", matches = ".+")
@EnabledIfEnvironmentVariable(named = "AGENT_TASK_FIXTURE_MYSQL_PASSWORD", matches = ".+")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReportAuditStoreBehaviorTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AuditEventStore audit;

    private final String tenant = "AUDT" + UUID.randomUUID().toString().substring(0, 8);

    @AfterEach
    void cleanTenant() {
        jdbc.update("DELETE FROM audit_events WHERE tenant_code=?", tenant);
    }

    @Test
    void appendIsIdempotentAndDuplicateEventIdKeepsOriginalContent() {
        String eventId = "evt-" + UUID.randomUUID();
        Instant firstAt = Instant.parse("2026-11-04T10:00:00Z");
        AuditEventFact first = audit.append(new AuditEventFact(eventId, tenant,
                "AGENT_REQUESTED", "{\"request\":\"原始请求\"}", firstAt));
        assertThat(first.payloadJson()).contains("原始请求");
        assertThat(first.rowId()).isPositive();

        AuditEventFact duplicate = audit.append(new AuditEventFact(eventId, tenant,
                "AGENT_REQUESTED", "{\"request\":\"篡改后的请求\"}", Instant.parse("2026-11-04T11:00:00Z")));
        assertThat(duplicate.payloadJson()).contains("原始请求");
        assertThat(duplicate.createdAt()).isEqualTo(firstAt);

        assertThat(audit.findByEventId(eventId)).hasValueSatisfying(stored -> {
            assertThat(stored.payloadJson()).contains("原始请求");
            assertThat(stored.createdAt()).isEqualTo(firstAt);
        });
        assertThat(audit.readByTenant(tenant)).hasSize(1);
    }

    @Test
    void queryPushesFiltersDownAndPagesWithStableCursor() {
        Instant t1 = Instant.parse("2026-11-04T10:00:00Z");
        Instant t2 = Instant.parse("2026-11-04T10:01:00Z");
        Instant t3 = Instant.parse("2026-11-04T10:02:00Z");
        Instant t4 = Instant.parse("2026-11-04T10:03:00Z");
        audit.append(new AuditEventFact(id(), tenant, "AGENT_REQUESTED",
                "{\"source\":\"office\",\"principalId\":\"p-1\",\"identityRealm\":\"https://a.example.com/\",\"status\":\"started\"}", t1));
        audit.append(new AuditEventFact(id(), tenant, "ACTION_DRAFT_RESOLVED",
                "{\"source\":\"company\",\"principalId\":\"p-1\",\"identityRealm\":\"https://a.example.com/\",\"status\":\"RESOLVED\"}", t2));
        audit.append(new AuditEventFact(id(), tenant, "ACTION_DRAFT_INTERRUPTED",
                "{\"source\":\"office\",\"principalId\":\"p-1\",\"identityRealm\":\"https://b.example.com/\",\"status\":\"FAILED\"}", t3));
        audit.append(new AuditEventFact(id(), tenant, "PHONE_ALLOWLIST_ADDED",
                "{\"mobileNumber\":\"13800000000\",\"actor\":\"admin\"}", t4));

        List<AuditEventFact> firstPage = audit.query(new AuditEventQuery(tenant, null, null, null,
                null, false, null, null, Set.of(), Set.of(), false, null, 0, 2));
        assertThat(firstPage).extracting(AuditEventFact::eventType)
                .containsExactly("PHONE_ALLOWLIST_ADDED", "ACTION_DRAFT_INTERRUPTED");

        AuditEventFact cursorRow = firstPage.get(firstPage.size() - 1);
        List<AuditEventFact> secondPage = audit.query(new AuditEventQuery(tenant, null, null, null,
                null, false, null, null, Set.of(), Set.of(), false, cursorRow.createdAt(), cursorRow.rowId(), 2));
        assertThat(secondPage).extracting(AuditEventFact::eventType)
                .containsExactly("ACTION_DRAFT_RESOLVED", "AGENT_REQUESTED");

        List<AuditEventFact> realmScoped = audit.query(new AuditEventQuery(tenant,
                "https://a.example.com/", "p-1", null, null, false, null, null, Set.of(), Set.of(), false, null, 0, 10));
        assertThat(realmScoped).extracting(AuditEventFact::eventType)
                .containsExactly("ACTION_DRAFT_RESOLVED", "AGENT_REQUESTED");

        List<AuditEventFact> failed = audit.query(new AuditEventQuery(tenant, null, null, null,
                null, false, null, null, Set.of("run.failed", "FAILED"), Set.of("AGENT_RUN_FAILED"), false, null, 0, 10));
        assertThat(failed).extracting(AuditEventFact::eventType).containsExactly("ACTION_DRAFT_INTERRUPTED");

        List<AuditEventFact> unknown = audit.query(new AuditEventQuery(tenant, null, null, null,
                null, false, null, null, Set.of(), Set.of(
                        "AGENT_REQUESTED", "AGENT_RUN_COMPLETED", "AGENT_RUN_FAILED", "AGENT_RUN_ABORTED",
                        "AGENT_RUN_AWAITING_CONFIRMATION", "IMMEDIATE_TASK_RECORD_FAILED",
                        "COMPANY_AGENT_DENIED", "PHONE_ALLOWLIST_ADDED", "PHONE_ALLOWLIST_REMOVED",
                        "COMPANY_AGENT_ALLOWLIST_ADDED", "COMPANY_AGENT_ALLOWLIST_REMOVED",
                        "TASK_PROPOSED", "TASK_CHANGE_PROPOSED", "TASK_CANCELLATION_PROPOSED"),
                true, null, 0, 10));
        // Every fixture event either carries a payload status or a result-bearing type.
        assertThat(unknown).isEmpty();

        List<AuditEventFact> unresolved = audit.query(new AuditEventQuery(tenant, null, null, null,
                null, true, null, null, Set.of(), Set.of(), false, null, 0, 10));
        assertThat(unresolved).extracting(AuditEventFact::eventType).containsExactly("PHONE_ALLOWLIST_ADDED");

        List<AuditEventFact> windowed = audit.query(new AuditEventQuery(tenant, null, null, null,
                null, false, Instant.parse("2026-11-04T10:02:30Z"), null, Set.of(), Set.of(), false, null, 0, 10));
        assertThat(windowed).extracting(AuditEventFact::eventType).containsExactly("PHONE_ALLOWLIST_ADDED");
    }

    @Test
    void queryFiltersByEventTypeBeforePaginationAndKeepsTieOrder() {
        // Two rows share one timestamp: pagination must still walk (create_time,id)
        // after the event type filter narrows the candidate set.
        Instant tie = Instant.parse("2026-11-04T11:00:00Z");
        audit.append(new AuditEventFact(id(), tenant, "TASK_PROPOSED",
                "{\"source\":\"company\",\"principalId\":\"p-2\",\"identityRealm\":\"https://c.example.com/\"}", tie));
        audit.append(new AuditEventFact(id(), tenant, "ACTION_DRAFT_PROPOSED",
                "{\"source\":\"office\",\"principalId\":\"p-2\",\"identityRealm\":\"https://c.example.com/\"}", tie));
        audit.append(new AuditEventFact(id(), tenant, "TASK_PROPOSED",
                "{\"source\":\"office\",\"principalId\":\"p-2\",\"identityRealm\":\"https://c.example.com/\"}",
                Instant.parse("2026-11-04T10:59:00Z")));

        List<AuditEventFact> proposed = audit.query(new AuditEventQuery(tenant, null, null, null,
                "TASK_PROPOSED", false, null, null, Set.of(), Set.of(), false, null, 0, 10));
        assertThat(proposed).extracting(AuditEventFact::eventType).containsOnly("TASK_PROPOSED");
        assertThat(proposed).hasSize(2);
        assertThat(proposed.get(0).createdAt()).isEqualTo(tie);

        AuditEventFact tieCursor = proposed.get(0);
        List<AuditEventFact> older = audit.query(new AuditEventQuery(tenant, null, null, null,
                "TASK_PROPOSED", false, null, null, Set.of(), Set.of(), false,
                tieCursor.createdAt(), tieCursor.rowId(), 10));
        assertThat(older).extracting(AuditEventFact::eventType).containsOnly("TASK_PROPOSED");
        assertThat(older).hasSize(1);
        assertThat(older.get(0).createdAt()).isEqualTo(Instant.parse("2026-11-04T10:59:00Z"));

        List<AuditEventFact> none = audit.query(new AuditEventQuery(tenant, null, null, null,
                "NOT_EMITTED_TYPE", false, null, null, Set.of(), Set.of(), false, null, 0, 10));
        assertThat(none).isEmpty();
    }

    @Test
    void memberIndexGroupsTrustedIdentityAndOrdersByLatestActivity() {
        audit.append(new AuditEventFact(id(), tenant, "AGENT_REQUESTED",
                "{\"source\":\"office\",\"principalId\":\"p-1\",\"identityRealm\":\"https://a.example.com/\"}",
                Instant.parse("2026-11-04T10:00:00Z")));
        audit.append(new AuditEventFact(id(), tenant, "ACTION_DRAFT_RESOLVED",
                "{\"source\":\"company\",\"principalId\":\"p-1\",\"identityRealm\":\"https://a.example.com/\"}",
                Instant.parse("2026-11-04T10:01:00Z")));
        audit.append(new AuditEventFact(id(), tenant, "ACTION_DRAFT_INTERRUPTED",
                "{\"source\":\"office\",\"principalId\":\"p-1\",\"identityRealm\":\"https://b.example.com/\"}",
                Instant.parse("2026-11-04T10:02:00Z")));
        audit.append(new AuditEventFact(id(), tenant, "PHONE_ALLOWLIST_ADDED",
                "{\"actor\":\"admin\"}", Instant.parse("2026-11-04T10:03:00Z")));

        AuditMemberIndex index = audit.memberIndex(tenant, null, null, 10, 0);
        assertThat(index.total()).isEqualTo(3);
        List<AuditMember> members = index.members();
        assertThat(members).extracting(AuditMember::identityResolved)
                .containsExactly(false, true, true);
        assertThat(members.get(0).principalId()).isNull();
        assertThat(members.get(0).eventCount()).isEqualTo(1);
        assertThat(members.get(0).sources()).containsEntry("unknown", 1L);
        assertThat(members.get(1).identityRealm()).isEqualTo("https://b.example.com/");
        assertThat(members.get(1).eventCount()).isEqualTo(1);
        assertThat(members.get(2).identityRealm()).isEqualTo("https://a.example.com/");
        assertThat(members.get(2).eventCount()).isEqualTo(2);
        assertThat(members.get(2).sources()).containsEntry("office", 1L).containsEntry("company", 1L);
        assertThat(members.get(2).firstAt()).isEqualTo(Instant.parse("2026-11-04T10:00:00Z"));
        assertThat(members.get(2).lastAt()).isEqualTo(Instant.parse("2026-11-04T10:01:00Z"));

        AuditMemberIndex filtered = audit.memberIndex(tenant, "https://a.example.com/", "p-1", 10, 0);
        assertThat(filtered.total()).isEqualTo(1);
        assertThat(filtered.members().get(0).eventCount()).isEqualTo(2);
    }

    @Test
    void nameLookupUsesOnlyRequestedVerifiedPhonesAndLeavesConflictsUnknown() {
        String first = "13800138000", second = "13900139000";
        assertThat(audit.knownNames(Set.of(first, second))).isEmpty();
        audit.append(new AuditEventFact(id(), tenant, "AGENT_REQUESTED",
                "{\"source\":\"company\",\"principalId\":\"p-1\",\"identityRealm\":\"fixture\","
                        + "\"mobileNumber\":\"" + first + "\",\"displayName\":\"测试成员甲\"}"));
        audit.append(new AuditEventFact(id(), tenant, "PHONE_ALLOWLIST_ADDED",
                "{\"mobileNumber\":\"" + second + "\",\"name\":\"旧备注\",\"displayName\":\"未核验姓名\"}"));
        assertThat(audit.knownNames(Set.of(first, second))).containsExactly(
                java.util.Map.entry(first, "测试成员甲"));
        assertThat(audit.knownNames(Set.of(second))).isEmpty();
        audit.append(new AuditEventFact(id(), tenant, "AGENT_REQUESTED",
                "{\"source\":\"office\",\"principalId\":\"p-2\",\"identityRealm\":\"fixture\","
                        + "\"mobileNumber\":\"" + second + "\",\"displayName\":\"测试成员乙\"}"));
        audit.append(new AuditEventFact(id(), tenant, "AGENT_REQUESTED",
                "{\"source\":\"company\",\"principalId\":\"p-3\",\"identityRealm\":\"other-fixture\","
                        + "\"mobileNumber\":\"" + first + "\",\"displayName\":\"姓名冲突\"}"));
        assertThat(audit.knownNames(Set.of(first, second))).containsExactly(
                java.util.Map.entry(second, "测试成员乙"));
    }

    private static String id() {
        return "evt-" + UUID.randomUUID();
    }
}
