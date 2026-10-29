package ltd.qianc.iotagent.report.internal;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Small dependency-free PDF projection of saved report facts; never recalculates measurements. */
public final class ReportPdfRenderer {
    private ReportPdfRenderer() { }

    public static byte[] render(Map<String, Object> report) {
        List<String> lines = new ArrayList<>();
        lines.add(text(report.get("title"), "报告"));
        lines.add("状态：" + text(report.get("status"), "unknown"));
        lines.add("生成时间：" + text(report.get("generatedAt"), ""));
        lines.add("目标：" + text(report.get("objective"), ""));
        String summary = text(report.get("summary"), "");
        if (!summary.isBlank()) lines.add(summary);
        Object blocks = report.get("blocks");
        if (blocks instanceof List<?> list) {
            for (Object value : list) {
                if (!(value instanceof Map<?, ?> block)) continue;
                String title = text(block.get("title"), "");
                String body = text(block.get("text"), "");
                if (!title.isBlank()) lines.add(title);
                if (!body.isBlank()) lines.add(body);
            }
        }
        StringBuilder stream = new StringBuilder("BT /F1 11 Tf 48 780 Td ");
        boolean first = true;
        for (String line : lines) {
            String safe = ascii(line);
            if (!first) stream.append("0 -18 Td ");
            stream.append('(').append(pdfEscape(safe)).append(") Tj ");
            first = false;
        }
        stream.append("ET");
        String content = stream.toString();
        List<String> objects = List.of(
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
                "<< /Length " + content.getBytes(StandardCharsets.US_ASCII).length + " >>\nstream\n" + content + "\nendstream"
        );
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            write(out, "%PDF-1.4\n%\u00e2\u00e3\u00cf\u00d3\n");
            List<Integer> offsets = new ArrayList<>();
            for (int i = 0; i < objects.size(); i++) {
                offsets.add(out.size());
                write(out, (i + 1) + " 0 obj\n" + objects.get(i) + "\nendobj\n");
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

    private static void write(ByteArrayOutputStream out, String value) throws Exception {
        out.write(value.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static String text(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private static String ascii(String value) {
        StringBuilder result = new StringBuilder(value.length());
        value.codePoints().forEach(codePoint -> result.append(codePoint < 128 ? (char) codePoint : '?'));
        return result.toString();
    }

    private static String pdfEscape(String value) {
        return value.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)").replace("\n", " ");
    }
}
