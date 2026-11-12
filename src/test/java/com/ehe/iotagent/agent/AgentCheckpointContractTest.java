package com.ehe.iotagent.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import com.ehe.iotagent.error.BusinessException;
import com.ehe.iotagent.iot.AuthorizedDevice;
import com.ehe.iotagent.iot.AuthorizedDeviceScope;
import com.ehe.iotagent.iot.IotAdapter;
import com.ehe.iotagent.iot.IotSourceEnum;
import com.ehe.iotagent.iot.ObservationAdapter;
import com.ehe.iotagent.iot.ObservationRequest;
import com.ehe.iotagent.iot.RequestIdentity;
import com.ehe.iotagent.report.ReportEvidenceFixture;
import com.ehe.iotagent.report.ReportFact;
import com.ehe.iotagent.report.ReportFactStore;
import com.ehe.iotagent.report.ReportGenerationServiceFactory;
import com.ehe.iotagent.task.TaskExecutionGateway.TaskExecutionRequest;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class AgentCheckpointContractTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final RequestIdentity IDENTITY = new RequestIdentity("Bearer fixture", "TENANT", IotSourceEnum.COMPANY, "owner");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-05T02:00:00Z"), ZoneOffset.UTC);
    private static final TaskExecutionRequest REQUEST = new TaskExecutionRequest("task", "phase", "company", "{}",
            Instant.parse("2026-11-02T01:00:00Z"), "{}");

    @Test
    void resultOutputRejectsReportSubmissionAndKeepsScheduledPeriodWithoutCreatingArtifact() throws Exception {
        try (Fixture fixture = new Fixture((bridge, binding) -> {
            assertThatThrownBy(() -> bridge.executeTool(call(binding, "publish_report", Map.of("title", "wrong", "summary", "wrong", "sections", List.of()))))
                    .isInstanceOf(BusinessException.class);
            bridge.executeTool(call(binding, "finish_checkpoint", Map.of("summary", "已读取确认期间")));
        })) {
            var executed = fixture.bridge.execute(IDENTITY, REQUEST, List.of("meter-1", "meter-2"), assignment("result"), () -> true);
            var result = MAPPER.readTree(executed.payloadJson());
            assertThat(executed.outcome()).isEqualTo("COMPLETED");
            assertThat(fixture.saved).isEmpty();
            assertThat(result.path("observations").get(0).path("report").path("total").path("value").asDouble()).isEqualTo(10);
            assertThat(fixture.queries.get(0).parameters()).containsEntry("from", "2026-11-01T08:00:00+08:00")
                    .containsEntry("to", "2026-11-02T08:00:00+08:00").containsEntry("meterIds", "meter-1,meter-2");
            assertThat(fixture.bridge.executeTool(call(fixture.lastBinding.get(), "finish_checkpoint", Map.of("summary", "again")))).isEmpty();
        }
    }

    @Test
    void scheduledReportPersistsServerFactsWithoutStartingModelInterpretation() throws Exception {
        try (Fixture fixture = new Fixture((bridge, binding) -> { })) {
            var executed = fixture.bridge.execute(IDENTITY, REQUEST, List.of("meter-1", "meter-2"), assignment("report"), () -> true);
            assertThat(executed.outcome()).isEqualTo("COMPLETED");
            assertThat(fixture.lastBinding.get()).as("scheduled reports must not invoke the model").isNull();
            assertThat(fixture.saved).hasSize(1);
            var stored = MAPPER.readTree(fixture.saved.get(0).payloadJson());
            assertThat(stored.path("blocks").get(0).path("report").path("total").path("value").asDouble()).isEqualTo(10);
            assertThat(stored.path("checkpointAt").asText()).isEqualTo(REQUEST.dueAt().toString());
            assertThat(stored.path("ownerKey").asText()).isEqualTo("owner");
            assertThat(MAPPER.readTree(executed.payloadJson()).path("observations").get(1).path("type").asText()).isEqualTo("report_artifact");
        }
    }

    @Test
    void ordinaryModelCompletionWithoutSubmissionIsPartialAndCancelledInvocationCannotPublish() throws Exception {
        try (Fixture fixture = new Fixture((bridge, binding) -> { })) {
            var executed = fixture.bridge.execute(IDENTITY, REQUEST, List.of("meter-1"), assignment("result"), () -> true);
            assertThat(executed.outcome()).isEqualTo("PARTIAL");
            assertThat(fixture.saved).isEmpty();
        }
        AtomicBoolean active = new AtomicBoolean(true);
        try (Fixture fixture = new Fixture((bridge, binding) -> active.set(false))) {
            assertThatThrownBy(() -> fixture.bridge.execute(IDENTITY, REQUEST, List.of("meter-1"), assignment("result"), active::get))
                    .isInstanceOf(BusinessException.class);
            assertThat(fixture.saved).isEmpty();
        }
    }

    @Test
    void scheduledReportDoesNotRequireModelConfiguration() throws Exception {
        try (Fixture fixture = new Fixture((bridge, binding) -> { }, false)) {
            var executed = fixture.bridge.execute(IDENTITY, REQUEST, List.of("meter-1"), assignment("report"), () -> true);
            assertThat(executed.outcome()).isEqualTo("COMPLETED");
            assertThat(fixture.lastBinding.get()).isNull();
            assertThat(fixture.saved).hasSize(1);
        }
    }

    @Test
    void scheduledReportRequiresStructuredEvidenceAndCannotSaveAfterCancellation() throws Exception {
        AtomicBoolean active = new AtomicBoolean(true);
        try (Fixture fixture = new Fixture((bridge, binding) -> { })) {
            assertThatThrownBy(() -> fixture.bridge.execute(IDENTITY, REQUEST, List.of("meter-1"),
                    Map.of("objective", "昨日用电", "output", "report"), () -> true))
                    .isInstanceOf(BusinessException.class);
            fixture.afterQuery = () -> active.set(false);
            assertThatThrownBy(() -> fixture.bridge.execute(IDENTITY, REQUEST, List.of("meter-1"),
                    assignment("report"), active::get)).isInstanceOf(BusinessException.class);
            assertThat(fixture.lastBinding.get()).isNull();
            assertThat(fixture.saved).isEmpty();
        }
    }

    private static Map<String, Object> assignment(String output) {
        return Map.of("objective", "统计检查点计划日期的前一日用电", "output", output,
                "spec", Map.of("period", Map.of("kind", "previous", "unit", "day"), "comparisons", List.of()));
    }
    private static Map<String, Object> call(Map<String, Object> binding, String tool, Map<String, Object> args) {
        var result = new LinkedHashMap<>(binding);
        result.put("toolName", tool); result.put("toolCallId", java.util.UUID.randomUUID().toString()); result.put("arguments", args);
        return result;
    }
    private static final class Fixture implements AutoCloseable {
        final List<ReportFact> saved = new ArrayList<>();
        final List<ObservationRequest> queries = new ArrayList<>();
        final AtomicReference<Map<String, Object>> lastBinding = new AtomicReference<>();
        final HttpServer server;
        final AgentCheckpointBridge bridge;
        Runnable afterQuery = () -> { };
        Fixture(BiConsumer<AgentCheckpointBridge, Map<String, Object>> script) throws Exception {
            this(script, true);
        }
        Fixture(BiConsumer<AgentCheckpointBridge, Map<String, Object>> script, boolean engineConfigured) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            AtomicReference<AgentCheckpointBridge> reference = new AtomicReference<>();
            server.createContext("/run", exchange -> {
                try {
                    Map<String, Object> binding = MAPPER.readValue(exchange.getRequestBody(), new TypeReference<>() { });
                    lastBinding.set(binding);
                    script.accept(reference.get(), binding);
                    byte[] response = "{\"type\":\"run.completed\"}\n".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response);
                } finally { exchange.close(); }
            });
            server.createContext("/cancel", exchange -> { exchange.sendResponseHeaders(404, -1); exchange.close(); });
            server.start();
            IotAdapter iot = new IotAdapter() {
                @Override public Mono<AuthorizedDeviceScope> loadAuthorizedDeviceScope(RequestIdentity identity) {
                    return Mono.just(AuthorizedDeviceScope.of(identity, IotSourceEnum.COMPANY, List.of(
                            new AuthorizedDevice("meter-1", "energy", "电表1", "26楼", true),
                            new AuthorizedDevice("meter-2", "energy", "电表2", "27楼", true))));
                }
            };
            ObservationAdapter observations = request -> {
                queries.add(request);
                return ReportEvidenceFixture.evidence().read(request).doOnNext(ignored -> afterQuery.run());
            };
            ReportFactStore reports = new ReportFactStore() {
                @Override public ReportFact save(ReportFact report) { saved.add(report); return report; }
                @Override public Optional<ReportFact> find(String id) { return saved.stream().filter(fact -> fact.reportId().equals(id)).findFirst(); }
            };
            bridge = AgentBusinessEngineFactory.checkpointBridge(MAPPER, observations,
                    ReportGenerationServiceFactory.evidence(iot, observations, CLOCK), reports, () -> null,
                    engineConfigured ? "http://127.0.0.1:" + server.getAddress().getPort() : "",
                    engineConfigured ? "fixture" : "", CLOCK);
            reference.set(bridge);
        }
        @Override public void close() { server.stop(0); }
    }
}
