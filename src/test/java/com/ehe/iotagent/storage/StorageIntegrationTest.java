package com.ehe.iotagent.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ehe.iotagent.conversation.ConversationEventFact;
import com.ehe.iotagent.conversation.ConversationEventStore;
import com.ehe.iotagent.conversation.ConversationRecord;
import com.ehe.iotagent.conversation.ConversationStore;
import com.ehe.iotagent.conversation.ConversationTurn;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "agent.storage.mysql.enabled=true",
        "agent.storage.redis.enabled=true",
        "iot.identity.verify=false",
        "iot.gateway.base-url=https://fixture.example.invalid",
        "agent.local-data.credential-key=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        "spring.datasource.url=${AGENT_MYSQL_URL}",
        "spring.datasource.username=${AGENT_MYSQL_USERNAME}",
        "spring.datasource.password=${AGENT_MYSQL_PASSWORD}",
        "spring.datasource.hikari.maximum-pool-size=2",
        "spring.datasource.hikari.minimum-idle=0",
        "spring.datasource.hikari.connection-timeout=300",
        "spring.data.redis.url=${AGENT_REDIS_URL}"
})
@EnabledIfEnvironmentVariable(named = "AGENT_MYSQL_USERNAME", matches = ".+")
@EnabledIfEnvironmentVariable(named = "AGENT_MYSQL_PASSWORD", matches = ".+")
@EnabledIfEnvironmentVariable(named = "AGENT_REDIS_URL", matches = ".+")
class StorageIntegrationTest {
    @Autowired
    private ConversationStore conversations;
    @Autowired
    private ConversationEventStore events;
    @Autowired
    private RedisCoordinationStore coordination;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private javax.sql.DataSource dataSource;

    @Test
    void configuredPoolCapacityBoundsConcurrentBorrowers() throws Exception {
        try (var first = dataSource.getConnection(); var second = dataSource.getConnection()) {
            assertThat(first.isValid(1)).isTrue();
            assertThat(second.isValid(1)).isTrue();
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> {
                try (var excess = dataSource.getConnection()) {
                    excess.isValid(1);
                }
            }).isInstanceOf(java.sql.SQLTransientConnectionException.class);
        }
    }

    @Test
    void persistsConversationEventsAndRedisCoordination() throws Exception {
        String tenant = "storage-test-" + UUID.randomUUID();
        String owner = "owner-" + UUID.randomUUID();
        Instant now = Instant.now();
        ConversationRecord conversation = conversations.create(tenant, owner, "office", now);
        ConversationTurn turn = conversations.startTurn(conversation.id(), "storage contract", now, "idem-" + UUID.randomUUID());
        ConversationEventFact event = new ConversationEventFact(conversation.id(), turn.id(), 1, "message.completed", "{\"content\":\"ok\"}", "event-" + UUID.randomUUID());

        assertThat(events.append(event)).isEqualTo(event);
        assertThat(events.read(conversation.id())).hasSize(1);
        assertThat(mapper.readTree(events.read(conversation.id()).getFirst().payloadJson()))
                .isEqualTo(mapper.readTree(event.payloadJson()));
        ConversationRecord current = conversations.current(tenant, owner, "office", now.minusSeconds(1)).orElseThrow();
        assertThat(current.id()).isEqualTo(conversation.id());
        assertThat(current.tenantCode()).isEqualTo(tenant);
        assertThat(current.ownerKey()).isEqualTo(owner);
        assertThat(current.source()).isEqualTo("office");

        String key = "storage-test:" + UUID.randomUUID();
        coordination.put(key, "value", Duration.ofMinutes(1));
        assertThat(coordination.get(key)).contains("value");
        assertThat(coordination.markIdempotent(key, Duration.ofMinutes(1))).isTrue();
        assertThat(coordination.markIdempotent(key, Duration.ofMinutes(1))).isFalse();
        assertThat(coordination.delete(key)).isTrue();
    }
}
