package ltd.qianc.iotagent.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import ltd.qianc.iotagent.conversation.ConversationEventFact;
import ltd.qianc.iotagent.conversation.ConversationEventStore;
import ltd.qianc.iotagent.conversation.ConversationRecord;
import ltd.qianc.iotagent.conversation.ConversationStore;
import ltd.qianc.iotagent.conversation.ConversationTurn;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "agent.storage.mysql.enabled=true",
        "agent.storage.redis.enabled=true",
        "iot.identity.verify=false",
        "spring.datasource.url=${AGENT_MYSQL_URL}",
        "spring.datasource.username=${AGENT_MYSQL_USERNAME}",
        "spring.datasource.password=${AGENT_MYSQL_PASSWORD}",
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
