package ltd.qianc.iotagent.allowlist.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import ltd.qianc.iotagent.allowlist.AllowedPhone;
import ltd.qianc.iotagent.allowlist.PhoneAllowlist;
import ltd.qianc.iotagent.allowlist.PhoneAllowlistView;
import ltd.qianc.iotagent.audit.AuditEventFact;
import ltd.qianc.iotagent.audit.AuditEventStore;
import ltd.qianc.iotagent.error.BusinessException;
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
    static final int NAME_LIMIT = 64;

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
    public boolean companyTasksEnabled(ltd.qianc.iotagent.auth.PrincipalInfo principal) {
        return principal != null && principal.verified() && principal.mobileNumber() != null
                && snapshot.phones().contains(principal.mobileNumber().strip());
    }

    private PhoneAllowlistView readView(long revision) {
        List<AllowedPhone> users = jdbc.query(
                "SELECT mobile_number,name,added_by,create_time FROM phone_allowlist ORDER BY create_time,id",
                (rs, row) -> new AllowedPhone(rs.getString(1), rs.getString(2),
                        rs.getString(3), rs.getTimestamp(4).toInstant().toString()));
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
            return transactions.execute(status -> {
                long revision = lockedRevision();
                return readView(revision);
            });
        } catch (RuntimeException storageFailure) {
            throw unavailable();
        }
    }

    @Override
    public AllowedPhone add(long expectedRevision, String mobileNumber, String name, String actor) {
        String mobile = validatedMobile(mobileNumber);
        String note = validatedName(name);
        String resolvedActor = resolvedActor(actor);
        try {
            return transactions.execute(status -> {
                long revision = lockedRevision();
                if (revision != expectedRevision) throw conflict();
                Optional<AllowedPhone> existing = find(mobile);
                if (existing.isPresent()) return existing.get();
                Timestamp at = Timestamp.from(now());
                jdbc.update("INSERT INTO phone_allowlist"
                                + "(mobile_number,name,added_by,create_time,update_time) VALUES (?,?,?,?,?)",
                        mobile, note, resolvedActor, at, at);
                long next = bumpRevision();
                appendAudit("PHONE_ALLOWLIST_ADDED", mobile, note, resolvedActor, next,
                        Map.of("addedAt", at.toInstant().toString()));
                readView(next);
                return new AllowedPhone(mobile, note, resolvedActor, at.toInstant().toString());
            });
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
                    appendAudit("PHONE_ALLOWLIST_REMOVED", mobile, null, resolvedActor, next,
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
        return jdbc.query("SELECT mobile_number,name,added_by,create_time "
                        + "FROM phone_allowlist WHERE mobile_number=?",
                (rs, row) -> new AllowedPhone(rs.getString(1), rs.getString(2),
                        rs.getString(3), rs.getTimestamp(4).toInstant().toString()),
                mobileNumber).stream().findFirst();
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

    private void appendAudit(String eventType, String mobileNumber, String name, String actor,
            long revision, Map<String, Object> tail) {
        if (audit == null) throw new IllegalStateException("名单变更审计不可用");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mobileNumber", mobileNumber);
        if (name != null) body.put("name", name);
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

    private static String validatedName(String name) {
        if (name == null || name.isBlank()) return null;
        String note = name.strip();
        if (note.length() > NAME_LIMIT) {
            throw new BusinessException("ALLOWLIST_INVALID", "姓名备注长度超出限制", 400);
        }
        return note;
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
