package com.ehe.iotagent.auth.internal;

import com.ehe.iotagent.auth.AccountPrincipalVerifier;
import com.ehe.iotagent.auth.PrincipalInfo;
import com.ehe.iotagent.error.BusinessException;

/** No local identity or token-derived stable principal is accepted as production verification. */
public final class FailClosedAccountPrincipalVerifier implements AccountPrincipalVerifier {
    private final String reason;

    public FailClosedAccountPrincipalVerifier(String reason) {
        this.reason = reason;
    }

    @Override
    public PrincipalInfo verify(PrincipalInfo presented) {
        throw new BusinessException("ACCOUNT_IDENTITY_UNAVAILABLE", reason,
                "请稍后重试", 503);
    }
}
