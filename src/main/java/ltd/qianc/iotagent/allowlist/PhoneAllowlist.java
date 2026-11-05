package ltd.qianc.iotagent.allowlist;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import ltd.qianc.iotagent.allowlist.internal.FailClosedPhoneAllowlist;
import ltd.qianc.iotagent.allowlist.internal.JdbcPhoneAllowlist;
import ltd.qianc.iotagent.audit.AuditEventStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 手机号白名单的管理事实：仅维护手机号与可选姓名备注，不参与任何业务准入。
 * 名单不决定谁能使用公司 Agent，也不影响公司／办公室业务可用性。
 *
 * <ul>
 *   <li>存储不可用时管理读写抛 ALLOWLIST_UNAVAILABLE；</li>
 *   <li>add/remove 幂等，并要求 expectedRevision 与当前快照一致，过期抛 ALLOWLIST_VERSION_CONFLICT。</li>
 * </ul>
 */
public interface PhoneAllowlist {

    PhoneAllowlistView list();

    /** 幂等添加：手机号已存在时返回既有记录且不推进 revision。 */
    AllowedPhone add(long expectedRevision, String mobileNumber, String name, String actor);

    /** 幂等移除：手机号不存在时视为成功且不推进 revision。 */
    void remove(long expectedRevision, String mobileNumber, String actor);

    /** MySQL 实现；建表由 db/mysql/agent-allowlist.sql 负责。 */
    static PhoneAllowlist mysql(JdbcTemplate jdbc, TransactionTemplate transactions,
            ObjectMapper mapper, AuditEventStore audit, Clock clock) {
        return new JdbcPhoneAllowlist(jdbc, transactions, mapper, audit, clock);
    }

    /** 无存储时管理读写不可用；不影响任何业务路径。 */
    static PhoneAllowlist failClosed(String reason) {
        return new FailClosedPhoneAllowlist(reason);
    }
}
