package com.ehe.iotagent.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import com.ehe.iotagent.admin.AdminConfigurationStore;
import com.ehe.iotagent.auth.PrincipalInfo;
import com.ehe.iotagent.conversation.AgentRunRequest;
import com.ehe.iotagent.conversation.AgentToolContext;
import com.ehe.iotagent.conversation.ConversationFacade;
import org.junit.jupiter.api.Test;

class AgentInstructionRunSnapshotContractTest {
    @Test
    void savedChangesAffectTheNextRunWithoutChangingTheAlreadyPreparedRun() {
        var principal = new PrincipalInfo("Bearer fixture", "TENANT", "owner", "fixture");
        var conversations = mock(ConversationFacade.class);
        when(conversations.toolContext(anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(call -> new AgentToolContext(principal, call.getArgument(3), call.getArgument(1),
                        call.getArgument(2), "office", null, Map.of()));
        var configurations = mock(AdminConfigurationStore.class);
        var saved = new AtomicReference<Map<String, Object>>(new LinkedHashMap<>(Map.of(
                "officeBaseInstructions", "办公室基础", "officeInstructions", "已有办公追加",
                "replyInstructions", "已有回复风格", "sharedInstructions", "公共规则")));
        when(configurations.active()).thenAnswer(call -> saved.get());
        var provider = AgentBusinessEngineFactory.policyProvider(
                () -> conversations, null, () -> configurations, Clock.systemUTC(), 6);

        Map<?, ?> prepared = (Map<?, ?>) provider.prepare(request("first")).request().get("definition");
        assertThat(prepared.get("instructions")).isEqualTo(
                "办公室基础\n\n已有办公追加\n\n已有回复风格\n\n公共规则\n\n");
        var changed = new LinkedHashMap<>(saved.get());
        changed.put("officeBaseInstructions", "修改后的办公室基础");
        changed.put("sharedInstructions", "修改后的公共规则");
        saved.set(changed);
        Map<?, ?> next = (Map<?, ?>) provider.prepare(request("next")).request().get("definition");

        assertThat(next.get("instructions")).isEqualTo(
                "修改后的办公室基础\n\n已有办公追加\n\n已有回复风格\n\n修改后的公共规则\n\n");
        assertThat(prepared.get("instructions")).isEqualTo(
                "办公室基础\n\n已有办公追加\n\n已有回复风格\n\n公共规则\n\n");
    }

    private static AgentRunRequest request(String run) {
        return new AgentRunRequest(run, "conversation", "turn-" + run, "office", "你好", "TENANT",
                null, Map.of(), "context-" + run);
    }
}
