package ltd.qianc.iotagent.report.internal;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Dependency-free OOXML workbook projection of the same saved report facts used
 * by preview and PDF. Numeric measurements are written as numeric cells so a
 * spreadsheet can calculate with them; no value is recalculated here.
 */
public final class ReportXlsxRenderer {
    private ReportXlsxRenderer() { }

    public static byte[] render(Map<String, Object> report) {
        List<Row> summary = new ArrayList<>();
        summary.add(Row.text("报告", text(report.get("title"), "报告")));
        summary.add(Row.text("报告标识", text(report.get("id"), "")));
        summary.add(Row.text("状态", text(report.get("status"), "")));
        summary.add(Row.text("生成时间", text(report.get("generatedAt"), "")));
        summary.add(Row.text("内容标识", text(report.get("contentHash"), "")));
        summary.add(Row.text("目标", text(report.get("objective"), "")));
        summary.add(Row.text("摘要", text(report.get("summary"), "")));
        Object coverage = report.get("coverage");
        if (coverage instanceof Map<?, ?> map) {
            summary.add(Row.text("覆盖/缺失", text(map.get("missing"), "无显式缺失")));
            summary.add(Row.text("计量范围", text(map.get("meters"), "") + " 台 / 有效 "
                    + text(map.get("dataMeters"), "") + " 台"));
        }
        Object blocks = report.get("blocks");
        if (blocks instanceof List<?> list) {
            for (Object value : list) {
                if (!(value instanceof Map<?, ?> block)) continue;
                if ("text".equals(text(block.get("type"), ""))) {
                    summary.add(Row.text(text(block.get("title"), "说明"), text(block.get("text"), "")));
                }
            }
        }

        List<Row> energy = new ArrayList<>();
        energy.add(Row.texts("电表", "位置", "期间", "本期用量(度)", "基期用量(度)", "状态", "覆盖", "数据来源"));
        if (blocks instanceof List<?> list) {
            for (Object value : list) {
                if (!(value instanceof Map<?, ?> block) || !"meter_report".equals(text(block.get("type"), ""))) continue;
                if (!(block.get("report") instanceof Map<?, ?> fact)) continue;
                String from = "", to = "";
                if (fact.get("params") instanceof Map<?, ?> params && params.get("span") instanceof Map<?, ?> span
                        && span.get("requested") instanceof Map<?, ?> requested) {
                    from = text(requested.get("from"), "");
                    to = text(requested.get("to"), "");
                }
                Object meters = fact.get("meters");
                if (!(meters instanceof List<?> rows)) continue;
                Map<?, ?> factCoverage = fact.get("coverage") instanceof Map<?, ?> map ? map : null;
                for (Object row : rows) {
                    if (!(row instanceof Map<?, ?> meter)) continue;
                    Object current = meter.get("value");
                    Object base = null;
                    String status = text(meter.get("status"), "");
                    if (meter.get("a") instanceof Map<?, ?> a) {
                        current = a.get("value");
                        status = text(a.get("status"), status);
                        if (meter.get("b") instanceof Map<?, ?> b) base = b.get("value");
                    }
                    List<Object> values = new ArrayList<>();
                    values.add(text(meter.get("name"), text(meter.get("id"), "")));
                    values.add(text(meter.get("areaName"), ""));
                    values.add(from + " 至 " + to);
                    values.add(current);
                    values.add(base);
                    values.add(status);
                    values.add(factCoverage == null ? "" : text(factCoverage.get("dataMeters"), "") + "/"
                            + text(factCoverage.get("meters"), ""));
                    values.add("ENERGY_HISTORY/epf");
                    energy.add(Row.values(values));
                }
            }
        }

        List<Row> safety = new ArrayList<>();
        safety.add(Row.texts("台账", "事件", "设备", "发生时间", "告警/记录", "覆盖"));
        if (blocks instanceof List<?> list) {
            for (Object value : list) {
                if (!(value instanceof Map<?, ?> block) || !"safety_ledger".equals(text(block.get("type"), ""))) continue;
                Object events = block.get("events");
                String ledger = text(block.get("ledger"), "");
                Map<?, ?> blockCoverage = block.get("coverage") instanceof Map<?, ?> map ? map : null;
                if (!(events instanceof List<?> rows)) continue;
                for (Object row : rows) {
                    if (!(row instanceof Map<?, ?> event)) continue;
                    safety.add(Row.values(List.of(
                            text(event.get("ledger"), ledger),
                            text(event.get("eventId"), text(event.get("id"), "")),
                            text(event.get("deviceName"), text(event.get("deviceId"), "")),
                            text(event.get("occurredAt"), ""),
                            Boolean.TRUE.equals(event.get("alarm")) ? "告警" : "记录",
                            blockCoverage == null ? "" : text(blockCoverage.get("events"), ""))));
                }
            }
        }

        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
                entry(zip, "[Content_Types].xml", contentTypes());
                entry(zip, "_rels/.rels", relationships());
                entry(zip, "xl/workbook.xml", workbook());
                entry(zip, "xl/_rels/workbook.xml.rels", workbookRelationships());
                entry(zip, "xl/worksheets/sheet1.xml", sheet(summary));
                entry(zip, "xl/worksheets/sheet2.xml", sheet(energy));
                entry(zip, "xl/worksheets/sheet3.xml", sheet(safety));
            }
            return bytes.toByteArray();
        } catch (Exception error) {
            throw new IllegalStateException("XLSX rendering failed", error);
        }
    }

    private static String contentTypes() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                + "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
                + "<Override PartName=\"/xl/worksheets/sheet2.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
                + "<Override PartName=\"/xl/worksheets/sheet3.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
                + "</Types>";
    }

    private static String relationships() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
                + "</Relationships>";
    }

    private static String workbook() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
                + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                + "<sheets><sheet name=\"Report\" sheetId=\"1\" r:id=\"rId1\"/>"
                + "<sheet name=\"Energy\" sheetId=\"2\" r:id=\"rId2\"/>"
                + "<sheet name=\"Safety\" sheetId=\"3\" r:id=\"rId3\"/></sheets></workbook>";
    }

    private static String workbookRelationships() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
                + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet2.xml\"/>"
                + "<Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet3.xml\"/>"
                + "</Relationships>";
    }

    private static String sheet(List<Row> rows) {
        StringBuilder sheet = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>");
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            Row row = rows.get(rowIndex);
            sheet.append("<row r=\"").append(rowIndex + 1).append("\">");
            for (int column = 0; column < row.values.size(); column++) {
                Object value = row.values.get(column);
                String ref = columnName(column) + (rowIndex + 1);
                if (value instanceof Number number && Double.isFinite(number.doubleValue())) {
                    sheet.append("<c r=\"").append(ref).append("\"><v>").append(number).append("</v></c>");
                } else {
                    sheet.append("<c r=\"").append(ref).append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                            .append(xml(text(value, ""))).append("</t></is></c>");
                }
            }
            sheet.append("</row>");
        }
        return sheet.append("</sheetData></worksheet>").toString();
    }

    private static String columnName(int index) {
        StringBuilder result = new StringBuilder();
        int value = index + 1;
        while (value > 0) {
            int remainder = (value - 1) % 26;
            result.insert(0, (char) ('A' + remainder));
            value = (value - 1) / 26;
        }
        return result.toString();
    }

    private static void entry(ZipOutputStream zip, String name, String value) throws Exception {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0);
        zip.putNextEntry(entry);
        zip.write(value.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String text(Object value, String fallback) { return value == null ? fallback : String.valueOf(value); }

    private static String xml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static final class Row {
        final List<Object> values;
        Row(List<Object> values) { this.values = values; }
        static Row text(Object left, Object right) { return values(List.of(left, right)); }
        static Row texts(Object... values) { return values(java.util.Arrays.asList(values)); }
        static Row values(List<?> values) { return new Row(new ArrayList<>(values)); }
    }
}
