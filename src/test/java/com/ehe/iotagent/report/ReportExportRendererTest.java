package com.ehe.iotagent.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

/**
 * Behavioral export assertions for AC inspection blocks: PDF text and XLSX cells
 * must carry the real per-device conditions, dispositions, readback and the
 * labeled estimate assumptions; a block that lost its device rows must surface
 * the missing-result fact instead of an empty or silently dropped section.
 */
class ReportExportRendererTest {

    @Test
    void pdfCarriesDeviceOutcomesAndLabeledEstimateAssumptions() {
        String text = pdfText(ReportRendererFactory.pdf().render(inspectionReport()).content());

        assertThat(text).contains("空调调温检查");
        assertThat(text).contains("大厅空调（26楼）");
        assertThat(text).contains("已下发调到26℃");
        assertThat(text).contains("平台回读 26.0℃");
        assertThat(text).contains("这次未调整");
        assertThat(text).contains("上游拒绝调温指令");
        assertThat(text).contains("已下发，未确认回读");
        assertThat(text).contains("指令已受理，尚未取得平台实测回读");
        assertThat(text).contains("节电估算（估算值，非电表实测）：约 3.2 度");
        assertThat(text).contains("每升高 1℃ 约节电 5%");
        assertThat(text).contains("单台功率 0.8kW");
        assertThat(text).contains("调前平均设定 24.5℃");
        assertThat(text).contains("计算式：kWh = Σ(target - beforeSetpoint) × ratePerDegree × powerKw × hours");
    }

    @Test
    void pdfKeepsBlocksThatLostDeviceRowsVisibleAsMissingResults() {
        String text = pdfText(ReportRendererFactory.pdf().render(reportWithEmptyBlock()).content());

        assertThat(text).contains("本检查点没有取得设备结果");
        // The estimate-less block must not imply any savings claim.
        assertThat(text).doesNotContain("节电估算");
    }

    @Test
    void xlsxInspectionSheetCarriesDeviceRowsAndNumericEstimate() throws Exception {
        String sheet = sheetContaining(ReportRendererFactory.xlsx().render(inspectionReport()).content(), "处置结果");

        assertThat(sheet).contains("大厅空调");
        assertThat(sheet).contains("已下发调到26℃");
        assertThat(sheet).contains("这次未调整");
        assertThat(sheet).contains("已下发，未确认回读");
        assertThat(sheet).contains("上游拒绝调温指令");
        assertThat(sheet).contains("节电估算（估算值，非电表实测）");
        assertThat(sheet).contains("每升高 1℃ 约节电 5%");
        assertThat(sheet).contains("按调前设定温度与经验参数估算，非电表实测");
        // Estimate and readback stay numeric cells a spreadsheet can calculate with.
        assertThat(sheet).contains("<v>3.2</v>");
        assertThat(sheet).contains("<v>26.0</v>");
    }

    @Test
    void pdfPreservesChineseFactsAndEmbedsTheFontForPortableViewing() throws Exception {
        var report = new LinkedHashMap<String, Object>();
        report.put("id", "report-font-1");
        report.put("title", "公司能耗周报（26楼与27楼）");
        report.put("summary", "2026-10-26 至 2026-11-02，总用电 902.85 度。");
        byte[] bytes = ReportRendererFactory.pdf().render(report).content();
        try (var document = Loader.loadPDF(bytes)) {
            assertThat(new PDFTextStripper().getText(document))
                    .contains((String) report.get("title"), (String) report.get("summary"), "report-font-1");
            for (var page : document.getPages()) {
                for (var name : page.getResources().getFontNames()) {
                    assertThat(page.getResources().getFont(name).isEmbedded()).isTrue();
                }
            }
        }
    }

    @Test
    void exportsKeepMeasuredSampleBoundsAndCumulativeReadingsSeparateFromRequestedPeriod() throws Exception {
        String from = "2026-10-25T20:00:00+08:00", to = "2026-11-01T20:00:00+08:00";
        var observed = Map.of(
                "fromSample", Map.of("readTime", from, "value", 1000.0),
                "toSample", Map.of("readTime", to, "value", 1025.5));
        var meter = Map.of("id", "meter-1", "name", "办公室电表", "value", 25.5, "status", "ok", "observed", observed);
        var fact = Map.of("kind", "statistics", "params", Map.of("span", Map.of(
                "requested", Map.of("from", "2026-10-26T00:00:00+08:00", "to", "2026-11-02T00:00:00+08:00"),
                "observed", Map.of("from", from, "to", to))),
                "total", Map.of("value", 25.5, "unit", "度"), "meters", List.of(meter));
        var report = reportWith(Map.of("type", "meter_report", "report", fact));
        String pdf = pdfText(ReportRendererFactory.pdf().render(report).content());
        assertThat(pdf).contains("1000", "1025.5", "客户模拟补数");
        byte[] xlsx = ReportRendererFactory.xlsx().render(report).content();
        assertThat(sheetContaining(xlsx, "起始累计示数"))
                .contains(from, to, "<v>1000.0</v>", "<v>1025.5</v>");
    }


