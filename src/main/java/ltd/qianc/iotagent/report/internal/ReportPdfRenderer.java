package ltd.qianc.iotagent.report.internal;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

/**
 * Paginated projection of saved report facts with a bundled, subset-embedded
 * Chinese font. Rendering never recalculates the previewed measurements.
 */
public final class ReportPdfRenderer {
    private static final int LINES_PER_PAGE = 44;
    private static final int MAX_LINE_CHARS = 78;

    private ReportPdfRenderer() { }

    public static byte[] render(Map<String, Object> report) {
        try (PDDocument document = new PDDocument();
                InputStream asset = ReportPdfRenderer.class.getResourceAsStream("/reports/NotoSansSC-Regular.ttf");
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (asset == null) throw new IllegalStateException("Report font asset is unavailable");
            PDType0Font font = PDType0Font.load(document, asset, true);
            List<String> content = pageLines(lines(report), font);
            for (int index = 0; index < content.size(); index += LINES_PER_PAGE) {
                PDPage page = new PDPage(PDRectangle.A4);
                document.addPage(page);
                try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                    stream.beginText();
                    stream.setFont(font, 10.5f);
                    stream.setLeading(17);
                    stream.newLineAtOffset(42, 802);
                    int end = Math.min(content.size(), index + LINES_PER_PAGE);
                    for (int line = index; line < end; line++) {
                        if (line > index) stream.newLine();
                        stream.showText(content.get(line));
                    }
                    stream.endText();
                }
            }
            document.save(out);
            return out.toByteArray();
        } catch (Exception error) {
            throw new IllegalStateException("PDF rendering failed", error);
        }
    }

    private static List<String> pageLines(List<String> lines, PDType0Font font) throws java.io.IOException {
        List<String> result = new ArrayList<>();
        for (String source : lines) {
            String line = source.replace('\n', ' ').replace('\r', ' ');
            int start = 0;
            float width = 0;
            for (int offset = 0; offset < line.length();) {
                int end = offset + Character.charCount(line.codePointAt(offset));
                float characterWidth = font.getStringWidth(line.substring(offset, end)) * 10.5f / 1000;
                if (width + characterWidth > 511 && offset > start) {
                    result.add(line.substring(start, offset));
                    start = offset;
                    width = 0;
                }
                width += characterWidth;
                offset = end;
            }
            result.add(line.substring(start));
        }
        return result;
    }

    private static List<String> lines(Map<String, Object> report) {
        List<String> lines = new ArrayList<>();
        lines.add(text(report.get("title"), "报告"));
        lines.add("报告标识：" + text(report.get("id"), ""));
        lines.add("状态：" + text(report.get("status"), "unknown"));
        lines.add("生成时间：" + text(report.get("generatedAt"), ""));
        lines.add("目标：" + text(report.get("objective"), ""));
        if (report.get("contentHash") != null) lines.add("内容标识：" + report.get("contentHash"));
        String summary = text(report.get("summary"), "");
        if (!summary.isBlank()) {
            lines.add("");
            lines.add("摘要");
            lines.addAll(wrap(summary, MAX_LINE_CHARS));
        }
        Object blocks = report.get("blocks");
        if (blocks instanceof List<?> list) {
            for (Object value : list) {
                if (!(value instanceof Map<?, ?> block)) continue;
                String type = text(block.get("type"), "");
                if ("text".equals(type)) {
                    lines.add("");
                    lines.add(text(block.get("title"), "说明"));
                    lines.addAll(wrap(text(block.get("text"), ""), MAX_LINE_CHARS));
                } else if ("meter_report".equals(type) && block.get("report") instanceof Map<?, ?> fact) {
                    lines.add("");
                    lines.addAll(meterLines(fact));
                } else if ("ac_inspection".equals(type)) {
                    lines.add("");
                    lines.addAll(acInspectionLines(block));
                } else if ("safety_ledger".equals(type)) {
                    lines.add("");
                    lines.add("安全台账：" + text(block.get("ledger"), ""));
                    Object events = block.get("events");
                    lines.add("记录数：" + (events instanceof List<?> rows ? rows.size() : 0));
                    if (events instanceof List<?> rows) {
                        for (Object row : rows) {
                            if (!(row instanceof Map<?, ?> event)) continue;
                            lines.add("- " + text(event.get("ledger"), text(block.get("ledger"), ""))
                                    + " / " + text(event.get("deviceName"), text(event.get("deviceId"), ""))
                                    + " / " + text(event.get("occurredAt"), "") + " / "
                                    + (Boolean.TRUE.equals(event.get("alarm")) ? "告警" : "记录"));
                        }
                    }
                }
            }
        }
        Object coverage = report.get("coverage");
        if (coverage instanceof Map<?, ?> map) {
            lines.add("");
            lines.add("覆盖与缺失：" + text(map.get("missing"), "无显式缺失"));
            Object meters = map.get("meters");
            Object dataMeters = map.get("dataMeters");
            if (meters != null || dataMeters != null) {
                lines.add("计量范围：" + text(meters, "") + " 台，取得有效数据 " + text(dataMeters, "") + " 台");
            }
        }
        lines.add("");
        lines.add("说明：本报告由服务端根据当前授权计量范围和已取得采样生成；缺失边界不以零补齐。");
        return lines;
    }

    private static List<String> meterLines(Map<?, ?> fact) {
        List<String> lines = new ArrayList<>();
        String kind = text(fact.get("kind"), "");
        lines.add("meter_report".equals(kind) ? "用电统计" : "用电报告");
        Map<?, ?> params = fact.get("params") instanceof Map<?, ?> map ? map : Map.of();
        Object span = params.get("span");
        if (span instanceof Map<?, ?> spanMap && spanMap.get("requested") instanceof Map<?, ?> requested) {
            lines.add("期间：" + text(requested.get("from"), "") + " 至 " + text(requested.get("to"), ""));
        }
        lines.addAll(MeterExportEvidence.notes(fact));
        Object total = fact.get("total");
        if (total instanceof Map<?, ?> totalMap) {
            lines.add("总量：" + text(totalMap.get("value"), "—") + " " + text(totalMap.get("unit"), "度"));
        }
        Object diff = fact.get("diff");
        if (diff instanceof Map<?, ?> diffMap) {
            lines.add("增减：" + text(diffMap.get("value"), "—") + " " + text(diffMap.get("unit"), "度")
                    + "（" + text(diffMap.get("pct"), "—") + "%）"
                    + (diffMap.get("pctNote") == null ? "" : " " + diffMap.get("pctNote")));
        }
        Object meters = fact.get("meters");
        if (meters instanceof List<?> rows) {
            lines.add("逐表依据：");
            for (Object value : rows) {
                if (!(value instanceof Map<?, ?> meter)) continue;
                Map<?, ?> coverage = meter.get("coverage") instanceof Map<?, ?> map ? map : null;
                Object meterValue = meter.get("value");
                if (meterValue == null && meter.get("a") instanceof Map<?, ?> a) meterValue = a.get("value");
                Object status = meter.get("status");
                if (status == null && meter.get("a") instanceof Map<?, ?> a) status = a.get("status");
                lines.add("- " + text(meter.get("name"), text(meter.get("id"), ""))
                        + " / " + text(meter.get("areaName"), "")
                        + " / " + text(meterValue, "—") + " 度 / " + text(status, ""));
                String currentSamples = MeterExportEvidence.sampleLine(meter, "a");
                if (!currentSamples.isEmpty()) lines.add(currentSamples);
                String baselineSamples = MeterExportEvidence.sampleLine(meter, "b");
                if (!baselineSamples.isEmpty()) lines.add(baselineSamples);
                if (coverage != null) {
                    lines.add("  覆盖 " + text(coverage.get("dataMeters"), "") + "/" + text(coverage.get("meters"), "")
                            + (Boolean.TRUE.equals(coverage.get("truncated")) ? "（读取截断）" : ""));
                }
            }
        }
        Object coverage = fact.get("coverage");
        if (coverage instanceof Map<?, ?> map) {
            lines.add("覆盖：表 " + text(map.get("meters"), "") + " 台，有效 " + text(map.get("dataMeters"), ""));
            Object noData = map.get("noData");
            Object partial = map.get("partial");
            if (noData instanceof List<?> list && !list.isEmpty()) lines.add("缺失表：" + String.join("、", list.stream().map(String::valueOf).toList()));
            if (partial instanceof List<?> list && !list.isEmpty()) lines.add("部分表：" + list.size() + " 台，详见明细");
            if (Boolean.TRUE.equals(map.get("truncated"))) lines.add("读取预算不足：仅保留已取得采样，不补零。");
        }
        return lines;
    }

    /** Human-readable AC inspection projection: command facts stay commands, readback is labeled, estimates are never measured savings. */
    private static List<String> acInspectionLines(Map<?, ?> block) {
        List<String> lines = new ArrayList<>();
        boolean adjust = "adjust_to_26".equals(text(block.get("mode"), ""));
        Object target = acTarget(block);
        lines.add(adjust ? "空调调温检查" : "空调巡检");
        lines.add("检查时间：" + text(block.get("at"), ""));
        lines.add("检查方式：" + (adjust
                ? "符合条件时调整到目标 " + text(target, "26") + "℃（只调整开机、制冷且设定温度更低的空调）"
                : "只读检查，不调整空调"));
        String phaseOutcome = acPhaseOutcome(block);
        if (!phaseOutcome.isBlank()) lines.add("检查点结果：" + phaseOutcome);
        Object devices = block.get("devices");
        if (devices instanceof List<?> rows && !rows.isEmpty()) {
            lines.add("设备检查结果：");
            for (Object value : rows) {
                if (!(value instanceof Map<?, ?> device)) continue;
                StringBuilder row = new StringBuilder("- " + text(device.get("name"), text(device.get("deviceId"), "未知设备")));
                String location = text(device.get("location"), "");
                if (!location.isBlank()) row.append("（").append(location).append("）");
                String state = acStateText(device);
                if (!state.isBlank()) row.append(" / 检查前：").append(state);
                row.append(" / ").append(acDispositionText(device, target));
                String readback = acReadbackText(device);
                if (!readback.isBlank()) row.append(" / ").append(readback);
                lines.addAll(wrap(row.toString(), MAX_LINE_CHARS));
                String reason = text(device.get("reason"), "");
                if (!reason.isBlank()) lines.addAll(wrap("  说明：" + reason, MAX_LINE_CHARS));
            }
        } else {
            lines.add("设备检查结果：本检查点没有取得设备结果");
        }
        if (block.get("energyEstimate") instanceof Map<?, ?> estimate) {
            lines.addAll(acEstimateLines(estimate));
        }
        return lines;
    }

    private static Object acTarget(Map<?, ?> block) {
        if (block.get("criteria") instanceof Map<?, ?> criteria
                && criteria.get("setpoint") instanceof Map<?, ?> setpoint
                && setpoint.get("value") != null) {
            return setpoint.get("value");
        }
        return block.get("target");
    }

    private static String acPhaseOutcome(Map<?, ?> block) {
        String outcome = text(block.get("outcome"), text(block.get("status"), ""));
        return switch (outcome) {
            case "" -> "";
            case "COMPLETED" -> "完成";
            case "FAILED" -> "失败";
            case "PARTIAL" -> "部分完成";
            case "INTERRUPTED" -> "中断";
            case "MISSED" -> "错过";
            case "SKIPPED" -> "跳过";
            default -> outcome;
        };
    }

    private static String acStateText(Map<?, ?> device) {
        List<String> parts = new ArrayList<>();
        Object power = device.get("power");
        if ("on".equals(power)) parts.add("开机");
        else if ("off".equals(power)) parts.add("关机");
        else if (power != null) parts.add("电源" + power);
        Object mode = device.get("mode");
        if (mode != null) parts.add(switch (String.valueOf(mode)) {
            case "cool" -> "制冷";
            case "heat" -> "制热";
            case "dry" -> "除湿";
            case "fan" -> "送风";
            case "auto" -> "自动";
            default -> String.valueOf(mode);
        });
        if (device.get("setpoint") instanceof Number setpoint) parts.add("设定 " + setpoint + "℃");
        return String.join(" · ", parts);
    }

    private static String acDispositionText(Map<?, ?> device, Object target) {
        String disposition = text(device.get("disposition"), "");
        return switch (disposition) {
            case "adjusted" -> target instanceof Number
                    ? "已下发调到" + target + "℃" : "已下发调温指令";
            case "unconfirmed" -> "已下发，未确认回读";
            case "not_dispatched" -> "这次未调整";
            case "not_needed" -> "无需调整";
            case "adjustable" -> "可调";
            case "unknown" -> "已下发";
            default -> "状态未知";
        };
    }

    private static String acReadbackText(Map<?, ?> device) {
        return device.get("afterSetpoint") instanceof Number value ? "平台回读 " + value + "℃" : "";
    }

    private static List<String> acEstimateLines(Map<?, ?> estimate) {
        List<String> lines = new ArrayList<>();
        StringBuilder headline = new StringBuilder("节电估算（估算值，非电表实测）");
        if (estimate.get("value") instanceof Number value) {
            headline.append("：约 ").append(value).append(" ").append(text(estimate.get("unit"), "度"));
        }
        if (estimate.get("costValue") instanceof Number cost) {
            headline.append("，折合电费约 ").append(cost).append(" ").append(text(estimate.get("costUnit"), "元"));
        }
        lines.add(headline.toString());
        List<String> parts = new ArrayList<>();
        Map<?, ?> assumptions = estimate.get("assumptions") instanceof Map<?, ?> map ? map : Map.of();
        if (assumptions.get("ratePerDegree") instanceof Number rate) {
            parts.add("每升高 1℃ 约节电 " + percent(rate) + "%");
        }
        if (assumptions.get("powerKw") instanceof Number power) parts.add("单台功率 " + power + "kW");
        if (assumptions.get("pricePerKwh") instanceof Number price) parts.add("电价 " + price + " 元/度");
        Map<?, ?> baseline = estimate.get("baseline") instanceof Map<?, ?> map ? map : Map.of();
        if (baseline.get("averageSetpoint") instanceof Number average) parts.add("调前平均设定 " + average + "℃");
        if (baseline.get("target") instanceof Number target) parts.add("统一目标 " + target + "℃");
        if (baseline.get("averageRise") instanceof Number rise) parts.add("平均调升 " + rise + "℃");
        Map<?, ?> coverage = estimate.get("coverage") instanceof Map<?, ?> map ? map : Map.of();
        if (coverage.get("adjusted") instanceof Number adjusted) parts.add("实际调节 " + adjusted + " 台");
        if (coverage.get("estimated") instanceof Number estimated) parts.add("参与估算 " + estimated + " 台");
        if (!parts.isEmpty()) lines.addAll(wrap("估算假设：" + String.join("；", parts), MAX_LINE_CHARS));
        String calculation = text(estimate.get("calculation"), "");
        if (!calculation.isBlank()) lines.add("计算式：" + calculation);
        String reason = text(estimate.get("reason"), "");
        if (!reason.isBlank()) lines.add("口径说明：" + reason);
        if (Boolean.TRUE.equals(estimate.get("potential"))) {
            lines.add("本估算为可调节潜力，设备未实际调节。");
        }
        return lines;
    }

    private static String percent(Number rate) {
        double value = Math.round(rate.doubleValue() * 1000) / 10.0;
        return value == Math.floor(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    private static List<String> wrap(String value, int width) {
        List<String> result = new ArrayList<>();
        if (value == null || value.isBlank()) return result;
        String remaining = value.trim();
        while (remaining.length() > width) {
            int cut = remaining.lastIndexOf(' ', width);
            if (cut < width / 2) cut = width;
            result.add(remaining.substring(0, cut));
            remaining = remaining.substring(cut).trim();
        }
        if (!remaining.isEmpty()) result.add(remaining);
        return result;
    }


    private static String text(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }
}
