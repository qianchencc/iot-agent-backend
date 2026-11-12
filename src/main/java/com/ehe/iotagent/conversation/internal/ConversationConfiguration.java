package com.ehe.iotagent.conversation.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import com.ehe.iotagent.conversation.ConversationDrafts;
import com.ehe.iotagent.conversation.ConversationTaskCards;
import com.ehe.iotagent.conversation.AgentEngineBridge;
import com.ehe.iotagent.conversation.AgentRunPolicyProvider;
import com.ehe.iotagent.conversation.ConversationEventStore;
import com.ehe.iotagent.conversation.ConversationFacade;
import com.ehe.iotagent.conversation.ConversationFacade;
import com.ehe.iotagent.conversation.ConversationStore;
import com.ehe.iotagent.audit.AuditEventStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import com.ehe.iotagent.admin.AdminConfigurationStore;
import com.ehe.iotagent.admin.AdminSettingReader;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;

@Configuration
public class ConversationConfiguration {
    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean(name = "conversationExecutor")
    ExecutorService conversationExecutor() {
        return new ThreadPoolExecutor(0, 256, 60L, TimeUnit.SECONDS, new SynchronousQueue<>(),
                Thread.ofVirtual().name("conversation-run-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean(name = "conversationScheduler")
    ScheduledExecutorService conversationScheduler() {
        return new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "conversation-scheduler");
            thread.setDaemon(true);
            return thread;
        });
    }
    @Bean @ConditionalOnProperty(name = "agent.storage.mysql.enabled", havingValue = "false", matchIfMissing = true)
    @ConditionalOnMissingBean(ConversationStore.class)
    ConversationStore conversationStore() { return new FailClosedConversationStore(); }
    @Bean @ConditionalOnProperty(name = "agent.storage.mysql.enabled", havingValue = "false", matchIfMissing = true)
    @ConditionalOnMissingBean(ConversationEventStore.class)
    ConversationEventStore conversationEventStore() { return new FailClosedConversationEventStore(); }
    @Bean
    @ConditionalOnMissingBean(AgentEngineBridge.class)
    AgentEngineBridge agentEngineBridge(ObjectMapper mapper,
            @Qualifier("conversationExecutor") ExecutorService executor,
            @Qualifier("conversationScheduler") ScheduledExecutorService conversationScheduler,
            @Value("${agent.bridge.base-url:}") String baseUrl,
            @Value("${agent.bridge.token:}") String bridgeToken,
            @Value("${agent.bridge.run-timeout-ms:120000}") long timeoutMs,
            AgentRunPolicyProvider policies) {
        return new HttpAgentEngineBridge(mapper, baseUrl, bridgeToken, executor,
                java.time.Duration.ofMillis(timeoutMs), conversationScheduler, policies);
    }
    @Bean
    ConversationFacade conversationFacade(ObjectMapper mapper, ConversationStore conversations, ConversationEventStore events,
            AgentEngineBridge bridge, ConversationDrafts drafts, ConversationTaskCards taskCards,
            @Qualifier("conversationExecutor") ExecutorService executor,
            @Qualifier("conversationScheduler") ScheduledExecutorService conversationScheduler,
            @Value("${agent.heartbeat-ms:15000}") long heartbeatMs,
            ObjectProvider<AdminConfigurationStore> adminConfigurations,
            ObjectProvider<AuditEventStore> audit) {
        return new ConversationFacade(mapper, conversations, events, bridge, executor, conversationScheduler,
                AdminSettingReader.longSetting(adminConfigurations, "heartbeatMs", 0, 60_000, heartbeatMs),
                drafts, taskCards, audit.getIfAvailable());
    }
}
