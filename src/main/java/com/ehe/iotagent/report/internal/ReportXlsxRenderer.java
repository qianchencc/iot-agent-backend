package com.ehe.iotagent.report.internal;

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
        summary.add(Row.text("项目", "内容"));
        summary.add(Row.text("报告", text(report.get("title"), "报告")));
        summary.add(Row.text("状态", MeterExportEvidence.reportStatus(report.get("status"))));
        summary.add(Row.text("生成时间（上海时间）", MeterExportEvidence.time(report.get("generatedAt"))));
        summary.add(Row.text("目标", text(report.get("objective"), "")));
        summary.add(Row.text("摘要", text(report.get("summary"), "")));
        Object coverage = report.get("coverage");
        if (coverage instanceof Map<?, ?> map) {
            summary.add(Row.text("覆盖/缺失", MeterExportEvidence.missing(map.get("missing"))));
            summary.add(Row.text("计量范围", text(map.get("meters"), "") + " 台 / 有效 "
                    + text(map.get("dataMeters"), "") + " 台"));
        }
        Object blocks = report.get("blocks");
        for (Map<?, ?> block : MeterExportEvidence.maps(blocks)) {
            if ("text".equals(block.get("type"))) summary.add(Row.text(text(block.get("title"), "说明"), text(block.get("text"), "")));
        }
        for (String note : MeterExportEvidence.reportNotes(report)) summary.add(Row.text("计量口径", note));
        List<Sheet> sheets = new ArrayList<>();
        sheets.add(new Sheet("报告概览", summary));
        var sections = MeterExportEvidence.sections(report);
        List<Row> evidence = new ArrayList<>();
        evidence.add(Row.texts("统计区块", "电表", "设备标识", "期间", "请求起止期间", "起始采样时间", "起始累计示数",
                "结束采样时间", "结束累计示数", "状态", "数据来源"));
        for (var section : MeterExportEvidence.details(sections)) {
            summary.add(Row.text(section.label(), section.period("a")));
            summary.add(Row.text("本期总量", section.total("a")));
            if (section.comparison()) {
                summary.add(Row.text("基期期间", section.period("b")));
                summary.add(Row.text("基期总量", section.total("b")));
                summary.add(Row.text("增减量", section.diff().get("value")));
                summary.add(Row.text("增减比例(%)", section.diff().get("pct")));
                summary.add(Row.text("比较口径", "dailyAvg".equals(section.diff().get("basis")) ? "按日均比较，度/日" : "按总量比较，度"));
                summary.add(Row.text("比较说明", text(section.diff().get("pctNote"), "")));
            }
            summary.add(Row.text("用量单位", "度"));
        }
        int sectionIndex = 0;
        for (var section : MeterExportEvidence.details(sections)) {
            List<Row> details = new ArrayList<>();
            details.add(Row.texts("电表", "位置", "本期用量(度)", "基期用量(度)", "用量增减(度)", "用量增减比例(%)",
                    "本期状态", "基期状态", "比较说明", "本期期间", "基期期间", "设备标识"));
            for (Map<?, ?> meter : section.meters()) {
                Map<?, ?> a = MeterExportEvidence.current(meter, "a"), b = MeterExportEvidence.current(meter, "b");
                details.add(Row.values(java.util.Arrays.asList(text(meter.get("name"), text(meter.get("id"), "")),
                        text(meter.get("areaName"), ""), a.get("value"), b.get("value"),
                        MeterExportEvidence.change(meter), MeterExportEvidence.rate(meter),
                        MeterExportEvidence.status(a.get("status")), section.comparison() ? MeterExportEvidence.status(b.get("status")) : "不适用",
                        section.comparison() ? MeterExportEvidence.comparisonNote(meter) : "",
                        section.period("a"), section.comparison() ? section.period("b") : "", meter.get("id"))));
                for (String side : section.comparison() ? List.of("a", "b") : List.of("a")) {
                    List<Object> sample = MeterExportEvidence.samples(meter, side);
                    evidence.add(Row.values(java.util.Arrays.asList(section.label(), meter.get("name"), meter.get("id"),
                            "a".equals(side) ? "本期" : "基期", section.period(side),
                            sample.get(0), sample.get(1), sample.get(2), sample.get(3),
                            MeterExportEvidence.status(MeterExportEvidence.current(meter, side).get("status")), "ENERGY_HISTORY/epf")));
                }
            }
            String name = section.comparison() ? "逐表对比" : "用电明细";
            sheets.add(new Sheet(name + (++sectionIndex == 1 ? "" : "-" + sectionIndex), details));
        }
        if (evidence.size() > 1) sheets.add(new Sheet("采样依据", evidence));
        addMetrics(sheets, sections);
        summary.add(Row.text("报告标识", text(report.get("id"), "")));
        summary.add(Row.text("内容标识", text(report.get("contentHash"), "")));

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
        List<Row> inspection = new ArrayList<>();
        inspection.add(Row.texts("检查时间", "检查方式", "设备", "位置", "检查前状态", "处置结果", "平台回读(℃)", "说明", "节电估算(度)"));
        if (blocks instanceof List<?> list) {
            for (Object value : list) {
                if (!(value instanceof Map<?, ?> block) || !"ac_inspection".equals(text(block.get("type"), ""))) continue;
                String at = text(block.get("at"), "");
                Object target = null;
                if (block.get("criteria") instanceof Map<?, ?> criteria
                        && criteria.get("setpoint") instanceof Map<?, ?> setpoint) {
                    target = setpoint.get("value");
                }
                if (target == null) target = block.get("target");
                String modeText = "adjust_to_26".equals(text(block.get("mode"), ""))
                        ? "调温巡检（目标 " + text(target, "26") + "℃）" : "只读巡检";
                if (block.get("devices") instanceof List<?> rows) {
                    for (Object row : rows) {
                        if (!(row instanceof Map<?, ?> device)) continue;
                        inspection.add(Row.values(java.util.Arrays.asList(
                                at,
                                modeText,
                                text(device.get("name"), text(device.get("deviceId"), "未知设备")),
                                text(device.get("location"), ""),
                                xlsxAcState(device),
                                xlsxAcDisposition(device, target),
                                device.get("afterSetpoint") instanceof Number readback ? readback : "",
                                text(device.get("reason"), ""),
                                "")));
                    }
                }
                if (block.get("energyEstimate") instanceof Map<?, ?> estimate) {
                    List<String> basis = new ArrayList<>();
                    if (estimate.get("assumptions") instanceof Map<?, ?> assumptions) {
                        if (assumptions.get("ratePerDegree") instanceof Number rate) {
                            basis.add("每升高 1℃ 约节电 " + percent(rate) + "%");
                        }
                        if (assumptions.get("powerKw") instanceof Number power) basis.add("单台功率 " + power + "kW");
                        if (assumptions.get("pricePerKwh") instanceof Number price) basis.add("电价 " + price + " 元/度");
                    }
                    if (estimate.get("baseline") instanceof Map<?, ?> baseline) {
                        if (baseline.get("averageSetpoint") instanceof Number average) basis.add("调前平均设定 " + average + "℃");
                        if (baseline.get("target") instanceof Number aim) basis.add("统一目标 " + aim + "℃");
                        if (baseline.get("averageRise") instanceof Number rise) basis.add("平均调升 " + rise + "℃");
                    }
                    if (estimate.get("coverage") instanceof Map<?, ?> estimateCoverage) {
                        if (estimateCoverage.get("adjusted") instanceof Number adjusted) basis.add("实际调节 " + adjusted + " 台");
                        if (estimateCoverage.get("estimated") instanceof Number estimated) basis.add("参与估算 " + estimated + " 台");
                    }
                    String calculation = text(estimate.get("calculation"), "");
                    if (!calculation.isBlank()) basis.add(calculation);
                    String reason = text(estimate.get("reason"), "");
                    if (!reason.isBlank()) basis.add(reason);
                    if (Boolean.TRUE.equals(estimate.get("potential"))) basis.add("可调节潜力估算，设备未实际调节");
                    inspection.add(Row.values(java.util.Arrays.asList(
                            at,
                            modeText,
                            "节电估算（估算值，非电表实测）",
                            "",
                            "",
                            estimate.get("costValue") instanceof Number cost
                                    ? "折合电费约 " + cost + " " + text(estimate.get("costUnit"), "元") : "折合电费未知",
                            "",
                            String.join("；", basis),
                            estimate.get("value") instanceof Number saved ? saved : "")));
                }
            }
        }
        if (safety.size() > 1) sheets.add(new Sheet("安全台账", safety));
        if (inspection.size() > 1) sheets.add(new Sheet("巡检明细", inspection));
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
                entry(zip, "[Content_Types].xml", contentTypes(sheets.size()));
                entry(zip, "_rels/.rels", relationships());
                entry(zip, "xl/workbook.xml", workbook(sheets));
                entry(zip, "xl/_rels/workbook.xml.rels", workbookRelationships(sheets.size()));
                entry(zip, "xl/styles.xml", styles());
                for (int i = 0; i < sheets.size(); i++) entry(zip, "xl/worksheets/sheet" + (i + 1) + ".xml", sheet(sheets.get(i)));
            }
            return bytes.toByteArray();
        } catch (Exception error) {
            throw new IllegalStateException("XLSX rendering failed", error);
        }
    }

    private record Sheet(String name, List<Row> rows) { }

    private static void addMetrics(List<Sheet> sheets, List<MeterExportEvidence.Section> sections) {
        List<Row> metrics = new ArrayList<>();
        metrics.add(Row.texts("统计区块", "指标", "本期", "基期", "增减", "单位", "本期时段", "基期时段", "口径"));
        List<Row> buckets = new ArrayList<>();
        buckets.add(Row.texts("统计区块", "期间", "粒度", "分段标识", "起始时间", "结束时间", "用量(度)", "状态"));
        List<Row> ranking = new ArrayList<>();
        ranking.add(Row.texts("统计区块", "类别", "位置", "用量(度)", "增减量", "单位", "其他位置数量"));
        List<Row> comparisons = new ArrayList<>();
        comparisons.add(Row.texts("统计区块", "本期起始", "本期结束", "基期起始", "基期结束", "本期用量(度)", "基期用量(度)", "增减量", "增减比例(%)", "比较单位", "说明"));
        for (var section : sections) {
            for (Map<?, ?> metric : MeterExportEvidence.maps(section.fact().get("metrics"))) {
                Map<?, ?> a = metric.get("a") instanceof Map<?, ?> pair ? pair : metric;
                Map<?, ?> b = MeterExportEvidence.map(metric.get("b"));
                metrics.add(Row.values(java.util.Arrays.asList(section.label(), MeterExportEvidence.metricName(metric.get("key")),
                        a.get("value"), b.get("value"), metric.get("change"), metric.get("unit"),
                        MeterExportEvidence.metricPeriod(a), MeterExportEvidence.metricPeriod(b),
                        text(metric.get("nightWindow"), "") + " " + text(metric.get("dayWindow"), ""))));
            }
            if (section.comparison()) {
                for (String side : List.of("a", "b")) addBuckets(buckets, section.label(), side,
                        MeterExportEvidence.map(MeterExportEvidence.map(section.fact().get("sides")).get(side)));
            } else addBuckets(buckets, section.label(), "a", section.fact());
            for (String field : List.of("composition", "contribution")) {
                Map<?, ?> group = MeterExportEvidence.map(section.fact().get(field));
                for (Map<?, ?> item : MeterExportEvidence.maps(group.get("rows"))) ranking.add(Row.values(java.util.Arrays.asList(
                        section.label(), "composition".equals(field) ? "用电构成" : "增减贡献", item.get("label"),
                        item.get("value"), item.get("delta"), text(group.get("unit"), "度"), null)));
                Map<?, ?> rest = MeterExportEvidence.map(group.get("rest"));
                if (!rest.isEmpty()) ranking.add(Row.values(java.util.Arrays.asList(section.label(),
                        "composition".equals(field) ? "用电构成" : "增减贡献", "其他", rest.get("value"), rest.get("delta"),
                        text(group.get("unit"), "度"), rest.get("count"))));
            }
            for (Map<?, ?> item : MeterExportEvidence.maps(MeterExportEvidence.map(section.fact().get("periodComparisons")).get("items"))) {
                Map<?, ?> a = MeterExportEvidence.map(item.get("a")), b = MeterExportEvidence.map(item.get("b"));
                Map<?, ?> diff = MeterExportEvidence.map(item.get("diff"));
                comparisons.add(Row.values(java.util.Arrays.asList(section.label(), a.get("from"), a.get("to"), b.get("from"), b.get("to"),
                        a.get("value"), b.get("value"), diff.get("value"), diff.get("pct"), diff.get("unit"), diff.get("pctNote"))));
            }
        }
        if (metrics.size() > 1) sheets.add(new Sheet("指标明细", metrics));
        if (buckets.size() > 1) sheets.add(new Sheet("分段用电", buckets));
        if (ranking.size() > 1) sheets.add(new Sheet("用电构成与贡献", ranking));
        if (comparisons.size() > 1) sheets.add(new Sheet("分段对比", comparisons));
    }

    private static void addBuckets(List<Row> rows, String label, String side, Map<?, ?> fact) {
        Map<?, ?> all = MeterExportEvidence.map(fact.get("bucketsAll"));
        if (!all.isEmpty()) {
            all.forEach((grain, values) -> appendBuckets(rows, label, side, grain.toString(), values));
        } else {
            Map<?, ?> bucket = MeterExportEvidence.map(fact.get("buckets"));
            appendBuckets(rows, label, side, text(bucket.get("grain"), ""), bucket.get("items"));
        }
    }

    private static void appendBuckets(List<Row> rows, String label, String side, String grain, Object values) {
        for (Map<?, ?> bucket : MeterExportEvidence.maps(values)) {
            rows.add(Row.values(java.util.Arrays.asList(label, "a".equals(side) ? "本期" : "基期", grain, bucket.get("key"),
                    bucket.get("from"), bucket.get("to"), bucket.get("value"), MeterExportEvidence.bucketStatus(bucket))));
        }
    }

    private static String contentTypes(int count) {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                + "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>");
        for (int i = 1; i <= count; i++) xml.append("<Override PartName=\"/xl/worksheets/sheet").append(i)
                .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
        return xml.append("</Types>").toString();
    }

    private static String relationships() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>";
    }

    private static String workbook(List<Sheet> sheets) {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
                + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><bookViews><workbookView/></bookViews><sheets>");
        for (int i = 0; i < sheets.size(); i++) xml.append("<sheet name=\"").append(xml(sheets.get(i).name())).append("\" sheetId=\"")
                .append(i + 1).append("\" r:id=\"rId").append(i + 1).append("\"/>");
        return xml.append("</sheets></workbook>").toString();
    }

    private static String workbookRelationships(int count) {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
        for (int i = 1; i <= count; i++) xml.append("<Relationship Id=\"rId").append(i)
                .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet").append(i).append(".xml\"/>");
        return xml.append("<Relationship Id=\"styles\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/></Relationships>").toString();
    }

    private static String styles() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                + "<fonts count=\"2\"><font><sz val=\"11\"/><name val=\"等线\"/></font><font><b/><color rgb=\"FFFFFFFF\"/><sz val=\"11\"/><name val=\"等线\"/></font></fonts>"
                + "<fills count=\"3\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill><fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF284D69\"/><bgColor indexed=\"64\"/></patternFill></fill></fills>"
                + "<borders count=\"1\"><border/></borders><cellStyleXfs count=\"1\"><xf/></cellStyleXfs>"
                + "<cellXfs count=\"3\"><xf fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyAlignment=\"1\"><alignment vertical=\"top\" wrapText=\"1\"/></xf>"
                + "<xf fontId=\"1\" fillId=\"2\" borderId=\"0\" xfId=\"0\" applyAlignment=\"1\"><alignment vertical=\"center\" wrapText=\"1\"/></xf>"
                + "<xf numFmtId=\"4\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\" applyAlignment=\"1\"><alignment vertical=\"top\" horizontal=\"right\"/></xf></cellXfs>"
                + "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles></styleSheet>";
    }

    private static String xlsxAcState(Map<?, ?> device) {
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

    private static String xlsxAcDisposition(Map<?, ?> device, Object target) {
        String disposition = text(device.get("disposition"), "");
        return switch (disposition) {
            case "adjusted" -> target instanceof Number ? "已下发调到" + target + "℃" : "已下发调温指令";
            case "unconfirmed" -> "已下发，未确认回读";
            case "not_dispatched" -> "这次未调整";
            case "not_needed" -> "无需调整";
            case "adjustable" -> "可调";
            case "unknown" -> "已下发";
            default -> "状态未知";
        };
    }

    private static String percent(Number rate) {
        double value = Math.round(rate.doubleValue() * 1000) / 10.0;
        return value == Math.floor(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    private static String sheet(Sheet data) {
        List<Row> rows = data.rows();
        int columns = rows.getFirst().values.size();
        String last = columnName(columns - 1);
        StringBuilder sheet = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
                .append("<sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews>")
                .append("<sheetFormatPr defaultRowHeight=\"32\"/><cols>");
        for (int c = 0; c < columns; c++) {
            String heading = String.valueOf(rows.getFirst().values.get(c));
            int width = columns == 2 ? c == 0 ? 25 : 100
                    : heading.contains("期间") || heading.contains("时间") ? 38 : heading.contains("电表") || heading.contains("说明") || heading.contains("设备标识") ? 32 : 20;
            sheet.append("<col min=\"").append(c + 1).append("\" max=\"").append(c + 1).append("\" width=\"").append(width).append("\" customWidth=\"1\"/>");
        }
        sheet.append("</cols><sheetData>");
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            Row row = rows.get(rowIndex);
            sheet.append("<row r=\"").append(rowIndex + 1).append("\">");
            for (int column = 0; column < row.values.size(); column++) {
                Object value = row.values.get(column);
                String ref = columnName(column) + (rowIndex + 1);
                int style = rowIndex == 0 ? 1 : value instanceof Number ? 2 : 0;
                sheet.append("<c r=\"").append(ref).append("\" s=\"").append(style).append("\"");
                if (value instanceof Number number && Double.isFinite(number.doubleValue())) {
                    sheet.append("><v>").append(number).append("</v></c>");
                } else {
                    sheet.append(" t=\"inlineStr\"><is><t xml:space=\"preserve\">").append(xml(text(value, ""))).append("</t></is></c>");
                }
            }
            sheet.append("</row>");
        }
        return sheet.append("</sheetData><autoFilter ref=\"A1:").append(last).append(rows.size())
                .append("\"/><printOptions horizontalCentered=\"1\"/><pageMargins left=\"0.3\" right=\"0.3\" top=\"0.5\" bottom=\"0.5\" header=\"0.2\" footer=\"0.2\"/>")
                .append("<pageSetup orientation=\"landscape\" paperSize=\"9\" fitToWidth=\"1\" fitToHeight=\"0\"/></worksheet>").toString();
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
        static Row text(Object left, Object right) { return values(java.util.Arrays.asList(left, right)); }
        static Row texts(Object... values) { return values(java.util.Arrays.asList(values)); }
        static Row values(List<?> values) { return new Row(new ArrayList<>(values)); }
    }
}
