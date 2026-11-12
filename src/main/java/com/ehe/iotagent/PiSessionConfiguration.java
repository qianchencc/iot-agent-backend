package com.ehe.iotagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import com.ehe.iotagent.conversation.ConversationFacade;
import com.ehe.iotagent.pisession.PiSessionService;
import com.ehe.iotagent.pisession.PiSessionServiceFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

// Composition root: the storage module receives identity resolution, never business facades.
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="agent.storage.mysql.enabled",havingValue="true")
public class PiSessionConfiguration {
    @Bean @DependsOn("storageSchemaInitializer")
    PiSessionService piSessionService(JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper mapper,
            ObjectProvider<ConversationFacade> conversations,
            ObjectProvider<com.ehe.iotagent.agent.AgentCheckpointBridge> checkpoints) {
        return PiSessionServiceFactory.jdbc(jdbc,transactions,mapper,r->{
            var checkpoint = checkpoints.getIfAvailable();
            if (checkpoint != null) {
                var bound = checkpoint.sessionContext(r);
                if (bound.isPresent()) return bound.get();
            }
            var c=conversations.getObject().toolContext((String)r.get("toolContextId"),(String)r.get("conversationId"),(String)r.get("turnId"),(String)r.get("runId"));
            return new PiSessionService.Context(c.principal().tenantCode(),c.principal().ownerKey(),c.source(),c.conversationId(),c.runId());
        },Clock.systemUTC());
    }
}
