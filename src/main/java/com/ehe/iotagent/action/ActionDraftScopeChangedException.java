package com.ehe.iotagent.action;

import com.ehe.iotagent.error.BusinessException;

public final class ActionDraftScopeChangedException extends BusinessException {
    public ActionDraftScopeChangedException() {
        super("AUTHORIZED_SCOPE_CHANGED", "部分设备已不在当前用户授权范围内", "请重新检索设备后发起操作", 409);
    }
}
