package ltd.qianc.iotagent.error;

/**
 * 可对用户公开的业务失败事实：{@code code}/{@code message}/{@code recovery} 就是最终响应体，
 * 因此不得携带任何内部细节（上游原文、cause 文本、SQL 片段）。
 * 对齐 runtime `apps/runtime/src/business-error.ts` 的 BusinessError 约定。
 */
public class BusinessException extends RuntimeException {

    private final String code;
    private final Object recovery;
    private final int httpStatus;

    public BusinessException(String code, String message) {
        this(code, message, (String) null, 400);
    }

    public BusinessException(String code, String message, int httpStatus) {
        this(code, message, (String) null, httpStatus);
    }

    public BusinessException(String code, String message, String recovery, int httpStatus) {
        this(code, message, (Object) recovery, httpStatus);
    }

    public BusinessException(String code, String message, java.util.Map<String, Object> recovery, int httpStatus) {
        this(code, message, (Object) recovery, httpStatus);
    }

    private BusinessException(String code, String message, Object recovery, int httpStatus) {
        super(message);
        this.code = code;
        this.recovery = recovery;
        this.httpStatus = httpStatus;
    }

    public String code() {
        return code;
    }

    public Object recovery() {
        return recovery;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
