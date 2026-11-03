package ltd.qianc.iotagent.agent;

import ltd.qianc.iotagent.error.BusinessException;

/** A rejected tool operation that also closes this run's business-tool phase. */
public final class AgentToolHaltException extends BusinessException {
    public AgentToolHaltException(String code, String message) { super(code, message, 409); }
}
