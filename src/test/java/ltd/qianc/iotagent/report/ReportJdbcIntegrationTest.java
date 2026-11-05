package ltd.qianc.iotagent.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ltd.qianc.iotagent.conversation.ReportGenerationPort;
import ltd.qianc.iotagent.iot.AuthorizedDevice;
import ltd.qianc.iotagent.iot.AuthorizedDeviceScope;
import ltd.qianc.iotagent.iot.IotAdapter;
import ltd.qianc.iotagent.iot.IotSourceEnum;
import ltd.qianc.iotagent.iot.RequestIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import reactor.core.publisher.Mono;

/** Optional local MySQL evidence for durable report facts and idempotency across service reconstruction. */
@SpringBootTest(properties = {
        "agent.storage.mysql.enabled=true",
        "agent.storage.redis.enabled=false",
        "iot.identity.verify=false"
})
@EnabledIfEnvironmentVariable(named = "AGENT_MYSQL_URL", matches = ".+")
class ReportJdbcIntegrationTest {
    private final RequestIdentity identity = new RequestIdentity("Bearer report-jdbc", "REPORT_JDBC_TEST",
            IotSourceEnum.COMPANY, "owner-report-jdbc");

    @Autowired
    private ReportFactStore facts;

    @Autowired
    private ObjectMapper mapper;

    @Test
    void mysqlFactSurvivesServiceReconstructionAndDuplicateRequestsRemainOriginal() {
        IotAdapter scope = new FixtureScope();
        String conversation = "conversation-" + UUID.randomUUID();
        String turn = "turn-" + UUID.randomUUID();
        String request = "request-" + UUID.randomUUID();

        ReportGenerationPort.GeneratedReport first = ReportGenerationServiceFactory.local(facts, mapper, scope, ReportEvidenceFixture.evidence())
                .generate(identity, conversation, turn, request, ReportEvidenceFixture.energy("meter-jdbc", false));
        assertThat(facts.find(first.reportId())).isPresent();

        ReportGenerationPort.GeneratedReport afterRestart = ReportGenerationServiceFactory.local(facts, mapper, scope, ReportEvidenceFixture.evidence())
                .generate(identity, conversation, turn, request, ReportEvidenceFixture.energy("meter-jdbc", false));
        assertThat(afterRestart.reused()).isTrue();
        assertThat(afterRestart.report()).containsEntry("status", "completed");
        assertThat(facts.find(first.reportId()).orElseThrow().payloadJson())
                .isEqualTo(facts.find(afterRestart.reportId()).orElseThrow().payloadJson());
    }

    private static final class FixtureScope implements IotAdapter {
        @Override
        public Mono<AuthorizedDeviceScope> loadAuthorizedDeviceScope(RequestIdentity request) {
            return Mono.just(new AuthorizedDeviceScope() {
                @Override
                public RequestIdentity identity() { return request; }

                @Override
                public IotSourceEnum source() { return IotSourceEnum.COMPANY; }

                @Override
                public List<AuthorizedDevice> devices() {
                    return List.of(new AuthorizedDevice("meter-jdbc", "energy", "JDBC meter", "Building", false));
                }
            });
        }
    }
}
