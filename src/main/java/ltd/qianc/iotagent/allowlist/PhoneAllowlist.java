package ltd.qianc.iotagent.allowlist;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import ltd.qianc.iotagent.allowlist.internal.FailClosedPhoneAllowlist;
import ltd.qianc.iotagent.allowlist.internal.JdbcPhoneAllowlist;
import ltd.qianc.iotagent.audit.AuditEventStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 持久手机号名单及公司任务资格；姓名只关联本地已核验记录，资格匹配只读内存快照。
 *
 * <ul>
 *   <li>存储不可用时管理读写抛 ALLOWLIST_UNAVAILABLE；</li>
 *   <li>add/remove 幂等，并要求 expectedRevision 与当前快照一致，过期抛 ALLOWLIST_VERSION_CONFLICT。</li>
 * </ul>
 */
public interface PhoneAllowlist {

    PhoneAllowlistView list();

    boolean companyTasksEnabled(ltd.qianc.iotagent.auth.PrincipalInfo principal);

    default void requireCompanyTasks(ltd.qianc.iotagent.auth.PrincipalInfo principal, String source) {
        if ("company".equals(source) && !companyTasksEnabled(principal)) {
            throw new ltd.qianc.iotagent.error.BusinessException(
                    "COMPANY_TASKS_NOT_ALLOWED", "当前账号未开通任务功能", 403);
        }
    }

    /** 幂等添加：手机号已存在时返回既有记录且不推进 revision。 */
    AllowedPhone add(long expectedRevision, String mobileNumber, String actor);

    /** 幂等移除：手机号不存在时视为成功且不推进 revision。 */
    void remove(long expectedRevision, String mobileNumber, String actor);

    /** MySQL 实现；建表由 db/mysql/agent-allowlist.sql 负责。 */
    static PhoneAllowlist mysql(JdbcTemplate jdbc, TransactionTemplate transactions,
            ObjectMapper mapper, AuditEventStore audit, Clock clock) {
        return new JdbcPhoneAllowlist(jdbc, transactions, mapper, audit, clock);
    }

    /** 无存储时管理读写不可用，公司任务资格关闭；其他业务不受影响。 */
    static PhoneAllowlist failClosed(String reason) {
        return new FailClosedPhoneAllowlist(reason);
    }
}
