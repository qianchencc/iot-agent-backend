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
    private static final int MAX_LINE_CHARS = 78;
    private static final java.util.regex.Pattern NUMERIC = java.util.regex.Pattern.compile("-?\\d+(\\.\\d+)?%?");

    private ReportPdfRenderer() { }

    public static byte[] render(Map<String, Object> report) {
        try (PDDocument document = new PDDocument();
                InputStream asset = ReportPdfRenderer.class.getResourceAsStream("/reports/NotoSansSC-Regular.ttf");
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (asset == null) throw new IllegalStateException("Report font asset is unavailable");
            PDType0Font font = PDType0Font.load(document, asset, true);
            document.getDocumentInformation().setTitle(text(report.get("title"), "报告"));
            document.getDocumentCatalog().setLanguage("zh-CN");
            try (Layout layout = new Layout(document, font, text(report.get("title"), "报告"))) {
                layout.heading(text(report.get("title"), "报告"));
                layout.paragraph("生成 " + MeterExportEvidence.time(report.get("generatedAt")) + "（上海时间） · "
                        + MeterExportEvidence.reportStatus(report.get("status")), 10);
                String summary = text(report.get("summary"), "");
                if (!summary.isBlank()) layout.paragraph(MeterExportEvidence.displayText(summary), 11);
                for (String note : MeterExportEvidence.reportNotes(report)) layout.paragraph(MeterExportEvidence.displayText(note), 9);
                var sections = MeterExportEvidence.sections(report);
                var details = MeterExportEvidence.details(sections);
                for (var section : details) {
                    layout.section(section.label());
                    layout.paragraph("本期：" + section.period("a"), 10);
                    if (section.comparison()) layout.paragraph("基期：" + section.period("b"), 10);
                    layout.metrics(section);
                    if (section.comparison()) layout.chart(section);
                    Map<?, ?> coverage = MeterExportEvidence.map(section.fact().get("coverage"));
                    if (!coverage.isEmpty()) layout.paragraph("覆盖：" + text(coverage.get("dataMeters"), "未取得")
                            + " / " + text(coverage.get("meters"), "未取得") + " 台有效；缺失："
                            + MeterExportEvidence.missing(coverage.get("noData")) + "；部分数据："
                            + MeterExportEvidence.missing(coverage.get("partial")), 9);
                }
                for (var section : details) {
                    layout.newPage();
                    layout.heading(section.comparison() ? "逐表用电对比" : "逐表用电明细");
                    layout.paragraph(section.label() + " · 本期 " + section.period("a"), 9);
                    if (section.comparison()) layout.paragraph("基期 " + section.period("b"), 9);
                    List<List<String>> rows = new ArrayList<>();
                    for (Map<?, ?> meter : section.meters()) {
                        Map<?, ?> a = MeterExportEvidence.current(meter, "a"), b = MeterExportEvidence.current(meter, "b");
                        String location = text(meter.get("areaName"), "");
                        rows.add(List.of(text(meter.get("name"), text(meter.get("id"), "未知电表")) + (location.isBlank() ? "" : "\n" + location),
                                MeterExportEvidence.number(a.get("value")), section.comparison() ? MeterExportEvidence.number(b.get("value")) : "—",
                                MeterExportEvidence.number(MeterExportEvidence.change(meter)),
                                MeterExportEvidence.rate(meter) == null ? "—" : MeterExportEvidence.number(MeterExportEvidence.rate(meter)) + "%",
                                "本期 " + MeterExportEvidence.status(a.get("status")) + (section.comparison() ? "\n基期 "
                                        + MeterExportEvidence.status(b.get("status")) + (MeterExportEvidence.comparisonNote(meter).isBlank()
                                                ? "" : "\n" + MeterExportEvidence.comparisonNote(meter)) : "")));
                    }
                    layout.table(List.of("电表 / 位置", "本期(度)", "基期(度)", "用量增减", "增减比例", "数据状态"),
                            new float[]{165, 65, 65, 65, 65, 86}, rows);
                }
                // Keep derived facts even when identical statistics details are represented by a comparison.
                for (var section : sections) {
                    List<Map<?, ?>> metrics = MeterExportEvidence.maps(section.fact().get("metrics"));
                    if (metrics.isEmpty()) continue;
                    layout.ensure(90 + metrics.size() * 56);
                    layout.section(section.label() + " · 指标");
                    List<List<String>> rows = new ArrayList<>();
                    for (Map<?, ?> metric : metrics) {
                        Map<?, ?> a = metric.get("a") instanceof Map<?, ?> side ? side : metric;
                        Map<?, ?> b = MeterExportEvidence.map(metric.get("b"));
                        rows.add(List.of(MeterExportEvidence.metricName(metric.get("key")),
                                MeterExportEvidence.number(a.get("value")), MeterExportEvidence.number(b.get("value")),
                                text(metric.get("unit"), ""), MeterExportEvidence.metricPeriod(a)
                                        + (MeterExportEvidence.metricPeriod(b).isBlank() ? "" : "\n基期 " + MeterExportEvidence.metricPeriod(b))));
                    }
                    layout.table(List.of("指标", "本期", "基期", "单位", "时段"), new float[]{145, 85, 85, 76, 120}, rows);
                }
                for (Map<?, ?> block : MeterExportEvidence.maps(report.get("blocks"))) {
                    String type = text(block.get("type"), "");
                    if ("text".equals(type)) {
                        layout.section(text(block.get("title"), "说明"));
                        layout.paragraph(text(block.get("text"), ""), 10);
                    } else if ("ac_inspection".equals(type)) {
                        layout.section("空调检查");
                        for (String line : acInspectionLines(block)) layout.paragraph(line, 10);
                    } else if ("safety_ledger".equals(type)) {
                        layout.section("安全台账 · " + text(block.get("ledger"), ""));
                        List<List<String>> rows = new ArrayList<>();
                        for (Map<?, ?> event : MeterExportEvidence.maps(block.get("events"))) rows.add(List.of(
                                text(event.get("deviceName"), text(event.get("deviceId"), "")),
                                MeterExportEvidence.time(event.get("occurredAt")), Boolean.TRUE.equals(event.get("alarm")) ? "告警" : "记录"));
                        layout.table(List.of("设备", "发生时间（上海时间）", "类型"), new float[]{240, 180, 91}, rows);
                    }
                }
                if (!details.isEmpty()) {
                    layout.newPage();
                    layout.heading("附录 · 采样依据");
                    layout.paragraph("累计示数用于追溯本报告，缺失值不补零。全部时间按上海时间展示。", 10);
                    for (var section : details) {
                        layout.section(section.label());
                        List<List<String>> rows = new ArrayList<>();
                        for (Map<?, ?> meter : section.meters()) {
                            for (String side : section.comparison() ? List.of("a", "b") : List.of("a")) {
                                List<Object> sample = MeterExportEvidence.samples(meter, side);
                                rows.add(List.of(text(meter.get("name"), text(meter.get("id"), "")),
                                        "a".equals(side) ? "本期" : "基期",
                                        MeterExportEvidence.time(sample.get(0)) + "\n" + MeterExportEvidence.number(sample.get(1)),
                                        MeterExportEvidence.time(sample.get(2)) + "\n" + MeterExportEvidence.number(sample.get(3))));
                            }
                        }
                        layout.table(List.of("电表", "期间", "起始采样时间 / 累计示数", "结束采样时间 / 累计示数"),
                                new float[]{145, 42, 162, 162}, rows);
                    }
                }
                layout.section("报告信息");
                layout.paragraph("报告标识：" + text(report.get("id"), ""), 9);
                if (report.get("objective") != null) layout.paragraph("报告目标：" + report.get("objective"), 9);
                if (report.get("contentHash") != null) layout.paragraph("内容标识：" + report.get("contentHash"), 8);
                layout.paragraph("本报告使用生成时保存的授权范围与采样事实，重新下载不会重取或改写历史数据。", 9);
            }
            for (int i = 0; i < document.getNumberOfPages(); i++) {
                try (var stream = new PDPageContentStream(document, document.getPage(i),
                        PDPageContentStream.AppendMode.APPEND, true, true)) {
                    stream.beginText(); stream.setFont(font, 8); stream.newLineAtOffset(454, 23);
                    stream.showText("第 " + (i + 1) + " / " + document.getNumberOfPages() + " 页"); stream.endText();
                }
            }
            document.save(out);
            return out.toByteArray();
        } catch (Exception error) {
            throw new IllegalStateException("PDF rendering failed", error);
        }
    }

    private static final class Layout implements AutoCloseable {
        private static final java.awt.Color INK = new java.awt.Color(32, 49, 62);
        private static final java.awt.Color ACCENT = new java.awt.Color(40, 77, 105);
        private static final java.awt.Color MUTED = new java.awt.Color(81, 96, 108);
        private final PDDocument document;
        private final PDType0Font font;
        private final String title;
        private final Map<Integer, Float> widths = new java.util.HashMap<>();
        private PDPageContentStream stream;
        private float y;

        Layout(PDDocument document, PDType0Font font, String title) throws java.io.IOException {
            this.document = document; this.font = font; this.title = title; newPage();
        }
        void newPage() throws java.io.IOException {
            if (stream != null) stream.close();
            PDPage page = new PDPage(PDRectangle.A4); document.addPage(page);
            stream = new PDPageContentStream(document, page);
            y = 779;
            text(title, 42, 813, 8, MUTED);
            stream.setStrokingColor(ACCENT); stream.setLineWidth(1); stream.moveTo(42, 800); stream.lineTo(553, 800); stream.stroke();
        }
        void ensure(float height) throws java.io.IOException { if (y - height < 48) newPage(); }
        void heading(String value) throws java.io.IOException { paragraph(value, 22); y -= 10; }
        void section(String value) throws java.io.IOException { ensure(70); y -= 12; paragraph(value, 14); y -= 4; }
        void paragraph(String value, float size) throws java.io.IOException {
            for (String line : wrapText(value, size, 511)) {
                ensure(size * 1.65f);
                text(line, 42, y, size, size < 10 ? MUTED : INK);
                y -= size * 1.65f;
            }
            y -= 5;
        }
        void metrics(MeterExportEvidence.Section section) throws java.io.IOException {
            ensure(86);
            text("本期总量", 42, y, 9, MUTED);
            Map<?, ?> coverage = MeterExportEvidence.map(section.fact().get("coverage"));
            text(section.comparison() ? "基期总量" : "计量范围（台）", 217, y, 9, MUTED);
            text(section.comparison() ? "增减（" + ReportPdfRenderer.text(section.diff().get("unit"), "度") + "）" : "有效电表（台）", 392, y, 9, MUTED);
            y -= 29;
            text(MeterExportEvidence.number(section.total("a")), 42, y, 23, ACCENT);
            text(MeterExportEvidence.number(section.comparison() ? section.total("b") : coverage.get("meters")), 217, y, 23, ACCENT);
            text(MeterExportEvidence.number(section.comparison() ? section.diff().get("value") : coverage.get("dataMeters")), 392, y, 23, ACCENT);
            y -= 23;
            String ratio = MeterExportEvidence.number(section.diff().get("pct"));
            paragraph(section.comparison() ? "增减比例：" + ratio + ("—".equals(ratio) ? "" : "%") + "；"
                    + ("dailyAvg".equals(section.diff().get("basis")) ? "两期时长不同，按日均比较。" : "按总用量比较。")
                    + ReportPdfRenderer.text(section.diff().get("pctNote"), "") : "用量单位：度", 10);
        }
        void chart(MeterExportEvidence.Section section) throws java.io.IOException {
            if (!(section.total("a") instanceof Number a) || !(section.total("b") instanceof Number b)
                    || !(section.diff().get("value") instanceof Number) || a.doubleValue() < 0 || b.doubleValue() < 0) return;
            ensure(110);
            text("两期总用电量（度）", 42, y, 10, INK); y -= 26;
            double max = Math.max(a.doubleValue(), b.doubleValue());
            Number[] values = {a, b};
            String[] labels = {"本期", "基期"};
            for (int i = 0; i < 2; i++) {
                text(labels[i], 42, y, 10, INK);
                stream.setNonStrokingColor(i == 0 ? ACCENT : new java.awt.Color(111, 138, 156));
                stream.addRect(84, y - 3, max == 0 ? 0 : (float) (values[i].doubleValue() / max * 350), 15); stream.fill();
                text(MeterExportEvidence.number(values[i]), 445, y, 10, INK);
                y -= 30;
            }
            y -= 10;
        }
        void table(List<String> header, float[] columns, List<List<String>> rows) throws java.io.IOException {
            tableRow(header, columns, true);
            int index = 0;
            for (List<String> row : rows) {
                float height = rowHeight(row, columns);
                if (y - height < 48) { newPage(); tableRow(header, columns, true); }
                tableRow(row, columns, false);
                index++;
            }
            if (index == 0) paragraph("未取得明细", 10);
            y -= 12;
        }
        float rowHeight(List<String> row, float[] columns) throws java.io.IOException {
            int lines = 1;
            for (int i = 0; i < columns.length; i++) lines = Math.max(lines, wrapText(row.get(i), 9, columns[i] - 12).size());
            return lines * 14 + 14;
        }
        void tableRow(List<String> row, float[] columns, boolean header) throws java.io.IOException {
            float height = rowHeight(row, columns);
            ensure(height);
            if (header) {
                stream.setNonStrokingColor(ACCENT); stream.addRect(42, y - height + 7, 511, height); stream.fill();
            }
            float x = 42;
            for (int i = 0; i < columns.length; i++) {
                float top = y - 7;
                for (String line : wrapText(row.get(i), 9, columns[i] - 12)) {
                    float left = !header && NUMERIC.matcher(line).matches()
                            ? x + columns[i] - 6 - font.getStringWidth(line) * 9 / 1000 : x + 6;
                    text(line, left, top, 9, header ? java.awt.Color.WHITE : INK); top -= 14;
                }
                x += columns[i];
            }
            y -= height;
            stream.setStrokingColor(new java.awt.Color(220, 226, 231)); stream.setLineWidth(0.4f);
            stream.moveTo(42, y + 7); stream.lineTo(553, y + 7); stream.stroke();
        }
        List<String> wrapText(String value, float size, float width) throws java.io.IOException {
            List<String> result = new ArrayList<>();
            for (String source : value.replace('\r', ' ').split("\n", -1)) {
                StringBuilder line = new StringBuilder(); float used = 0;
                for (int offset = 0; offset < source.length();) {
                    int code = source.codePointAt(offset);
                    Float unit = widths.get(code);
                    if (unit == null) { unit = font.getStringWidth(new String(Character.toChars(code))) / 1000; widths.put(code, unit); }
                    float next = unit * size;
                    if (used + next > width && !line.isEmpty()) { result.add(line.toString()); line.setLength(0); used = 0; }
                    line.appendCodePoint(code); used += next; offset += Character.charCount(code);
                }
                result.add(line.toString());
            }
            return result;
        }
        void text(String value, float x, float y, float size, java.awt.Color color) throws java.io.IOException {
            stream.setNonStrokingColor(color); stream.beginText(); stream.setFont(font, size);
            stream.newLineAtOffset(x, y); stream.showText(value); stream.endText();
        }
        @Override public void close() throws java.io.IOException { if (stream != null) stream.close(); }
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
