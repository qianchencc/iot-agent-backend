package com.ehe.iotagent.allowlist.internal;

import com.ehe.iotagent.allowlist.AllowedPhone;
import com.ehe.iotagent.allowlist.PhoneAllowlist;
import com.ehe.iotagent.allowlist.PhoneAllowlistView;
import com.ehe.iotagent.error.BusinessException;

/** 无可用存储时管理不可用，公司任务资格关闭。 */
public final class FailClosedPhoneAllowlist implements PhoneAllowlist {

    private final String reason;

    public FailClosedPhoneAllowlist(String reason) {
        this.reason = reason == null || reason.isBlank() ? "白名单存储未启用" : reason;
    }

    @Override
    public PhoneAllowlistView list() {
        throw unavailable();
    }

    @Override
    public boolean companyTasksEnabled(com.ehe.iotagent.auth.PrincipalInfo principal) {
        return false;
    }

    @Override
    public AllowedPhone add(long expectedRevision, String mobileNumber, String actor) {
        throw unavailable();
    }

    @Override
    public void remove(long expectedRevision, String mobileNumber, String actor) {
        throw unavailable();
    }

    private BusinessException unavailable() {
        return new BusinessException("ALLOWLIST_UNAVAILABLE", reason + "，白名单管理暂不可用", 503);
    }
}
