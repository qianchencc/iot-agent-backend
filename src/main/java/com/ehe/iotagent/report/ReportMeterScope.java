package com.ehe.iotagent.report;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import com.ehe.iotagent.error.BusinessException;
import com.ehe.iotagent.iot.AuthorizedDevice;
import com.ehe.iotagent.iot.AuthorizedDeviceScope;

/** One authorized scope interpretation for report preparation and confirmed task membership. */
public record ReportMeterScope(String level, List<String> meterIds, List<String> allMeterIds) {
    private static final Set<String> KEYS = Set.of("level", "floor", "text", "meterId", "meterIds");
    private static final Pattern FLOOR = Pattern.compile("^(\\d+楼)");

    public static ReportMeterScope resolve(AuthorizedDeviceScope authorized, Object raw) {
        List<AuthorizedDevice> all = authorized.devices().stream()
                .filter(device -> "energy".equalsIgnoreCase(device.kind())).toList();
        List<String> allIds = all.stream().map(AuthorizedDevice::id).toList();
        Map<String, Object> request = new LinkedHashMap<>();
        if (raw == null) request.put("level", "all");
        else if (raw instanceof Map<?, ?> values) {
            values.forEach((key, value) -> request.put(String.valueOf(key), value));
        } else throw new BusinessException("INVALID_REPORT", "报告范围无效");
        for (String key : request.keySet()) {
            if (!KEYS.contains(key)) throw new BusinessException("INVALID_REPORT", "报告范围包含未知字段");
        }
        String level = text(request.get("level"));
        if (level == null) level = "all";
        List<AuthorizedDevice> selected;
        switch (level) {
            case "all" -> selected = all;
            case "floor" -> {
                String floor = text(request.get("floor"));
                if (floor == null) throw new BusinessException("INVALID_REPORT", "楼层报告范围缺少楼层");
                selected = all.stream().filter(device -> floor.equals(floorOf(device.installAddress()))).toList();
            }
            case "location" -> {
                String id = text(request.get("meterId"));
                AuthorizedDevice device = id == null ? null : all.stream()
                        .filter(item -> item.id().equals(id)).findFirst().orElse(null);
                if (device == null) throw new BusinessException("REPORT_SCOPE_CHANGED", "报告引用的电表不在当前授权范围内", 404);
                selected = List.of(device);
            }
            case "nameMatch" -> {
                String query = text(request.get("text"));
                if (query == null) throw new BusinessException("INVALID_REPORT", "名称匹配范围缺少文本");
                String floor = text(request.get("floor"));
                selected = all.stream().filter(device -> (floor == null || floor.equals(floorOf(device.installAddress())))
                        && (device.installAddress() + " " + device.name()).contains(query)).toList();
            }
            case "selection" -> {
                List<String> ids = new ArrayList<>();
                if (request.get("meterIds") instanceof List<?> values) {
                    for (Object value : values) {
                        String id = text(value);
                        if (id != null) ids.add(id);
                    }
                }
                if (ids.isEmpty()) throw new BusinessException("INVALID_REPORT", "选择范围缺少电表");
                LinkedHashSet<String> unique = new LinkedHashSet<>(ids);
                selected = unique.stream().map(id -> all.stream()
                        .filter(item -> item.id().equals(id)).findFirst().orElse(null)).toList();
                if (selected.stream().anyMatch(Objects::isNull)) {
                    throw new BusinessException("REPORT_SCOPE_CHANGED", "报告引用的电表不在当前授权范围内", 404);
                }
            }
            default -> throw new BusinessException("INVALID_REPORT", "报告范围层级无效");
        }
        if (selected.isEmpty()) throw new BusinessException("INVALID_REPORT", "报告范围没有授权电表");
        return new ReportMeterScope(level, selected == all ? allIds : selected.stream().map(AuthorizedDevice::id).toList(), allIds);
    }

    private static String text(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
    }

    private static String floorOf(String address) {
        if (address == null) return null;
        var matcher = FLOOR.matcher(address);
        return matcher.find() ? matcher.group(1) : null;
    }
}
