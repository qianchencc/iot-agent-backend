package com.ehe.iotagent.auth;

import java.util.Objects;

/**
 * 过渡期认证上下文；接口形状对齐客户 LocalVariable.getPrincipalInfo()，实现不绑定 ehe-core。
 */
public final class PrincipalContext {

    private static final ThreadLocal<PrincipalInfo> CURRENT = new ThreadLocal<>();

    private PrincipalContext() {
    }

    public static void set(PrincipalInfo principal) {
        CURRENT.set(Objects.requireNonNull(principal, "principal"));
    }

    public static PrincipalInfo getPrincipalInfo() {
        PrincipalInfo principal = CURRENT.get();
        if (principal == null) {
            throw new IllegalStateException("当前请求没有认证上下文");
        }
        return principal;
    }

    public static PrincipalInfo currentOrNull() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
