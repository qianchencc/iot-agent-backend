package ltd.qianc.iotagent.action.internal;

import java.time.Clock;
import java.time.Duration;
import ltd.qianc.iotagent.action.ActionCommandGateway;
import ltd.qianc.iotagent.action.ActionDraftFacade;
import ltd.qianc.iotagent.action.ActionDraftStore;
import ltd.qianc.iotagent.audit.AuditEventStore;
import ltd.qianc.iotagent.conversation.ConversationEventStore;
import ltd.qianc.iotagent.iot.IotAdapter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Storage and control are explicit seams; absent infrastructure fails closed. */
@Configuration(proxyBeanMethods = false)
public class ActionDraftConfiguration {
    @Bean
    @ConditionalOnMissingBean(ActionDraftStore.class)
    ActionDraftStore actionDraftStore() {
        return new FailClosedActionDraftStore("草案事实存储未配置");
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
            ActionCommandGateway gateway,
            ObjectProvider<ConversationEventStore> events,
            ObjectProvider<AuditEventStore> audit,
            @Value("${iot.gateway.call-timeout-ms:10000}") long scopeTimeoutMs,
            @Value("${agent.action-draft-ttl-ms:600000}") long draftTtlMs) {
        return new ActionDraftFacade(mapper, store, iot, gateway, events.getIfAvailable(), audit.getIfAvailable(),
                Clock.systemUTC(), Duration.ofMillis(Math.max(1, scopeTimeoutMs)), Duration.ofMillis(Math.max(1, draftTtlMs)));
    }
}
