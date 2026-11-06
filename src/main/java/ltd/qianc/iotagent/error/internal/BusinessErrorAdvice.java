package ltd.qianc.iotagent.error.internal;

import ltd.qianc.iotagent.error.BusinessException;
import ltd.qianc.iotagent.error.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

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

    /** Request decoding and binding failures are client errors, never internal failures. */
    @ExceptionHandler({
            org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
            org.springframework.web.bind.MethodArgumentNotValidException.class
    })
    public ResponseEntity<ErrorResponse> onInvalidRequest(Exception ignored) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("INVALID_REQUEST", "请求参数格式无效", null));
    }

    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<Map<String, Object>> onUnknownRoute(HttpServletRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "Route " + request.getMethod() + ":" + request.getRequestURI() + " not found");
        body.put("error", "Not Found");
        body.put("statusCode", 404);
        return ResponseEntity.status(404).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> onUnexpected(Exception e) {
        if (e instanceof org.springframework.web.ErrorResponse framework) {
            var response = ResponseEntity.status(framework.getStatusCode()).headers(framework.getHeaders());
            // A 406 cannot carry a JSON error when the caller rejects that representation.
            return response.body(framework.getStatusCode().value() == 406 ? null
                    : new ErrorResponse("HTTP_" + framework.getStatusCode().value(), "请求无法处理", null));
        }
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
