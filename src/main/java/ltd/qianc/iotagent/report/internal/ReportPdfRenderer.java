package ltd.qianc.iotagent.report.internal;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Dependency-free, paginated PDF projection of saved report facts.
 *
 * <p>Text is written through the predefined Adobe STSong-Light UniGB-UCS2-H
 * CMap so Chinese content remains readable without bundling a font binary. The
 * renderer never recalculates measurements; it only projects the immutable saved
 * report map that was previewed to the user.</p>
 */
public final class ReportPdfRenderer {
    private static final int LINES_PER_PAGE = 44;
    private static final int MAX_LINE_CHARS = 78;

    private ReportPdfRenderer() { }

    public static byte[] render(Map<String, Object> report) {
        List<String> lines = lines(report);
        List<List<String>> pages = new ArrayList<>();
        for (int index = 0; index < lines.size(); index += LINES_PER_PAGE) {
            pages.add(lines.subList(index, Math.min(lines.size(), index + LINES_PER_PAGE)));
        }
        if (pages.isEmpty()) pages.add(List.of("报告"));
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            List<String> objects = new ArrayList<>();
            int pageCount = pages.size();
            int fontId = 3 + 2 * pageCount;
            int descendantId = fontId + 1;
            StringBuilder kids = new StringBuilder();
            for (int index = 0; index < pageCount; index++) {
                if (index > 0) kids.append(' ');
                kids.append(3 + index).append(" 0 R");
            }
            objects.add("<< /Type /Catalog /Pages 2 0 R >>");
            objects.add("<< /Type /Pages /Kids [" + kids + "] /Count " + pageCount + " >>");
            for (int index = 0; index < pageCount; index++) {
                int contentId = 3 + pageCount + index;
                objects.add("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] "
                        + "/Resources << /Font << /F1 " + fontId + " 0 R >> >> /Contents " + contentId + " 0 R >>");
            }
            for (List<String> page : pages) {
                String content = content(page);
                objects.add("<< /Length " + content.getBytes(StandardCharsets.US_ASCII).length
                        + " >>\nstream\n" + content + "\nendstream");
            }
            objects.add("<< /Type /Font /Subtype /Type0 /BaseFont /STSong-Light "
                    + "/Encoding /UniGB-UCS2-H /DescendantFonts [" + descendantId + " 0 R] >>");
            objects.add("<< /Type /Font /Subtype /CIDFontType0 /BaseFont /STSong-Light "
                    + "/CIDSystemInfo << /Registry (Adobe) /Ordering (GB1) /Supplement 2 >> >>");

            write(out, "%PDF-1.4\n%\u00e2\u00e3\u00cf\u00d3\n");
            List<Integer> offsets = new ArrayList<>();
            for (int index = 0; index < objects.size(); index++) {
                offsets.add(out.size());
                write(out, (index + 1) + " 0 obj\n" + objects.get(index) + "\nendobj\n");
            }
            int xref = out.size();
            write(out, "xref\n0 " + (objects.size() + 1) + "\n0000000000 65535 f \n");
            for (int offset : offsets) write(out, String.format("%010d 00000 n \n", offset));
            write(out, "trailer\n<< /Size " + (objects.size() + 1) + " /Root 1 0 R >>\nstartxref\n" + xref + "\n%%EOF\n");
            return out.toByteArray();
        } catch (Exception error) {
            throw new IllegalStateException("PDF rendering failed", error);
        }
    }

    private static String content(List<String> page) {
        StringBuilder stream = new StringBuilder("BT\n/F1 10.5 Tf\n1 0 0 1 42 802 Tm\n");
        for (int index = 0; index < page.size(); index++) {
            if (index > 0) stream.append("0 -17 Td\n");
            stream.append('<').append(utf16Hex(clip(page.get(index)))).append("> Tj\n");
        }
        return stream.append("ET").toString();
    }

    private static String utf16Hex(String value) {
        StringBuilder hex = new StringBuilder(value.length() * 4);
        for (int index = 0; index < value.length(); index++) {
            hex.append(String.format("%04X", (int) value.charAt(index)));
        }
        return hex.toString();
    }

    private static String clip(String value) {
        if (value == null) return "";
        String oneLine = value.replace('\n', ' ').replace('\r', ' ');
        return oneLine.length() <= MAX_LINE_CHARS ? oneLine : oneLine.substring(0, MAX_LINE_CHARS - 1) + "…";
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

    private static void write(ByteArrayOutputStream out, String value) {
        out.writeBytes(value.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static String text(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }
}
