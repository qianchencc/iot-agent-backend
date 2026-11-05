package ltd.qianc.iotagent.allowlist;

/**
 * 白名单成员：手机号是唯一键，姓名仅是管理员可选备注，不参与任何匹配或验证。
 */
public record AllowedPhone(
        String mobileNumber,
        String name,
        String addedBy,
        String addedAt) {
}
