package com.ehe.iotagent.iot;

import com.ehe.iotagent.error.BusinessException;

/** Upstream 401/403; never replaced by an old scope or a local identity. */
public final class GatewayAuthorizationException extends BusinessException {

    private final int upstreamStatus;

    public GatewayAuthorizationException(int upstreamStatus) {
        super(codeFor(upstreamStatus), messageFor(upstreamStatus), "请重新登录", upstreamStatus);
        if (upstreamStatus != 401 && upstreamStatus != 403) {
            throw new IllegalArgumentException("Only upstream 401/403 are authorization failures");
        }
        this.upstreamStatus = upstreamStatus;
    }

    public int upstreamStatus() {
        return upstreamStatus;
    }

    private static String codeFor(int status) {
        return status == 401 ? "IOT_GATEWAY_UNAUTHORIZED" : "IOT_GATEWAY_FORBIDDEN";
    }

    private static String messageFor(int status) {
        return status == 401 ? "登录凭据已失效或上游拒绝当前身份，请重新登录"
                : "当前身份无权访问物联设备范围，请联系管理员";
    }
}
