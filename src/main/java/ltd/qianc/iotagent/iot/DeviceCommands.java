package ltd.qianc.iotagent.iot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Iterator;

/**
 * lighting/access/ac 下发指令的唯一校验与规范化规则（deep module：入口隐藏整数性、
 * 无损 int 转换、取值范围与字段集合，调用方只保留各自的对外错误码）。
 *
 * <p>数值目标必须本身是整数且能无损转成 int：小数（26.5、1.0）、字符串（"26"）与
 * 溢出整数（2^32+26 一类 {@code asInt} 截断后看似合法的值）一律拒绝，校验值与
 * 执行值不再经过第二次有损转换。</p>
 */
public final class DeviceCommands {

    private DeviceCommands() {
    }

    /** 严格整数：非整数或超出 int 范围的整数抛 {@link IllegalArgumentException}。 */
    public static int losslessInt(JsonNode value) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalArgumentException("控制参数必须是可精确表示的整数");
        }
        return value.intValue();
    }

    /** 照明开关目标：严格 0/1。 */
    public static int lightingCloseOrOpen(JsonNode value) {
        int target = losslessInt(value);
        if (target != 0 && target != 1) throw new IllegalArgumentException("照明开关目标只支持 0 或 1");
        return target;
    }

    /** 照明 power 文本目标到 0/1 的唯一换算；非 on/off 拒绝而不是默认关灯。 */
    public static int closeOrOpenFromPower(JsonNode power) {
        String text = power == null ? "" : power.asText("");
        if ("on".equals(text)) return 1;
        if ("off".equals(text)) return 0;
        throw new IllegalArgumentException("照明电源目标只支持 on 或 off");
    }

    /** 门禁指令目标：严格为开门确认（2）。 */
    public static int accessControlType(JsonNode value) {
        int controlType = losslessInt(value);
        if (controlType != 2) throw new IllegalArgumentException("门禁指令仅支持开门确认");
        return controlType;
    }

    /** 空调整度目标：严格 16–30 的整数，范围来自 {@link AcCommandCapabilities}。 */
    public static int acTemperature(JsonNode value) {
        int temperature = losslessInt(value);
        if (temperature < (int) AcCommandCapabilities.TEMPERATURE_MIN
                || temperature > (int) AcCommandCapabilities.TEMPERATURE_MAX) {
            throw new IllegalArgumentException("空调温度仅支持"
                    + (int) AcCommandCapabilities.TEMPERATURE_MIN + "–"
                    + (int) AcCommandCapabilities.TEMPERATURE_MAX + "℃的整数");
        }
        return temperature;
    }

    /**
     * 按设备类型校验整条指令，并把数值字段规范成 int 节点后原样返回同一节点：
     * 通过校验的指令就是下发执行的指令，不再存在第二套转换。违反规则抛
     * {@link IllegalArgumentException}，由调用方映射为自己的对外错误码。
     */
    public static ObjectNode canonical(String kind, JsonNode command) {
        if (command == null || !command.isObject() || command.isEmpty()) {
            throw new IllegalArgumentException("设备指令必须是非空对象");
        }
        ObjectNode object = (ObjectNode) command;
        switch (kind) {
            case "lighting" -> {
                if (object.size() != 1 || !object.has("closeOrOpen")) {
                    throw new IllegalArgumentException("照明指令只支持 closeOrOpen");
                }
                object.put("closeOrOpen", lightingCloseOrOpen(object.path("closeOrOpen")));
            }
            case "access" -> {
                if (object.size() != 1 || !object.has("controlType")) {
                    throw new IllegalArgumentException("门禁指令只支持 controlType");
                }
                object.put("controlType", accessControlType(object.path("controlType")));
            }
            case "ac" -> {
                for (Iterator<String> names = object.fieldNames(); names.hasNext(); ) {
                    String name = names.next();
                    switch (name) {
                        case "power" -> {
                            if (!object.path(name).isTextual()
                                    || !AcCommandCapabilities.POWER.contains(object.path(name).textValue())) {
                                throw new IllegalArgumentException("空调电源参数无效");
                            }
                        }
                        case "mode" -> {
                            if (!object.path(name).isTextual()
                                    || !AcCommandCapabilities.MODE_SET.contains(object.path(name).textValue())) {
                                throw new IllegalArgumentException("空调模式参数无效");
                            }
                        }
                        case "fanSpeed" -> {
                            if (!object.path(name).isTextual()
                                    || !AcCommandCapabilities.FAN_SPEED_SET.contains(object.path(name).textValue())) {
                                throw new IllegalArgumentException("空调风速参数无效");
                            }
                        }
                        case "temperature" -> object.put("temperature", acTemperature(object.path("temperature")));
                        default -> throw new IllegalArgumentException("空调指令包含不支持的字段");
                    }
                }
                if ("off".equals(object.path("power").asText(null)) && object.size() > 1) {
                    throw new IllegalArgumentException("关闭空调时不能同时设置其他参数");
                }
            }
            default -> throw new IllegalArgumentException("设备类型不可控");
        }
        return object;
    }
}
