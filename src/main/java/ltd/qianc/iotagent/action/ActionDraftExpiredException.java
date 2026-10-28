package ltd.qianc.iotagent.action;

import ltd.qianc.iotagent.error.BusinessException;

public final class ActionDraftExpiredException extends BusinessException {
    public ActionDraftExpiredException() {
        super("ACTION_DRAFT_EXPIRED", "Action draft expired", "请重新发起操作", 409);
    }
}
