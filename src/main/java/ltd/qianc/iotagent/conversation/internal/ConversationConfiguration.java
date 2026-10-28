package ltd.qianc.iotagent.conversation.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import ltd.qianc.iotagent.conversation.AgentEngineBridge;
import ltd.qianc.iotagent.conversation.ConversationEventStore;
import ltd.qianc.iotagent.conversation.ConversationFacade;
import ltd.qianc.iotagent.conversation.ConversationStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
    @Bean @ConditionalOnMissingBean(ConversationStore.class)
    ConversationStore conversationStore() { return new FailClosedConversationStore(); }
    @Bean @ConditionalOnMissingBean(ConversationEventStore.class)
    ConversationEventStore conversationEventStore() { return new FailClosedConversationEventStore(); }
    @Bean @ConditionalOnMissingBean(AgentEngineBridge.class)
    AgentEngineBridge agentEngineBridge(ObjectMapper mapper,
            @Qualifier("conversationExecutor") ExecutorService executor,
            @Qualifier("conversationScheduler") ScheduledExecutorService conversationScheduler,
            @Value("${agent.bridge.base-url:}") String baseUrl,
            @Value("${agent.bridge.run-timeout-ms:120000}") long timeoutMs) {
        return new HttpAgentEngineBridge(mapper, baseUrl, executor, java.time.Duration.ofMillis(timeoutMs), conversationScheduler);
    }
    @Bean
    ConversationFacade conversationFacade(ObjectMapper mapper, ConversationStore conversations,
            ConversationEventStore events, AgentEngineBridge bridge,
            @Qualifier("conversationExecutor") ExecutorService executor,
            @Qualifier("conversationScheduler") ScheduledExecutorService conversationScheduler,
            @Value("${agent.heartbeat-ms:15000}") long heartbeatMs) {
        return new ConversationFacade(mapper, conversations, events, bridge, executor, conversationScheduler, heartbeatMs);
    }
}
