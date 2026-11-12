package com.ehe.iotagent.allowlist.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import com.ehe.iotagent.allowlist.AllowedPhone;
import com.ehe.iotagent.allowlist.PhoneAllowlist;
import com.ehe.iotagent.allowlist.PhoneAllowlistView;
import com.ehe.iotagent.audit.AuditEventFact;
import com.ehe.iotagent.audit.AuditEventStore;
import com.ehe.iotagent.error.BusinessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * MySQL 白名单事实：手机号成员表 + 单行 revision 状态表，CAS 于 state 行锁上串行化。
 * 公司任务资格只读内存快照；管理事务提交后发布，回滚不改变资格。
 */
public final class JdbcPhoneAllowlist implements PhoneAllowlist {

    /** 管理审计事件的租户归属：名单与业务租户无关，统一落在管理域。 */
    static final String AUDIT_TENANT = "ADMIN";
    static final int MOBILE_LIMIT = 20;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;
    private final AuditEventStore audit;
    private final Clock clock;
    private record Snapshot(long revision, java.util.Set<String> phones) { }
    private volatile Snapshot snapshot = new Snapshot(-1, java.util.Set.of());

    public JdbcPhoneAllowlist(JdbcTemplate jdbc, TransactionTemplate transactions,
            ObjectMapper mapper, AuditEventStore audit, Clock clock) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.mapper = mapper;
        this.audit = audit;
        this.clock = clock;
        try {
            list();
        } catch (BusinessException unavailable) {
            org.slf4j.LoggerFactory.getLogger(JdbcPhoneAllowlist.class)
                    .warn("白名单初始读取失败，公司任务功能暂不开放");
        }
    }

    @Override
    public boolean companyTasksEnabled(com.ehe.iotagent.auth.PrincipalInfo principal) {
        return principal != null && principal.verified() && principal.mobileNumber() != null
                && snapshot.phones().contains(principal.mobileNumber().strip());
    }

    private PhoneAllowlistView readView(long revision) {
        List<AllowedPhone> users = jdbc.query(
                "SELECT mobile_number,added_by,create_time FROM phone_allowlist ORDER BY create_time,id",
                (rs, row) -> new AllowedPhone(rs.getString(1), null,
                        rs.getString(2), rs.getTimestamp(3).toInstant().toString()));
        PhoneAllowlistView view = new PhoneAllowlistView(revision, users);
        var next = new Snapshot(revision, users.stream().map(AllowedPhone::mobileNumber)
                .collect(java.util.stream.Collectors.toUnmodifiableSet()));
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override public void afterCommit() { publish(next); }
                });
        return view;
    }

    private synchronized void publish(Snapshot next) {
        if (next.revision() >= snapshot.revision()) snapshot = next;
    }

    @Override
    public PhoneAllowlistView list() {
        try {
            PhoneAllowlistView view = transactions.execute(status -> readView(lockedRevision()));
            return new PhoneAllowlistView(view.revision(), named(view.users()));
        } catch (RuntimeException storageFailure) {
            throw unavailable();
        }
    }

    @Override
    public AllowedPhone add(long expectedRevision, String mobileNumber, String actor) {
        String mobile = validatedMobile(mobileNumber);
        String resolvedActor = resolvedActor(actor);
        try {
            AllowedPhone saved = transactions.execute(status -> {
                long revision = lockedRevision();
                if (revision != expectedRevision) throw conflict();
                Optional<AllowedPhone> existing = find(mobile);
                if (existing.isPresent()) return existing.get();
                Timestamp at = Timestamp.from(now());
                jdbc.update("INSERT INTO phone_allowlist"
                                + "(mobile_number,added_by,create_time,update_time) VALUES (?,?,?,?)",
                        mobile, resolvedActor, at, at);
                long next = bumpRevision();
                appendAudit("PHONE_ALLOWLIST_ADDED", mobile, resolvedActor, next,
                        Map.of("addedAt", at.toInstant().toString()));
                readView(next);
                return new AllowedPhone(mobile, null, resolvedActor, at.toInstant().toString());
            });
            return named(List.of(saved)).getFirst();
        } catch (BusinessException error) {
            throw error;
        } catch (RuntimeException storageFailure) {
            throw unavailable();
        }
    }

    @Override
    public void remove(long expectedRevision, String mobileNumber, String actor) {
        String mobile = validatedMobile(mobileNumber);
        String resolvedActor = resolvedActor(actor);
        try {
            transactions.executeWithoutResult(status -> {
                long revision = lockedRevision();
                if (revision != expectedRevision) throw conflict();
                int deleted = jdbc.update("DELETE FROM phone_allowlist WHERE mobile_number=?", mobile);
                if (deleted > 0) {
                    long next = bumpRevision();
                    appendAudit("PHONE_ALLOWLIST_REMOVED", mobile, resolvedActor, next,
                            Map.of("removedAt", now().toString()));
                    readView(next);
                }
            });
        } catch (BusinessException error) {
            throw error;
        } catch (RuntimeException storageFailure) {
            throw unavailable();
        }
    }

    private Optional<AllowedPhone> find(String mobileNumber) {
        return jdbc.query("SELECT mobile_number,added_by,create_time "
                        + "FROM phone_allowlist WHERE mobile_number=?",
                (rs, row) -> new AllowedPhone(rs.getString(1), null,
                        rs.getString(2), rs.getTimestamp(3).toInstant().toString()),
                mobileNumber).stream().findFirst();
    }

    private List<AllowedPhone> named(List<AllowedPhone> users) {
        if (audit == null || users.isEmpty()) return users;
        Map<String, String> names;
        try {
            names = audit.knownNames(users.stream().map(AllowedPhone::mobileNumber)
                    .collect(java.util.stream.Collectors.toSet()));
        } catch (RuntimeException unavailable) {
            // 姓名只是展示信息；缺失或读取失败不能阻止提前加白。
            return users;
        }
        return users.stream().map(user -> new AllowedPhone(user.mobileNumber(), names.get(user.mobileNumber()),
                user.addedBy(), user.addedAt())).toList();
    }

    /** 白名单 revision 锁：add/remove 在该行锁内完成版本比对与写入，避免并发覆盖。 */
    private long lockedRevision() {
        ensureStateRow();
        List<Long> revisions = jdbc.query(
                "SELECT revision FROM phone_allowlist_state WHERE singleton=1 FOR UPDATE",
                (rs, row) -> rs.getLong(1));
        if (revisions.isEmpty()) throw unavailable();
        return revisions.getFirst();
    }

    private long currentRevision() {
        Long revision = jdbc.queryForObject(
                "SELECT revision FROM phone_allowlist_state WHERE singleton=1", Long.class);
        if (revision == null) throw unavailable();
        return revision;
    }

    private long bumpRevision() {
        jdbc.update("UPDATE phone_allowlist_state SET revision=revision+1,update_time=? WHERE singleton=1",
                Timestamp.from(now()));
        return currentRevision();
    }

    private void ensureStateRow() {
        Integer present = jdbc.queryForObject(
                "SELECT COUNT(*) FROM phone_allowlist_state WHERE singleton=1", Integer.class);
        if (present == null || present > 0) return;
        Timestamp at = Timestamp.from(now());
        try {
            jdbc.update("INSERT INTO phone_allowlist_state(singleton,revision,create_time,update_time) "
                    + "VALUES (1,0,?,?)", at, at);
        } catch (DuplicateKeyException concurrentInsert) {
            // 并发初始化以唯一键收敛，不视为存储故障
        }
    }

    private void appendAudit(String eventType, String mobileNumber, String actor,
            long revision, Map<String, Object> tail) {
        if (audit == null) throw new IllegalStateException("名单变更审计不可用");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mobileNumber", mobileNumber);
        body.put("actor", actor);
        body.put("revision", revision);
        body.putAll(tail);
        try {
            audit.append(new AuditEventFact(UUID.randomUUID().toString(), AUDIT_TENANT,
                    eventType, mapper.writeValueAsString(body)));
        } catch (Exception auditFailure) {
            throw new IllegalStateException("名单变更审计未能保存", auditFailure);
        }
    }

    private static String validatedMobile(String mobileNumber) {
        if (mobileNumber == null || mobileNumber.isBlank()) {
            throw new BusinessException("ALLOWLIST_INVALID", "手机号不能为空", 400);
        }
        String mobile = mobileNumber.strip();
        String digits = mobile.startsWith("+") ? mobile.substring(1) : mobile;
        if (digits.length() < 5 || digits.length() > MOBILE_LIMIT
                || !digits.chars().allMatch(character -> character >= '0' && character <= '9')) {
            throw new BusinessException("ALLOWLIST_INVALID",
                    "手机号格式不正确，仅支持5-20位数字与可选前置加号", 400);
        }
        return mobile;
    }


    private static String resolvedActor(String actor) {
        return actor == null || actor.isBlank() ? "operator" : actor.strip();
    }

    private static BusinessException conflict() {
        return new BusinessException("ALLOWLIST_VERSION_CONFLICT", "白名单已被其他操作修改，请刷新后重试", 409);
    }

    private static BusinessException unavailable() {
        return new BusinessException("ALLOWLIST_UNAVAILABLE", "白名单存储不可用，请稍后重试", 503);
    }

    private Instant now() {
        return clock == null ? Instant.now() : clock.instant();
    }
}
