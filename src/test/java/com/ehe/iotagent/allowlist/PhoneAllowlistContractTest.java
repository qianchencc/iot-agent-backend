package com.ehe.iotagent.allowlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.ehe.iotagent.audit.AuditEventFact;
import com.ehe.iotagent.audit.AuditEventStore;
import com.ehe.iotagent.error.BusinessException;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 模块公开接缝上的真实 JDBC 行为：幂等、版本冲突、持久化与管理审计。 */
class PhoneAllowlistContractTest {

    private static final String MOBILE = "13800138000";

    @Test
    void addAndRemoveAreIdempotentAndGuardedByExpectedRevision() {
        RecordingAudit audit = new RecordingAudit();
        PhoneAllowlist allowlist = allowlist(audit);

        assertThat(allowlist.list().revision()).isZero();
        assertThat(allowlist.list().users()).isEmpty();

        AllowedPhone added = allowlist.add(0, MOBILE, "operator");
        assertThat(added.mobileNumber()).isEqualTo(MOBILE);
        assertThat(added.addedBy()).isEqualTo("operator");
        assertThat(added.addedAt()).isNotBlank();
        assertThat(allowlist.list().revision()).isEqualTo(1);
        assertThat(allowlist.list().users())
                .extracting(AllowedPhone::mobileNumber)
                .containsExactly(MOBILE);

        // 重复添加幂等：不推进 revision，保留原记录。
        AllowedPhone repeated = allowlist.add(1, MOBILE, "operator");
        assertThat(repeated.addedAt()).isEqualTo(added.addedAt());
        assertThat(allowlist.list().revision()).isEqualTo(1);
        assertThat(allowlist.list().users()).hasSize(1);

        // 过期版本号被拒绝
        assertThatThrownBy(() -> allowlist.add(0, "13900139000", "operator"))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.code()).isEqualTo("ALLOWLIST_VERSION_CONFLICT");
                    assertThat(error.httpStatus()).isEqualTo(409);
                });

        allowlist.remove(1, MOBILE, "operator");
        assertThat(allowlist.list().revision()).isEqualTo(2);
        assertThat(allowlist.list().users()).isEmpty();

        // 重复移除幂等：不推进 revision
        allowlist.remove(2, MOBILE, "operator");
        assertThat(allowlist.list().revision()).isEqualTo(2);
        assertThatThrownBy(() -> allowlist.remove(1, MOBILE, "operator"))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.code()).isEqualTo("ALLOWLIST_VERSION_CONFLICT"));
    }

    @Test
    void blankOrMalformedPhonesAreRejectedBeforeStorage() {
        PhoneAllowlist allowlist = allowlist(new RecordingAudit());

        for (String invalid : new String[] {" ", "abc", "1234", "138 0013 8000",
                "13800138000x", "+" + "1".repeat(21)}) {
            assertThatThrownBy(() -> allowlist.add(0, invalid, "operator"))
                    .isInstanceOfSatisfying(BusinessException.class, error -> {
                        assertThat(error.code()).isEqualTo("ALLOWLIST_INVALID");
                        assertThat(error.httpStatus()).isEqualTo(400);
                    });
        }
        assertThatThrownBy(() -> allowlist.add(0, "1".repeat(21), "operator"))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.code()).isEqualTo("ALLOWLIST_INVALID"));
        assertThatThrownBy(() -> allowlist.remove(0, "not-a-phone", "operator"))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.code()).isEqualTo("ALLOWLIST_INVALID"));
        assertThat(allowlist.list().users()).isEmpty();
        assertThat(allowlist.list().revision()).isZero();
    }

    @Test
    void recordsSurviveAcrossStoreInstancesOnTheSameDatabase() {
        RecordingAudit audit = new RecordingAudit();
        JdbcDataSource source = phoneTables();
        PhoneAllowlist first = PhoneAllowlist.mysql(jdbc(source),
                transactions(source), new ObjectMapper(), audit, Clock.systemUTC());
        first.add(0, MOBILE, "operator");
        first.add(1, "+8613900139000", "operator");

        // 新实例只依赖数据库：证明记录真实持久，而非内存态
        PhoneAllowlist second = PhoneAllowlist.mysql(jdbc(source),
                transactions(source), new ObjectMapper(), audit, Clock.systemUTC());
        PhoneAllowlistView view = second.list();
        assertThat(view.revision()).isEqualTo(2);
        assertThat(view.users())
                .extracting(AllowedPhone::mobileNumber)
                .containsExactly(MOBILE, "+8613900139000");

        second.remove(2, MOBILE, "operator");
        assertThat(first.list().users())
                .extracting(AllowedPhone::mobileNumber)
                .containsExactly("+8613900139000");
    }

    @Test
    void managementAuditEventsFollowRealChangesOnly() {
        RecordingAudit audit = new RecordingAudit();
        PhoneAllowlist allowlist = allowlist(audit);

        allowlist.add(0, MOBILE, "operator");
        allowlist.add(1, MOBILE, "operator"); // 幂等重复：不产生事件
        allowlist.remove(1, "13900139000", "operator"); // 目标不存在：幂等成功且不产生事件
        allowlist.remove(1, MOBILE, "operator");

        assertThat(audit.events).hasSize(2);
        AuditEventFact added = audit.events.get(0);
        assertThat(added.tenantCode()).isEqualTo("ADMIN");
        assertThat(added.eventType()).isEqualTo("PHONE_ALLOWLIST_ADDED");
        assertThat(added.payloadJson()).contains("\"mobileNumber\":\"" + MOBILE + "\"");
        assertThat(added.payloadJson()).contains("\"actor\":\"operator\"");
        assertThat(added.payloadJson()).contains("\"revision\":1");
        AuditEventFact removed = audit.events.get(1);
        assertThat(removed.eventType()).isEqualTo("PHONE_ALLOWLIST_REMOVED");
        assertThat(removed.payloadJson()).contains("\"mobileNumber\":\"" + MOBILE + "\"");
        assertThat(removed.payloadJson()).doesNotContain("\"name\"");
    }

    @Test
    void unavailableStorageRejectsAllowlistManagement() {
        PhoneAllowlist allowlist = PhoneAllowlist.failClosed("MySQL 存储未启用");

        assertThatThrownBy(allowlist::list)
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.code()).isEqualTo("ALLOWLIST_UNAVAILABLE");
                    assertThat(error.httpStatus()).isEqualTo(503);
                });
        assertThatThrownBy(() -> allowlist.add(0, MOBILE, "operator"))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.code()).isEqualTo("ALLOWLIST_UNAVAILABLE"));
        assertThatThrownBy(() -> allowlist.remove(0, MOBILE, "operator"))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.code()).isEqualTo("ALLOWLIST_UNAVAILABLE"));
    }

    @Test
    void missingStorageRejectsAllowlistReads() {
        JdbcDataSource missing = new JdbcDataSource();
        missing.setURL("jdbc:h2:mem:phone_allowlist_missing_" + UUID.randomUUID() + ";IFEXISTS=TRUE");
        PhoneAllowlist allowlist = PhoneAllowlist.mysql(
                new JdbcTemplate(missing),
                new TransactionTemplate(new DataSourceTransactionManager(missing)),
                new ObjectMapper(), null, Clock.systemUTC());

        assertThatThrownBy(allowlist::list)
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.code()).isEqualTo("ALLOWLIST_UNAVAILABLE"));
        assertThatThrownBy(() -> allowlist.add(0, MOBILE, "operator"))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.code()).isEqualTo("ALLOWLIST_UNAVAILABLE"));
    }

    @Test
    void auditFailureRollsBackBothTheChangeAndItsRevision() {
        RecordingAudit audit = new RecordingAudit();
        PhoneAllowlist list = allowlist(audit);
        audit.unavailable = true;
        assertThatThrownBy(() -> list.add(0, MOBILE, "operator"))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.code()).isEqualTo("ALLOWLIST_UNAVAILABLE"));
        assertThat(list.list().revision()).isZero();
        assertThat(list.list().users()).isEmpty();

        audit.unavailable = false;
        list.add(0, MOBILE, "operator");
        audit.unavailable = true;
        assertThatThrownBy(() -> list.remove(1, MOBILE, "operator"))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.code()).isEqualTo("ALLOWLIST_UNAVAILABLE"));
        assertThat(list.list().revision()).isEqualTo(1);
        assertThat(list.list().users()).extracting(AllowedPhone::mobileNumber).containsExactly(MOBILE);
    }

    @Test
    void missingAuditDoesNotAllowAnUnrecordedMutation() {
        PhoneAllowlist list = allowlist(null);
        assertThatThrownBy(() -> list.add(0, MOBILE, "operator"))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.code()).isEqualTo("ALLOWLIST_UNAVAILABLE"));
        assertThat(list.list().revision()).isZero();
        assertThat(list.list().users()).isEmpty();
    }

    @Test
    void taskEligibilityUsesCommittedPhonesAndSurvivesDatabaseReadOutage() {
        JdbcDataSource source = phoneTables();
        PhoneAllowlist list = PhoneAllowlist.mysql(jdbc(source), transactions(source),
                new ObjectMapper(), new RecordingAudit(), Clock.systemUTC());
        var principal = new com.ehe.iotagent.auth.PrincipalInfo(
                "fixture", "TEST", "member", "fixture-realm", "测试成员", MOBILE);
        assertThat(list.companyTasksEnabled(principal)).isFalse();
        list.add(0, MOBILE, "operator");
        assertThat(list.companyTasksEnabled(principal)).isTrue();
        transactions(source).executeWithoutResult(status -> {
            list.remove(1, MOBILE, "operator");
            assertThat(list.companyTasksEnabled(principal)).isTrue();
            status.setRollbackOnly();
        });
        assertThat(list.companyTasksEnabled(principal)).isTrue();
        PhoneAllowlist restarted = PhoneAllowlist.mysql(jdbc(source), transactions(source),
                new ObjectMapper(), new RecordingAudit(), Clock.systemUTC());
        assertThat(restarted.companyTasksEnabled(principal)).isTrue();
        list.remove(1, MOBILE, "operator");
        assertThat(list.companyTasksEnabled(principal)).isFalse();
        list.add(2, MOBILE, "operator");
        jdbc(source).execute("DROP TABLE phone_allowlist");
        assertThat(list.companyTasksEnabled(principal)).isTrue();
        assertThat(list.companyTasksEnabled(new com.ehe.iotagent.auth.PrincipalInfo("fixture", "TEST")))
                .isFalse();
    }

    @Test
    void preapprovedPhoneGetsItsNameOnLaterReadsWithoutChangingMembership() {
        JdbcDataSource source = phoneTables();
        RecordingAudit audit = new RecordingAudit();
        PhoneAllowlist list = PhoneAllowlist.mysql(jdbc(source), transactions(source),
                new ObjectMapper(), audit, Clock.systemUTC());
        audit.namesUnavailable = true;
        list.add(0, MOBILE, "operator");
        audit.namesUnavailable = false;
        jdbc(source).update("UPDATE phone_allowlist SET name=? WHERE mobile_number=?", "旧手工备注", MOBILE);
        assertThat(list.list().users().getFirst().name()).isNull();
        audit.append(new AuditEventFact("known-member", "TEST", "AGENT_RUN_REQUESTED",
                "{\"source\":\"company\",\"principalId\":\"member\",\"identityRealm\":\"fixture\","
                        + "\"mobileNumber\":\"" + MOBILE + "\",\"displayName\":\"测试成员甲\"}"));
        assertThat(list.list().users().getFirst().name()).isEqualTo("测试成员甲");
        assertThat(list.list().revision()).isEqualTo(1);
        assertThat(list.companyTasksEnabled(new com.ehe.iotagent.auth.PrincipalInfo(
                "fixture", "TEST", "member", "fixture", null, MOBILE))).isTrue();
    }

    private static PhoneAllowlist allowlist(RecordingAudit audit) {
        JdbcDataSource source = phoneTables();
        return PhoneAllowlist.mysql(jdbc(source), transactions(source),
                new ObjectMapper(), audit, Clock.systemUTC());
    }

    private static JdbcDataSource phoneTables() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:phone_allowlist_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = jdbc(source);
        jdbc.execute("CREATE TABLE phone_allowlist ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "mobile_number VARCHAR(32) NOT NULL,"
                + "name VARCHAR(128) NULL,"
                + "added_by VARCHAR(128) NOT NULL,"
                + "create_time TIMESTAMP NOT NULL,"
                + "update_time TIMESTAMP NOT NULL,"
                + "CONSTRAINT uk_phone_allowlist_mobile UNIQUE (mobile_number))");
        jdbc.execute("CREATE TABLE phone_allowlist_state ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "singleton TINYINT NOT NULL DEFAULT 1,"
                + "revision BIGINT NOT NULL DEFAULT 0,"
                + "create_time TIMESTAMP NOT NULL,"
                + "update_time TIMESTAMP NOT NULL,"
                + "CONSTRAINT uk_phone_allowlist_state UNIQUE (singleton))");
        return source;
    }

    private static JdbcTemplate jdbc(JdbcDataSource source) {
        return new JdbcTemplate(source);
    }

    private static TransactionTemplate transactions(JdbcDataSource source) {
        return new TransactionTemplate(new DataSourceTransactionManager(source));
    }

    private static final class RecordingAudit implements AuditEventStore {
        final List<AuditEventFact> events = new ArrayList<>();
        boolean unavailable;
        boolean namesUnavailable;

        @Override
        public AuditEventFact append(AuditEventFact event) {
            if (unavailable) throw new IllegalStateException("Audit storage offline");
            events.add(event);
            return event;
        }

        @Override
        public java.util.Map<String, String> knownNames(java.util.Set<String> phones) {
            if (namesUnavailable) throw new IllegalStateException("Name metadata offline");
            return AuditEventStore.super.knownNames(phones);
        }

        @Override
        public List<AuditEventFact> readByTenant(String tenantCode) {
            return events.stream().filter(event -> tenantCode.equals(event.tenantCode())).toList();
        }

        @Override
        public Optional<AuditEventFact> findByEventId(String eventId) {
            return events.stream().filter(event -> eventId.equals(event.eventId())).findFirst();
        }

        @Override
        public List<String> tenants() {
            return events.stream().map(AuditEventFact::tenantCode).distinct().sorted().toList();
        }
    }
}
