package ltd.qianc.iotagent.allowlist;

/**
 * 白名单成员：手机号是唯一键，姓名仅来自本地已核验记录，不参与资格匹配。
 */
public record AllowedPhone(
        String mobileNumber,
        String name,
        String addedBy,
        String addedAt) {
}
