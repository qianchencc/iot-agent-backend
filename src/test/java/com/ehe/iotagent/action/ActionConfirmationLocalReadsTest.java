package com.ehe.iotagent.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import com.ehe.iotagent.IotAgentBackendApplication;
import com.ehe.iotagent.auth.AccountPrincipalVerifier;
import com.ehe.iotagent.auth.PrincipalInfo;
import com.ehe.iotagent.iot.AuthorizedDevice;
import com.ehe.iotagent.iot.DeviceObservations;
import com.ehe.iotagent.iot.DeviceStateReadResult;
import com.ehe.iotagent.iot.EnergyHistoryFixture;
import com.ehe.iotagent.iot.IotAdapterFactory;
import com.ehe.iotagent.readmodel.ReadSnapshots;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = IotAgentBackendApplication.class, properties = {
        "iot.identity.verify=true", "agent.storage.mysql.enabled=false", "agent.heartbeat-ms=0"})
@AutoConfigureMockMvc
@Import(ActionConfirmationLocalReadsTest.Fixtures.class)
class ActionConfirmationLocalReadsTest {
    private static final AuthorizedDevice LIGHT = new AuthorizedDevice("fixture-light", "lighting", "测试灯", "测试区", false);
    @Autowired MockMvc http;
    @Autowired EnergyHistoryFixture local;
    @Autowired InMemoryActionDraftStore drafts;
    @Autowired ActionDraftFacade facade;
    @Autowired Dispatch dispatch;

    @ParameterizedTest
    @EnumSource(value = ActionDraftStatusEnum.class, names = {"ACCEPTED", "REJECTED", "INTERRUPTED"})
    void confirmationAndItsReplayKeepPublishedDirectoryAndStatesReadable(ActionDraftStatusEnum outcome) throws Exception {
        local.publish();
        dispatch.outcome = outcome;
        int beforeDispatch = dispatch.calls.get();
        var identity = local.identity();
        var principal = new PrincipalInfo(identity.authorization(), identity.tenantCode(), "fixture-principal", "fixture-realm");
        String id = "draft-" + outcome;
        var action = Map.of("id", "fixture-action", "deviceId", LIGHT.id(), "kind", "lighting",
                "command", Map.of("closeOrOpen", 1));
        drafts.create(new ActionDraftFact(id, 0, "PENDING_CONFIRMATION", local.mapper.writeValueAsString(Map.of(
                "source", "company", "tenantCode", principal.tenantCode(), "ownerKey", principal.ownerKey(),
                "actions", List.of(action), "selectedActionIds", List.of("fixture-action"))), id));
        int sourceCalls = local.directoryCalls.get();
        for (int attempt = 0; attempt < 2; attempt++) {
            http.perform(post("/api/agent/action-drafts/{id}/confirm", id).param("source", "company")
                            .header("Authorization", identity.authorization()).header("Tenantcode", identity.tenantCode()))
                    .andExpect(status().is(outcome == ActionDraftStatusEnum.ACCEPTED ? 200 : 502));
            // This is the same authorization-dependent directory entry used before every model run.
            assertThat(local.directory.loadScope(identity).device(LIGHT.id())).contains(LIGHT);
            assertThat(facade.deviceStates(principal, id, "company").get("states"))
                    .isEqualTo(Map.of(LIGHT.id(), false));
        }
        assertThat(dispatch.calls.get() - beforeDispatch).isEqualTo(1);
        assertThat(local.directoryCalls.get()).isEqualTo(sourceCalls);
        // An accepted receipt must not manufacture a new ordinary state. Only background publication changes it.
        local.currentStates = new DeviceStateReadResult(Map.of(LIGHT.id(), true), null);
        local.clock.advance(61);
        local.directory.synchronizeDue();
        assertThat(facade.deviceStates(principal, id, "company").get("states")).isEqualTo(Map.of(LIGHT.id(), true));
        local.currentStates = new DeviceStateReadResult(Map.of(LIGHT.id(), false), null);
        local.clock.advance(61);
        local.directory.synchronizeDue();
    }

    static final class Dispatch implements ActionCommandGateway {
        final AtomicInteger calls = new AtomicInteger();
        ActionDraftStatusEnum outcome;
        public ActionDispatchResult dispatch(com.ehe.iotagent.iot.RequestIdentity identity,
                com.ehe.iotagent.iot.AuthorizedDeviceScope scope, String commands) {
            calls.incrementAndGet();
            String receipt = outcome == ActionDraftStatusEnum.REJECTED ? "REJECTED"
                    : outcome == ActionDraftStatusEnum.INTERRUPTED ? "UNKNOWN" : "ACCEPTED";
            return new ActionDispatchResult(outcome, "{\"receipts\":[{\"actionId\":\"fixture-action\",\"outcome\":\""
                    + receipt + "\"}]}", true);
        }
    }

    @TestConfiguration
    static class Fixtures {
        @Bean(destroyMethod = "close") EnergyHistoryFixture confirmationLocalData() {
            var local = new EnergyHistoryFixture();
            local.catalog = List.of(LIGHT);
            local.meters = List.of();
            local.currentStates = new DeviceStateReadResult(Map.of(LIGHT.id(), false), null);
            return local;
        }
        @Bean @Primary ReadSnapshots confirmationSnapshots(EnergyHistoryFixture local) { return local.snapshots(); }
        @Bean @Primary AccountPrincipalVerifier confirmationIdentity() {
            return presented -> new PrincipalInfo(presented.authorization(), presented.tenantCode(), "fixture-principal", "fixture-realm");
        }
        @Bean InMemoryActionDraftStore confirmationDrafts() { return new InMemoryActionDraftStore(); }
        @Bean Dispatch confirmationDispatch() { return new Dispatch(); }
        @Bean @Primary ActionDraftFacade confirmationFacade(EnergyHistoryFixture local, InMemoryActionDraftStore drafts,
                Dispatch dispatch, com.ehe.iotagent.iot.DeviceCandidateSetStore candidates) {
            var observations = DeviceObservations.jdbc(local.jdbc, local.transactions, local.mapper, local.directory,
                    EnergyHistoryFixture.GATEWAY, local.clock);
            var live = new TestIotAdapter(List.of(LIGHT), identity -> Map.of(LIGHT.id(), false));
            var iot = IotAdapterFactory.localReads(live, () -> local.directory, () -> observations, candidates);
            return new ActionDraftFacade(local.mapper, drafts, iot, dispatch, null, null, null, null,
                    local.snapshots(), local.clock, Duration.ofSeconds(2), Duration.ofMinutes(10), null);
        }
    }
}
