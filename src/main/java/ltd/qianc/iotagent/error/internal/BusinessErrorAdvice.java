package ltd.qianc.iotagent.error.internal;

import ltd.qianc.iotagent.error.BusinessException;
import ltd.qianc.iotagent.error.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 未分类失败对用户只说「原因未确认」；日志只留异常类型与栈帧位置，
 * 绝不输出上游原文或 cause 文本（对齐 runtime failureDiagnostic 的脱敏纪律）。
 * 各业务域的确定性失败映射（超时/网络错误码/上游业务码 → 中文文案）随各域移植时补充。
 */
@RestControllerAdvice
public class BusinessErrorAdvice {

    private static final Logger log = LoggerFactory.getLogger(BusinessErrorAdvice.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> onBusiness(BusinessException e) {
        return ResponseEntity.status(e.httpStatus())
                .body(new ErrorResponse(e.code(), e.getMessage(), e.recovery()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> onUnexpected(Exception e) {
        log.error("未分类失败 type={} at {}", e.getClass().getName(), topFrames(e));
        return ResponseEntity.status(500).body(new ErrorResponse("INTERNAL", "原因未确认", null));
    }

    private static String topFrames(Throwable e) {
        StackTraceElement[] frames = e.getStackTrace();
        StringBuilder positions = new StringBuilder();
        for (int i = 0; i < Math.min(3, frames.length); i++) {
            if (i > 0) {
                positions.append(", ");
            }
            positions.append(frames[i].getClassName()).append('#').append(frames[i].getMethodName());
        }
        return positions.toString();
    }
}
