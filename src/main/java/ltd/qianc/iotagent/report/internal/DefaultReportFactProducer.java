package ltd.qianc.iotagent.report.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import ltd.qianc.iotagent.report.ReportFact;
import ltd.qianc.iotagent.report.ReportFactProducer;
import ltd.qianc.iotagent.report.ReportFactStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

/** Projects terminal company checkpoint payloads into immutable report facts. */
@Component
@ConditionalOnBean(ReportFactStore.class)
public final class DefaultReportFactProducer implements ReportFactProducer {
    private final ReportFactStore store;
    private final ObjectMapper mapper;

    public DefaultReportFactProducer(ReportFactStore store, ObjectMapper mapper) {
        this.store = store;
        this.mapper = mapper;
    }

    @Override
    public Optional<ReportFact> produce(ReportCheckpoint checkpoint) {
        if (checkpoint == null || !"company".equals(checkpoint.source()) || !terminal(checkpoint.outcome())) {
            return Optional.empty();
        }
        try {
            JsonNode parsed = mapper.readTree(checkpoint.payloadJson());
            if (parsed == null || !parsed.isObject()) return Optional.empty();
            JsonNode candidate = parsed.path("report");
            if (!candidate.isObject()) {
                candidate = parsed.has("title") && parsed.has("blocks") ? parsed : null;
            }
            if (candidate == null || !candidate.isObject()) return Optional.empty();

            ObjectNode report = (ObjectNode) candidate.deepCopy();
            if (!report.path("title").isTextual() || report.path("title").asText().isBlank()
                    || !report.path("blocks").isArray()) return Optional.empty();
            if (!report.has("meterIds")) {
                ArrayNode meterIds = mapper.createArrayNode();
                java.util.Set<String> seen = new java.util.LinkedHashSet<>();
                for (ReportReferenceExtractor.Reference reference : ReportReferenceExtractor.extract(report)) {
                    if (reference.meter() && seen.add(reference.id())) meterIds.add(reference.id());
                }
                if (!meterIds.isEmpty()) report.set("meterIds", meterIds);
            }
            String reportId = text(report, "id", "");
            if (reportId.isBlank()) {
                reportId = UUID.nameUUIDFromBytes((checkpoint.taskId() + "\u0000" + checkpoint.phaseId())
                        .getBytes(StandardCharsets.UTF_8)).toString();
            }
            String generatedAt = checkpoint.generatedAt().toString();
            String status = reportStatus(text(report, "status", ""), checkpoint.outcome());
            report.put("id", reportId);
            report.put("source", "company");
            report.put("tenantCode", checkpoint.tenantCode());
            report.put("ownerKey", checkpoint.ownerKey());
            report.put("status", status);
            report.put("generatedAt", generatedAt);
            report.put("taskId", checkpoint.taskId());
            report.put("phaseId", checkpoint.phaseId());
            report.put("checkpointAt", checkpoint.checkpointAt().toString());
            if (!checkpoint.objective().isBlank() && !report.hasNonNull("objective")) {
                report.put("objective", checkpoint.objective());
            }
            ObjectNode origin = report.with("origin");
            origin.put("kind", "checkpoint");
            origin.put("taskId", checkpoint.taskId());
            origin.put("phaseId", checkpoint.phaseId());
            origin.put("at", checkpoint.checkpointAt().toString());

            ReportFact fact = new ReportFact(reportId, "company", mapper.writeValueAsString(report));
            return Optional.of(store.find(reportId).orElseGet(() -> store.save(fact)));
        } catch (Exception ignored) {
            // A malformed or unavailable report must not make the terminal task
            // execute again. The task and checkpoint facts remain authoritative.
            return Optional.empty();
        }
    }

    private static boolean terminal(String outcome) {
        return switch (outcome.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "COMPLETED", "PARTIAL", "FAILED", "UNCONFIRMED", "INTERRUPTED" -> true;
            default -> false;
        };
    }

    private static String reportStatus(String existing, String outcome) {
        if (!"COMPLETED".equalsIgnoreCase(outcome)) return "partial";
        return "partial".equalsIgnoreCase(existing) ? "partial" : "completed";
    }

    private static String text(ObjectNode object, String name, String fallback) {
        JsonNode value = object.get(name);
        return value == null || value.isNull() ? fallback : value.asText(fallback);
    }
}
