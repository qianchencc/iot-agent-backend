package ltd.qianc.iotagent.error;

import java.math.BigInteger;
import java.util.Map;

/**
 * HTTP 请求体标量参数的严格解析入口（revision/version/source 等）。
 *
 * <p>规则：可缺省字段只在键确实缺失（{@code containsKey == false}）时才使用默认值；
 * 显式 null、字符串、小数（含 JSON 的 {@code 1.0}/{@code 1e0}）与溢出整数一律按
 * 参数错误拒绝，绝不做 {@code longValue()}/{@code intValue()} 一类的静默截断。
 * 调用方传入自己的错误码与文案，外部契约仍由各入口保留。</p>
 */
public final class RequestValues {

    private RequestValues() {
    }

    /** 必需数值参数：键缺失或值非法都抛调用方给定的参数错误。 */
    public static long requiredLong(Map<String, Object> body, String key, long min,
                                    String code, String message, int httpStatus) {
        Long value = optionalLong(body, key, min, code, message, httpStatus);
        if (value == null) throw new BusinessException(code, message, httpStatus);
        return value;
    }

    /** 可缺省数值参数：仅键缺失返回 null；键存在（含显式 null）即必须是范围内的无损整数。 */
    public static Long optionalLong(Map<String, Object> body, String key, long min,
                                    String code, String message, int httpStatus) {
        if (body == null || !body.containsKey(key)) return null;
        return presentLong(body.get(key), min, code, message, httpStatus);
    }

    /** 可缺省文本参数：仅键缺失用默认值；键存在（含显式 null）必须是字符串。 */
    public static String optionalText(Map<String, Object> body, String key, String fallback,
                                      String code, String message, int httpStatus) {
        if (body == null || !body.containsKey(key)) return fallback;
        Object value = body.get(key);
        if (!(value instanceof String text)) throw new BusinessException(code, message, httpStatus);
        return text;
    }

    private static long presentLong(Object value, long min, String code, String message, int httpStatus) {
        if (value == null || !(value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof BigInteger)) {
            throw new BusinessException(code, message, httpStatus);
        }
        long parsed;
        if (value instanceof BigInteger big) {
            try {
                parsed = big.longValueExact();
            } catch (ArithmeticException overflow) {
                throw new BusinessException(code, message, httpStatus);
            }
        } else {
            parsed = ((Number) value).longValue();
        }
        if (parsed < min) throw new BusinessException(code, message, httpStatus);
        return parsed;
    }
}