    private static Map<String, Object> inspectionReport() {
        Map<String, Object> adjusted = new LinkedHashMap<>();
        adjusted.put("deviceId", "ac-1");
        adjusted.put("name", "大厅空调");
        adjusted.put("location", "26楼");
        adjusted.put("power", "on");
        adjusted.put("mode", "cool");
        adjusted.put("setpoint", 25.0d);
        adjusted.put("disposition", "adjusted");
        adjusted.put("outcome", "verified");
        adjusted.put("afterSetpoint", 26.0d);
        Map<String, Object> rejected = new LinkedHashMap<>();
        rejected.put("deviceId", "ac-2");
        rejected.put("name", "会议室空调");
        rejected.put("location", "27楼");
        rejected.put("power", "on");
        rejected.put("mode", "cool");
        rejected.put("setpoint", 24.0d);
        rejected.put("disposition", "not_dispatched");
        rejected.put("outcome", "unknown");
        rejected.put("reason", "上游拒绝调温指令");
        Map<String, Object> unconfirmed = new LinkedHashMap<>();
        unconfirmed.put("deviceId", "ac-3");
        unconfirmed.put("name", "走廊空调");
        unconfirmed.put("power", "off");
        unconfirmed.put("mode", "fan");
        unconfirmed.put("disposition", "unconfirmed");
        unconfirmed.put("outcome", "unconfirmed");
        unconfirmed.put("reason", "指令已受理，尚未取得平台实测回读");

        Map<String, Object> estimate = new LinkedHashMap<>();
        estimate.put("status", "estimated");
        estimate.put("measured", false);
        estimate.put("label", "估算（非电表实测）");
        estimate.put("value", 3.2d);
        estimate.put("unit", "度");
        estimate.put("costValue", 2.1d);
        estimate.put("costUnit", "元");
        estimate.put("baseline", Map.of("averageSetpoint", 24.5d, "target", 26, "averageRise", 1.5d));
        estimate.put("assumptions", Map.of("ratePerDegree", 0.05d, "powerKw", 0.8d, "pricePerKwh", 0.65d));
        estimate.put("coverage", Map.of("adjusted", 2, "estimated", 2));
        estimate.put("reason", "按调前设定温度与经验参数估算，非电表实测");
        estimate.put("calculation", "kWh = Σ(target - beforeSetpoint) × ratePerDegree × powerKw × hours");

        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "ac_inspection");
        block.put("phaseId", "phase-1");
        block.put("at", "2026-10-12T01:00:00Z");
        block.put("status", "COMPLETED");
        block.put("outcome", "COMPLETED");
        block.put("mode", "adjust_to_26");
        block.put("target", 26);
        block.put("criteria", Map.of("setpoint", Map.of("value", 26)));
        block.put("devices", List.of(adjusted, rejected, unconfirmed));
        block.put("energyEstimate", estimate);
        return reportWith(block);
    }

    private static Map<String, Object> reportWithEmptyBlock() {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "ac_inspection");
        block.put("at", "2026-10-12T03:00:00Z");
        block.put("status", "FAILED");
        block.put("outcome", "FAILED");
        block.put("mode", "adjust_to_26");
        block.put("target", 26);
        block.put("devices", List.of());
        return reportWith(block);
    }

    private static Map<String, Object> reportWith(Map<String, Object> block) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("id", "report-1");
        report.put("title", "空调巡检报告");
        report.put("status", "partial");
        report.put("generatedAt", "2026-10-12T01:05:00Z");
        report.put("summary", "空调巡检报告");
        report.put("blocks", List.of(block));
        return report;
    }

    private static String pdfText(byte[] pdf) {
        try (var document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document).replaceAll("\\s+", " ");
        } catch (java.io.IOException failure) {
            throw new AssertionError("PDF must be readable by a standard parser", failure);
        }
    }

    private static String sheetContaining(byte[] xlsx, String header) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(xlsx))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.getName().startsWith("xl/worksheets/")) continue;
                String content = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                if (content.contains(header)) return content;
            }
        }
        throw new AssertionError("missing export data: " + header);
    }
}
