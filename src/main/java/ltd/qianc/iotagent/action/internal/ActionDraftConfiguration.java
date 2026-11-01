package ltd.qianc.iotagent.action.internal;

import java.time.Clock;
import java.time.Duration;
import ltd.qianc.iotagent.action.ActionCommandGateway;
import ltd.qianc.iotagent.action.ActionDraftFacade;
import ltd.qianc.iotagent.action.ActionDraftStore;
import ltd.qianc.iotagent.action.ActionReadbackStore;
import ltd.qianc.iotagent.action.ActionReceiptStore;
import ltd.qianc.iotagent.audit.AuditEventStore;
import ltd.qianc.iotagent.conversation.ConversationEventStore;
import ltd.qianc.iotagent.iot.DeviceCandidateSetStore;
import ltd.qianc.iotagent.iot.IotAdapter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Storage and control are explicit seams; absent infrastructure fails closed. */
@Configuration(proxyBeanMethods = false)
public class ActionDraftConfiguration {
    @Bean
    @ConditionalOnProperty(name = "agent.storage.mysql.enabled", havingValue = "false", matchIfMissing = true)
    @ConditionalOnMissingBean(ActionDraftStore.class)
    ActionDraftStore actionDraftStore() {
        return new FailClosedActionDraftStore("草案事实存储未配置");
    }

    @Bean
    @ConditionalOnProperty(name = "iot.gateway.base-url")
    @ConditionalOnMissingBean(ActionCommandGateway.class)
    ActionCommandGateway webClientActionCommandGateway(
            ObjectMapper mapper,
            org.springframework.web.reactive.function.client.WebClient.Builder builder,
            ltd.qianc.iotagent.iot.IotAdapter iot,
            @Value("${iot.gateway.base-url}") String baseUrl,
            @Value("${iot.gateway.call-timeout-ms:30000}") long timeoutMs,
            @Value("${iot.gateway.readback-wait-ms:6000}") long readbackWaitMs,
            @Value("${iot.gateway.company-lighting-down-path:/micro-api/api/office/app/device/lighting/down}") String lightingPath,
            @Value("${iot.gateway.company-access-down-path:/micro-api/api/office/app/device/access/down}") String accessPath,
            @Value("${iot.gateway.company-ac-down-path:/micro-api/api/office/app/device/ac/down}") String acPath) {
        return new WebClientActionCommandGateway(mapper, builder.baseUrl(baseUrl).build(), iot,
                Duration.ofMillis(Math.max(1, timeoutMs)), lightingPath, accessPath, acPath,
                Duration.ofMillis(Math.max(0, readbackWaitMs)));
    }

    @Bean
    @ConditionalOnProperty(name = "agent.control.mode", havingValue = "fixture")
    ActionCommandGateway fixtureActionCommandGateway(
            ObjectMapper mapper,
            @Value("${agent.control.fixture.outcome:accepted}") String outcome,
            @Value("${agent.control.fixture.readback:confirmed}") String readback) {
        return new FixtureActionCommandGateway(mapper, outcome, readback);
    }

    @Bean
    @ConditionalOnMissingBean(ActionCommandGateway.class)
    ActionCommandGateway actionCommandGateway() {
        return new FailClosedActionCommandGateway("设备控制适配器未配置");
    }

    @Bean
    ActionDraftFacade actionDraftFacade(
            ObjectMapper mapper,
            ActionDraftStore store,
            IotAdapter iot,
            DeviceCandidateSetStore candidates,
            ActionCommandGateway gateway,
            ObjectProvider<ConversationEventStore> events,
            ObjectProvider<AuditEventStore> audit,
            ObjectProvider<ActionReceiptStore> receipts,
            ObjectProvider<ActionReadbackStore> readbacks,
            @Value("${iot.gateway.call-timeout-ms:10000}") long scopeTimeoutMs,
            @Value("${agent.action-draft-ttl-ms:600000}") long draftTtlMs) {
        return new ActionDraftFacade(mapper, store, iot, candidates, gateway, events.getIfAvailable(),
                audit.getIfAvailable(), receipts.getIfAvailable(), readbacks.getIfAvailable(),
                Clock.systemUTC(), Duration.ofMillis(Math.max(1, scopeTimeoutMs)), Duration.ofMillis(Math.max(1, draftTtlMs)));
    }
}
