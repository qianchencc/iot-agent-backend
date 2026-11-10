package ltd.qianc.iotagent.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

class ReportExportCompletenessTest {
    @Test
    void comparisonExportKeepsTotalsAndOneDetailPerMeterWithoutDuplicatingStatistics() throws Exception {
        var fact = comparison(30, 20);
        var statistics = Map.of("kind", "statistics", "params", Map.of("span", span("2026-01-08", "2026-01-15")),
                "total", Map.of("value", 30), "meters", List.of(meter("a", 10), meter("b", 20)));
        var report = report(statistics, fact);
        var sheets = sheets(ReportRendererFactory.xlsx().render(report).content());
        var details = sheets.stream().filter(s -> s.getFirst().contains("本期用量(度)")).toList();
        assertThat(details).hasSize(1);
        assertThat(details.getFirst()).hasSize(3);
        int valueColumn = details.getFirst().getFirst().indexOf("本期用量(度)");
        assertThat(details.getFirst().stream().skip(1).map(r -> new BigDecimal(r.get(valueColumn))))
                .containsExactly(new BigDecimal("10"), new BigDecimal("20"));
        assertThat(sheets.getFirst()).anySatisfy(r -> assertThat(r).contains("本期总量", "30"));
        assertThat(sheets.getFirst()).anySatisfy(r -> assertThat(r).contains("基期总量", "20"));
        assertThat(sheets.getFirst()).anySatisfy(r -> assertThat(r).contains("增减量", "10"));
        try (var pdf = Loader.loadPDF(ReportRendererFactory.pdf().render(report).content())) {
            assertThat(new PDFTextStripper().getText(pdf)).contains("30", "20", "50", "2026-01-01");
        }
    }
    @Test
    void zeroBaselineAndMissingSidesStayBlankWhileDifferentPeriodsStaySeparate() throws Exception {
        var zero = new LinkedHashMap<>(comparison(30, 0));
        zero.put("diff", Map.of("value", 30, "pctNote", "基期为零", "unit", "度", "basis", "total"));
        zero.put("meters", List.of(pair("a", meter("a", 10), meter("a", 0)),
                pair("b", meter("b", 20), Map.of("status", "noData"))));
        var previous = new LinkedHashMap<>(comparison(30, 20));
        previous.put("params", Map.of("span", span("2026-02-08", "2026-02-15"), "baseline", span("2026-02-01", "2026-02-08")));
        var sheets = sheets(ReportRendererFactory.xlsx().render(report(zero, previous)).content());
        var details = sheets.stream().filter(s -> s.getFirst().contains("本期用量(度)")).toList();
        assertThat(details).hasSize(2);
        var header = details.getFirst().getFirst();
        var first = details.getFirst().get(1);
        var missing = details.getFirst().get(2);
        assertThat(first.get(header.indexOf("用量增减(度)"))).isEqualTo("10");
        assertThat(first.get(header.indexOf("用量增减比例(%)"))).isEmpty();
        assertThat(missing.get(header.indexOf("基期用量(度)"))).isEmpty();
        assertThat(missing.get(header.indexOf("用量增减(度)"))).isEmpty();
        assertThat(missing.get(header.indexOf("基期状态"))).isEqualTo("无有效数据");
    }

    static Map<String, Object> comparison(int current, int baseline) {
        var a = meter("a", 10); var b = meter("b", 20);

        var rows = List.of(pair("a", a, meter("a", 0)), pair("b", b, meter("b", baseline)));
        return Map.of("kind", "comparison", "params", Map.of("axis", "time",
                "span", span("2026-01-08", "2026-01-15"), "baseline", span("2026-01-01", "2026-01-08")),
                "sides", Map.of("a", Map.of("total", Map.of("value", current)), "b", Map.of("total", Map.of("value", baseline))),
                "diff", Map.of("value", 10, "pct", 50, "unit", "度", "basis", "total"), "meters", rows);
    }
    static Map<String, Object> meter(String id, Number value) {
        return Map.of("id", id, "name", "测试电表" + id, "value", value, "status", "ok");
    }
    static Map<String, Object> pair(String id, Map<String, Object> a, Map<String, Object> b) {
        var ca = new LinkedHashMap<>(a); ca.remove("id"); ca.remove("name");
        var cb = new LinkedHashMap<>(b); cb.remove("id"); cb.remove("name");
        return Map.of("id", id, "name", "测试电表" + id, "a", ca, "b", cb);
    }
    static Map<String, Object> span(String from, String to) {
        return Map.of("requested", Map.of("from", from + "T08:00:00+08:00", "to", to + "T08:00:00+08:00"));
    }
    @SafeVarargs
    static Map<String, Object> report(Map<String, Object>... facts) {
        return Map.of("id", "report-export-test", "title", "测试用电报告", "status", "completed",
                "blocks", java.util.Arrays.stream(facts).map(f -> Map.of("type", "meter_report", "report", f)).toList());
    }
    static List<List<List<String>>> sheets(byte[] bytes) throws Exception {
        var result = new ArrayList<List<List<String>>>();
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.getName().startsWith("xl/worksheets/")) continue;
                var doc = factory.newDocumentBuilder().parse(new ByteArrayInputStream(zip.readAllBytes()));
                var rows = new ArrayList<List<String>>();
                var nodes = doc.getElementsByTagName("row");
                for (int r = 0; r < nodes.getLength(); r++) {
                    var cells = ((Element) nodes.item(r)).getElementsByTagName("c");
                    var values = new ArrayList<String>();
                    for (int c = 0; c < cells.getLength(); c++) values.add(cells.item(c).getTextContent());
                    rows.add(values);
                }
                result.add(rows);
            }
        }
        return result;
    }
}
