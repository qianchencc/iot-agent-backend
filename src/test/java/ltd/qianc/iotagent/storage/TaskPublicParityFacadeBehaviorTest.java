package ltd.qianc.iotagent.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import ltd.qianc.iotagent.auth.PrincipalInfo;
import ltd.qianc.iotagent.error.BusinessException;
import ltd.qianc.iotagent.iot.AuthorizedDevice;
import ltd.qianc.iotagent.iot.AuthorizedDeviceScope;
import ltd.qianc.iotagent.iot.IotAdapter;
import ltd.qianc.iotagent.iot.IotSourceEnum;
import ltd.qianc.iotagent.iot.RequestIdentity;
import ltd.qianc.iotagent.task.TaskAtomicTransactions;
import ltd.qianc.iotagent.task.TaskExecutionIdentityStore.TaskExecutionIdentity;
import ltd.qianc.iotagent.task.TaskExecutionIdentityStore;
import ltd.qianc.iotagent.task.TaskFacade;
import ltd.qianc.iotagent.task.TaskFact;
import ltd.qianc.iotagent.task.TaskFactStore;
import ltd.qianc.iotagent.task.TaskNotificationFact;
import ltd.qianc.iotagent.task.TaskNotificationStore;
import ltd.qianc.iotagent.task.TaskPlanFact;
import ltd.qianc.iotagent.task.TaskPlanStore;
import ltd.qianc.iotagent.task.TaskProposalFact;
import ltd.qianc.iotagent.task.TaskProposalStore;
import ltd.qianc.iotagent.task.TaskScheduler;
import ltd.qianc.iotagent.task.TaskExecutionIdentityStoreFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;

/**
 * Consumer-visible behavior of the public task boundary over the real public
 * store seams: confirmation identity reuse, transactional rollback and replay,
 * Runtime cancel authorization and the cancellation inbox notification.
 */
class TaskPublicParityFacadeBehaviorTest {
    private static final Instant NOW = Instant.parse("2030-01-01T08:00:00Z");
    private static final PrincipalInfo PRINCIPAL = new PrincipalInfo("Bearer parity", "TENANT");

    private final ObjectMapper mapper = new ObjectMapper();
    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private TaskProposalStore proposals;
    private TaskFactStore tasks;
    private TaskPlanStore plans;
    private ltd.qianc.iotagent.task.TaskPhaseStore phaseStore;
    private TaskAtomicTransactions atomic;

    @BeforeEach
    void setUp() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:task_parity_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        transactions = new TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(source));
        atomic = TaskAtomicSupportFactory.atomicTransactions(transactions);
        jdbc.execute("CREATE TABLE task_proposals (id BIGINT AUTO_INCREMENT PRIMARY KEY, proposal_id VARCHAR(64) NOT NULL UNIQUE, tenant_code VARCHAR(128) NOT NULL, owner_key CHAR(64) NOT NULL, conversation_id VARCHAR(64) NOT NULL, turn_id VARCHAR(64) NOT NULL, version BIGINT NOT NULL, status VARCHAR(32) NOT NULL, payload_json VARCHAR(20000) NOT NULL, idempotency_key VARCHAR(256) NOT NULL, kind VARCHAR(32) NOT NULL DEFAULT 'TASK', target_json VARCHAR(20000) NULL, before_json VARCHAR(20000) NULL, after_json VARCHAR(20000) NULL, operation VARCHAR(32) NOT NULL DEFAULT 'create', source VARCHAR(16) NOT NULL DEFAULT 'office', expires_at DATETIME NULL, create_time TIMESTAMP NOT NULL, update_time TIMESTAMP NOT NULL, UNIQUE(tenant_code, owner_key, idempotency_key))");
        jdbc.execute("CREATE TABLE task_facts (id BIGINT AUTO_INCREMENT PRIMARY KEY, task_id VARCHAR(64) NOT NULL UNIQUE, version BIGINT NOT NULL, status VARCHAR(32) NOT NULL, payload_json VARCHAR(20000) NOT NULL, idempotency_key VARCHAR(256) NOT NULL UNIQUE, create_time TIMESTAMP NOT NULL, update_time TIMESTAMP NOT NULL)");
        jdbc.execute("CREATE TABLE task_phases (id BIGINT AUTO_INCREMENT PRIMARY KEY, phase_id VARCHAR(64) NOT NULL UNIQUE, task_id VARCHAR(64) NOT NULL, source VARCHAR(16) NOT NULL, version BIGINT NOT NULL, status VARCHAR(32) NOT NULL, due_at TIMESTAMP NOT NULL, payload_json VARCHAR(20000) NOT NULL, definition_json VARCHAR(20000) NULL, result_json VARCHAR(20000) NULL, failure_reason VARCHAR(1024) NULL, claimed_at TIMESTAMP NULL, ended_at TIMESTAMP NULL, create_time TIMESTAMP NOT NULL, update_time TIMESTAMP NOT NULL)");
        jdbc.execute("CREATE TABLE task_plans (id BIGINT AUTO_INCREMENT PRIMARY KEY, plan_id VARCHAR(64) NOT NULL UNIQUE, tenant_code VARCHAR(64) NOT NULL, owner_key VARCHAR(64) NOT NULL, source VARCHAR(16) NOT NULL, version BIGINT NOT NULL, status VARCHAR(32) NOT NULL, next_at DATETIME NULL, next_date DATE NULL, repeat_json VARCHAR(4000) NULL, payload_json VARCHAR(20000) NOT NULL, pause_reason VARCHAR(1024) NULL, idempotency_key VARCHAR(256) NOT NULL UNIQUE, create_time TIMESTAMP NOT NULL, update_time TIMESTAMP NOT NULL)");
        jdbc.execute("CREATE TABLE task_plan_occurrences (id BIGINT AUTO_INCREMENT PRIMARY KEY, plan_id VARCHAR(64) NOT NULL, occurrence_date DATE NOT NULL, task_id VARCHAR(64) NULL, status VARCHAR(32) NOT NULL, override_payload_json VARCHAR(20000) NULL, version BIGINT NOT NULL DEFAULT 1, create_time TIMESTAMP NOT NULL, update_time TIMESTAMP NOT NULL, UNIQUE(plan_id, occurrence_date))");
        jdbc.execute("CREATE TABLE task_execution_identities (owner_id VARCHAR(64) NOT NULL PRIMARY KEY, owner_kind VARCHAR(16) NOT NULL, tenant_code VARCHAR(128) NOT NULL, owner_key CHAR(64) NOT NULL, payload VARCHAR(4000) NOT NULL, create_time TIMESTAMP NOT NULL, update_time TIMESTAMP NOT NULL)");
        proposals = TaskFactStoresFactory.taskProposals(jdbc, transactions, mapper);
        tasks = TaskFactStoresFactory.taskFacts(jdbc, transactions, mapper);
        plans = TaskFactStoresFactory.taskPlans(jdbc, transactions, mapper);
        phaseStore = TaskFactStoresFactory.taskPhases(jdbc, transactions, mapper);
    }

    @Test
    void editingOnlyOneOccurrenceMovesThePersistedPointerWithoutChangingTheSeries() throws Exception {
        plans.save(new TaskPlanFact("fixture-pointer", 1, "ENABLED", planPayload(), "fixture-pointer-key"));
        var original = plans.find("fixture-pointer").orElseThrow();
        var changed = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(original.payloadJson());
        ((com.fasterxml.jackson.databind.node.ObjectNode) changed.path("phases").get(0)).put("at", "2030-01-02T13:30:00Z");
        var outcome = plans.overrideOccurrenceIfVersion("fixture-pointer", java.time.LocalDate.parse("2030-01-02"),
                1, changed.toString(), NOW);
        assertThat(outcome).isPresent();
        assertThat(plans.pointer("fixture-pointer").orElseThrow().nextAt()).isEqualTo(Instant.parse("2030-01-02T13:30:00Z"));
        assertThat(plans.find("fixture-pointer").orElseThrow().payloadJson()).isEqualTo(original.payloadJson());
    }

    @Test
    void confirmedTaskKeepsVerifiedCreatorBindingInsteadOfSuggestedIdentity() throws Exception {
        PrincipalInfo verified = new PrincipalInfo("Bearer verified", "TENANT", "creator-1", "https://identity.example/");
        String payload = oneTimePayload().replace(PRINCIPAL.ownerKey(), verified.ownerKey());
        var suggested = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(payload);
        suggested.put("principalId", "forged-member").put("identityRealm", "https://forged.example/");
        proposals.save(new TaskProposalFact("binding-proposal", 1, "PENDING_CONFIRMATION", suggested.toString(),
                "binding-key", "TASK", "{}", "{}", suggested.toString(), "create", "TENANT",
                verified.ownerKey(), "office", NOW.plusSeconds(600)));
        facade(null).confirmProposal(verified, "binding-proposal", 1, "office");
        JsonNode confirmed = mapper.readTree(tasks.find("binding-proposal").orElseThrow().payloadJson());
        assertThat(confirmed.path("principalId").asText()).isEqualTo("creator-1");
        assertThat(confirmed.path("identityRealm").asText()).isEqualTo("https://identity.example/");
    }

    private TaskFacade facade(TaskExecutionIdentityStore identities) {
        return new TaskFacade(mapper, tasks, proposals, plans, mock(TaskNotificationStore.class),
                TaskSchedulerFactory.jdbc(jdbc, mapper), null, null, identities, null,
                mock(ltd.qianc.iotagent.readmodel.ReadSnapshots.class), atomic,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static String oneTimePayload() {
        return "{\"source\":\"office\",\"tenantCode\":\"TENANT\",\"ownerKey\":\"" + PRINCIPAL.ownerKey()
                + "\",\"label\":\"夜间关灯\",\"creatorName\":\"张三\","
                + "\"phases\":[{\"phaseId\":\"phase-1\",\"at\":\"2030-01-01T09:00:00Z\",\"actions\":[]}]}";
    }

    @Test
    void confirmProposalRollsBackIdentityFailureAndReplaysToTheSameProposalId() {
        AtomicInteger failures = new AtomicInteger(1);
        TaskExecutionIdentityStore failing = new TaskExecutionIdentityStore() {
            @Override public void saveForTask(String taskId, TaskExecutionIdentity identity) {
                if (failures.getAndDecrement() > 0) throw new IllegalStateException("identity store down");
            }
            @Override public void saveForPlan(String planId, TaskExecutionIdentity identity) { }
            @Override public Optional<TaskExecutionIdentity> loadForTask(String taskId) { return Optional.empty(); }
        };
        proposals.save(new TaskProposalFact("proposal-reuse", 1, "PENDING_CONFIRMATION",
                oneTimePayload(), "idem-reuse", "TASK", "{}", "{}", oneTimePayload(), "create",
                "TENANT", PRINCIPAL.ownerKey(), "office", Instant.parse("2030-01-02T00:00:00Z")));
        TaskFacade facade = facade(failing);

        assertThatThrownBy(() -> facade.confirmProposal(PRINCIPAL, "proposal-reuse", 1, "office"))
                .isInstanceOf(RuntimeException.class);

        assertThat(jdbc.queryForObject("SELECT status FROM task_proposals WHERE proposal_id='proposal-reuse'", String.class))
                .isEqualTo("PENDING_CONFIRMATION");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_facts", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_phases", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_execution_identities", Integer.class)).isZero();

        Map<String, Object> receipt = facade.confirmProposal(PRINCIPAL, "proposal-reuse", 1, "office");
        assertThat(receipt).containsEntry("taskId", "proposal-reuse").containsEntry("status", "scheduled");
        assertThat(jdbc.queryForObject("SELECT task_id FROM task_facts WHERE idempotency_key='task:proposal-reuse'", String.class))
                .isEqualTo("proposal-reuse");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_phases WHERE task_id='proposal-reuse'", Integer.class))
                .isEqualTo(1);

        Map<String, Object> card = facade.conversationProposalState(PRINCIPAL, "proposal-reuse", "office");
        assertThat(card).containsEntry("created", true)
                .containsEntry("taskId", "proposal-reuse")
                .containsEntry("status", "SCHEDULED");
    }

    @Test
    void confirmChangeRollsBackThePlanWriteWhenTheProposalMarkFails() {
        plans.save(new TaskPlanFact("plan-cas", 1, "ENABLED", planPayload(), "plan-cas-key"));
        proposals.save(new TaskProposalFact("change-cas", 1, "PENDING_CONFIRMATION", "{}", "idem-cas",
                "CHANGE", "{\"type\":\"task_plan\",\"planId\":\"plan-cas\"}", planPayload(), planPayload(),
                "pause", "TENANT", PRINCIPAL.ownerKey(), "office", Instant.parse("2030-01-02T00:00:00Z")));
        TaskProposalStore sabotaged = new TaskProposalStore() {
            @Override public TaskProposalFact save(TaskProposalFact value) { return proposals.save(value); }
            @Override public Optional<TaskProposalFact> find(String id) { return proposals.find(id); }
            @Override public Optional<TaskProposalFact> findByIdempotencyKey(String key) {
                return proposals.findByIdempotencyKey(key);
            }
            @Override public Optional<TaskProposalFact> updateIfVersion(TaskProposalFact replacement, long expectedVersion) {
                if ("CONFIRMED".equals(replacement.status())) return Optional.empty();
                return proposals.updateIfVersion(replacement, expectedVersion);
            }
        };
        TaskFacade facade = new TaskFacade(mapper, tasks, sabotaged, plans, mock(TaskNotificationStore.class),
                TaskSchedulerFactory.jdbc(jdbc, mapper), null, null, null, null,
                mock(ltd.qianc.iotagent.readmodel.ReadSnapshots.class), atomic,
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> facade.confirmChange(PRINCIPAL, "change-cas", 1, "office"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("任务提案已变化");

        assertThat(jdbc.queryForObject("SELECT version FROM task_plans WHERE plan_id='plan-cas'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM task_plans WHERE plan_id='plan-cas'", String.class))
                .isEqualTo("ENABLED");
        assertThat(jdbc.queryForObject("SELECT status FROM task_proposals WHERE proposal_id='change-cas'", String.class))
                .isEqualTo("PENDING_CONFIRMATION");
    }

    @Test
    void confirmCancellationReturnsRuntimeReceiptWithTaskResultAndProposalStatus() {
        tasks.save(new TaskFact("task-cancel", 1, "SCHEDULED",
                oneTimePayload().replace("夜间关灯", "取消我").replace("phase-1", "phase-cancel"),
                "task:cancel-key"));
        TaskSchedulerFactory.jdbc(jdbc, mapper).schedule("task-cancel", "phase-cancel",
                Instant.parse("2030-01-01T09:00:00Z"));
        proposals.save(new TaskProposalFact("cancel-1", 1, "PENDING_CONFIRMATION", "{}", "idem-cancel",
                "CANCELLATION", "{\"type\":\"task\",\"taskId\":\"task-cancel\"}", "{}", "{}", "cancel",
                "TENANT", PRINCIPAL.ownerKey(), "office", Instant.parse("2030-01-02T00:00:00Z")));
        TaskFacade facade = facade(null);

        Map<String, Object> receipt = facade.confirmCancellation(PRINCIPAL, "cancel-1", 1, "office");

        assertThat(receipt).containsEntry("cancellationId", "cancel-1")
                .containsEntry("taskId", "task-cancel")
                .containsEntry("status", "cancelled")
                .containsEntry("proposalStatus", "CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT status FROM task_facts WHERE task_id='task-cancel'", String.class))
                .isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT status FROM task_phases WHERE task_id='task-cancel'", String.class))
                .isEqualTo("CANCELLED");
    }

    @Test
    void cancellationChecksExternalAuthorizationBeforeOpeningTheWriteTransaction() {
        tasks.save(new TaskFact("task-network-cancel", 1, "SCHEDULED", scopedPayload(), "network-cancel"));
        proposals.save(new TaskProposalFact("network-cancel-proposal", 1, "PENDING_CONFIRMATION", "{}",
                "network-cancel-proposal", "CANCELLATION",
                "{\"type\":\"task\",\"taskId\":\"task-network-cancel\"}", "{}", "{}", "cancel",
                "TENANT", PRINCIPAL.ownerKey(), "office", Instant.parse("2030-01-02T00:00:00Z")));
        IotAdapter iot = identity -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager
                    .isActualTransactionActive()).as("External authorization must not hold a DB transaction").isFalse();
            return Mono.just(AuthorizedDeviceScope.of(identity, IotSourceEnum.OFFICE, List.of(
                    new AuthorizedDevice("dev-1", "lighting", "灯一", "办公室", false),
                    new AuthorizedDevice("dev-2", "lighting", "灯二", "办公室", false))));
        };
        TaskFacade facade = new TaskFacade(mapper, tasks, proposals, plans, mock(TaskNotificationStore.class),
                TaskSchedulerFactory.jdbc(jdbc, mapper), allTerminalStore(false), iot, null, null,
                mock(ltd.qianc.iotagent.readmodel.ReadSnapshots.class), atomic,
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(facade.confirmCancellation(PRINCIPAL, "network-cancel-proposal", 1, "office"))
                .containsEntry("taskId", "task-network-cancel").containsEntry("status", "cancelled");
        assertThat(tasks.find("task-network-cancel").orElseThrow().status()).isEqualTo("CANCELLED");
        assertThat(proposals.find("network-cancel-proposal").orElseThrow().status()).isEqualTo("CONFIRMED");
    }

    @Test
    void agentCancellationProposalCoversAnotherCreatorsTaskUnderCatalogScope() {
        tasks.save(new TaskFact("task-other-owner", 1, "SCHEDULED", scopedPayload()
                .replace("phase-x", "phase-other"), "task:other-owner-key"));
        TaskSchedulerFactory.jdbc(jdbc, mapper).schedule("task-other-owner", "phase-other",
                Instant.parse("2030-01-01T09:00:00Z"));
        List<TaskNotificationFact> recorded = new ArrayList<>();
        TaskNotificationStore notices = mock(TaskNotificationStore.class);
        when(notices.record(any())).thenAnswer(invocation -> {
            recorded.add(invocation.getArgument(0));
            return invocation.getArgument(0);
        });
        TaskFacade facade = facadeWithScope(tasks, notices, List.of("dev-1", "dev-2"), true, null);

        TaskProposalFact proposal = facade.proposeCancellation(PRINCIPAL, "office",
                "conversation-other", "turn-other",
                "{\"target\":{\"type\":\"task\",\"taskId\":\"task-other-owner\"}}", "cancel-other");
        assertThat(proposal.version()).isEqualTo(1);

        Map<String, Object> receipt = facade.confirmCancellation(PRINCIPAL, proposal.proposalId(), 1, "office");
        assertThat(receipt).containsEntry("status", "cancelled").containsEntry("taskId", "task-other-owner");
        assertThat(jdbc.queryForObject("SELECT status FROM task_facts WHERE task_id='task-other-owner'", String.class))
                .isEqualTo("CANCELLED");
        assertThat(recorded).singleElement()
                .satisfies(fact -> assertThat(fact.recipientKey()).isEqualTo("other-owner"));

        // The proposal stays bound to its actor: another principal cannot confirm it.
        PrincipalInfo stranger = new PrincipalInfo("Bearer stranger", "TENANT");
        assertThatThrownBy(() -> facade.confirmCancellation(stranger, proposal.proposalId(), 1, "office"))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.httpStatus()).isEqualTo(404));
    }

    @Test
    void cancelFollowsRuntimeCatalogAuthorizationWithoutACreatorGate() {
        String owner = PRINCIPAL.ownerKey();
        String payload = "{\"source\":\"office\",\"tenantCode\":\"TENANT\",\"ownerKey\":\"other-owner\","
                + "\"phases\":[{\"phaseId\":\"phase-x\",\"at\":\"2030-01-01T09:00:00Z\",\"actions\":["
                + "{\"deviceId\":\"dev-1\",\"kind\":\"lighting\",\"command\":{\"power\":\"off\"}},"
                + "{\"deviceId\":\"dev-2\",\"kind\":\"lighting\",\"command\":{\"power\":\"off\"}}]}]}";
        TaskFactStore factStore = mock(TaskFactStore.class);
        java.util.concurrent.atomic.AtomicReference<TaskFact> stored = new java.util.concurrent.atomic.AtomicReference<>(
                new TaskFact("task-scope", 1, "SCHEDULED", payload, "scope-key"));
        when(factStore.find("task-scope")).thenAnswer(invocation -> Optional.of(stored.get()));
        when(factStore.updateIfVersion(any(), anyLong())).thenAnswer(invocation -> {
            stored.set(invocation.getArgument(0));
            return Optional.of(stored.get());
        });
        List<TaskNotificationFact> recorded = new ArrayList<>();
        TaskNotificationStore notices = mock(TaskNotificationStore.class);
        when(notices.record(any())).thenAnswer(invocation -> {
            recorded.add(invocation.getArgument(0));
            return invocation.getArgument(0);
        });

        TaskFacade partialScope = facadeWithScope(factStore, notices, List.of("dev-1"));
        assertThatThrownBy(() -> partialScope.cancel(PRINCIPAL, "task-scope", 1, "office"))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.code()).isEqualTo("TASK_FORBIDDEN");
                    assertThat(error.httpStatus()).isEqualTo(403);
                });

        TaskFacade noScope = facadeWithScope(factStore, notices, List.of());
        assertThatThrownBy(() -> noScope.cancel(PRINCIPAL, "task-scope", 1, "office"))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.code()).isEqualTo("TASK_NOT_FOUND"));

        TaskFacade fullScope = facadeWithScope(factStore, notices, List.of("dev-1", "dev-2", "dev-9"), true, null);
        TaskFact cancelled = fullScope.cancel(PRINCIPAL, "task-scope", 1, "office");
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(recorded).singleElement().satisfies(fact -> {
            assertThat(fact.taskId()).isEqualTo("task-scope");
            assertThat(fact.recipientKey()).isEqualTo("other-owner");
            assertThat(fact.outcome()).isEqualTo("CANCELLED");
        });
        // Idempotent replay returns the durable state without a second inbox row.
        fullScope.cancel(PRINCIPAL, "task-scope", 2, "office");
        assertThat(recorded).hasSize(1);
    }

    @Test
    void inFlightCancellationSettlesUnderTheScannerWithoutEarlyNotificationOrIdentityDrop() {
        TaskFactStore factStore = mock(TaskFactStore.class);
        when(factStore.find("task-inflight")).thenReturn(Optional.of(new TaskFact("task-inflight", 1,
                "EXECUTING", scopedPayload(), "inflight-key")));
        when(factStore.updateIfVersion(any(), anyLong()))
                .thenAnswer(invocation -> Optional.of(invocation.getArgument(0)));
        List<TaskNotificationFact> recorded = new ArrayList<>();
        TaskNotificationStore notices = mock(TaskNotificationStore.class);
        when(notices.record(any())).thenAnswer(invocation -> {
            recorded.add(invocation.getArgument(0));
            return invocation.getArgument(0);
        });
        List<String> deletedIdentities = new ArrayList<>();
        TaskExecutionIdentityStore identities = new TaskExecutionIdentityStore() {
            @Override public void saveForTask(String taskId, TaskExecutionIdentity identity) { }
            @Override public void saveForPlan(String planId, TaskExecutionIdentity identity) { }
            @Override public Optional<TaskExecutionIdentity> loadForTask(String taskId) { return Optional.empty(); }
            @Override public void deleteForTask(String taskId) { deletedIdentities.add(taskId); }
        };

        TaskFacade facade = facadeWithScope(factStore, notices, List.of("dev-1", "dev-2"), false, identities);
        TaskFact cancelled = facade.cancel(PRINCIPAL, "task-inflight", 1, "office");

        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(recorded).isEmpty();
        assertThat(deletedIdentities).isEmpty();
    }

    @Test
    void notificationPersistenceFailureRollsBackTheWholeCancellation() {
        tasks.save(new TaskFact("task-rollback-cancel", 1, "SCHEDULED",
                oneTimePayload().replace("夜间关灯", "回滚取消").replace("phase-1", "phase-r"),
                "task:rollback-cancel-key"));
        TaskNotificationStore notices = mock(TaskNotificationStore.class);
        when(notices.record(any())).thenThrow(new IllegalStateException("inbox down"));

        TaskFacade facade = new TaskFacade(mapper, tasks, proposals, plans, notices,
                TaskSchedulerFactory.jdbc(jdbc, mapper), settledPhases("task-rollback-cancel"), null, null, null,
                mock(ltd.qianc.iotagent.readmodel.ReadSnapshots.class), atomic,
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> facade.cancel(PRINCIPAL, "task-rollback-cancel", 1, "office"))
                .isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM task_facts WHERE task_id='task-rollback-cancel'", String.class))
                .isEqualTo("SCHEDULED");
    }

    @Test
    void missedTasksAreTerminalAndCannotBeCancelledOrRewritten() {
        TaskFact missed = new TaskFact("task-missed", 1, "MISSED",
                "{\"source\":\"office\",\"tenantCode\":\"TENANT\",\"ownerKey\":\"" + PRINCIPAL.ownerKey()
                        + "\",\"phases\":[{\"phaseId\":\"phase-m\",\"at\":\"2030-01-01T09:00:00Z\",\"actions\":[]}]}",
                "missed-key");
        TaskFactStore factStore = mock(TaskFactStore.class);
        when(factStore.find("task-missed")).thenReturn(Optional.of(missed));
        TaskFacade facade = facadeWithScope(factStore, mock(TaskNotificationStore.class), List.of());

        assertThatThrownBy(() -> facade.cancel(PRINCIPAL, "task-missed", 1, "office"))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.code()).isEqualTo("TASK_ALREADY_FINISHED");
                    assertThat(error.httpStatus()).isEqualTo(409);
                });
        org.mockito.Mockito.verify(factStore, org.mockito.Mockito.never())
                .updateIfVersion(any(), anyLong());
    }

    @Test
    void wholePlanCancellationCancelsEveryMaterializedTaskAndNotifiesOnlySettledOnes() {
        plans.save(new TaskPlanFact("plan-whole-cancel", 1, "ENABLED",
                managedPlanPayload(PRINCIPAL.ownerKey()), "plan-whole-cancel-key"));
        insertMaterializedOccurrence("occ-pending", "SCHEDULED", "2030-01-02",
                new String[][] {{"occ-phase-pending", "PENDING"}});
        insertMaterializedOccurrence("occ-partial", "SCHEDULED", "2030-01-03",
                new String[][] {{"occ-phase-done", "COMPLETED"}, {"occ-phase-rest", "PENDING"}});
        insertMaterializedOccurrence("occ-inflight", "EXECUTING", "2030-01-04",
                new String[][] {{"occ-phase-live", "EXECUTING"}});
        insertMaterializedOccurrence("occ-finished", "COMPLETED", "2030-01-05",
                new String[][] {{"occ-phase-old", "COMPLETED"}});
        TaskExecutionIdentityStore identities = identityStore();
        for (String taskId : List.of("occ-pending", "occ-partial", "occ-inflight", "occ-finished")) {
            identities.saveForTask(taskId, new TaskExecutionIdentityStore.TaskExecutionIdentity(
                    "TENANT", PRINCIPAL.ownerKey(), "Bearer creator"));
        }
        List<TaskNotificationFact> recorded = new ArrayList<>();
        TaskNotificationStore notices = mock(TaskNotificationStore.class);
        when(notices.record(any())).thenAnswer(invocation -> {
            recorded.add(invocation.getArgument(0));
            return invocation.getArgument(0);
        });
        TaskFacade facade = planFacade(notices, identities);

        Map<String, Object> result = facade.cancelPlan(PRINCIPAL, "office", "plan-whole-cancel", 1, "all", null);

        assertThat(result).containsEntry("planId", "plan-whole-cancel")
                .containsEntry("status", "deleted")
                .containsEntry("version", 2L);
        // Runtime store.cancelPlan: every still-scheduled/executing materialized
        // task turns CANCELLED — the partially completed one included — while the
        // already-terminal occurrence keeps its executed fact.
        assertThat(tasks.find("occ-pending").orElseThrow().status()).isEqualTo("CANCELLED");
        assertThat(tasks.find("occ-partial").orElseThrow().status()).isEqualTo("CANCELLED");
        assertThat(tasks.find("occ-inflight").orElseThrow().status()).isEqualTo("CANCELLED");
        assertThat(tasks.find("occ-finished").orElseThrow().status()).isEqualTo("COMPLETED");
        assertThat(phaseRowStatus("occ-phase-pending")).isEqualTo("CANCELLED");
        assertThat(phaseRowStatus("occ-phase-done")).isEqualTo("COMPLETED");
        assertThat(phaseRowStatus("occ-phase-rest")).isEqualTo("CANCELLED");
        assertThat(phaseRowStatus("occ-phase-live")).isEqualTo("EXECUTING");
        assertThat(phaseRowStatus("occ-phase-old")).isEqualTo("COMPLETED");
        // Without in-flight work the creator's cancelled inbox row and the
        // protected-identity drop happen with the cancellation itself; the
        // in-flight task settles later under the scanner's cancelled-first rule.
        assertThat(recorded).extracting(TaskNotificationFact::taskId)
                .containsExactlyInAnyOrder("occ-pending", "occ-partial");
        assertThat(recorded).allSatisfy(fact -> {
            assertThat(fact.recipientKey()).isEqualTo(PRINCIPAL.ownerKey());
            assertThat(fact.outcome()).isEqualTo("CANCELLED");
        });
        assertThat(identities.loadForTask("occ-pending")).isEmpty();
        assertThat(identities.loadForTask("occ-partial")).isEmpty();
        assertThat(identities.loadForTask("occ-inflight")).isPresent();
        assertThat(identities.loadForTask("occ-finished")).isPresent();
    }

    @Test
    void directPlanResumeRebindsTheStoredPlanCredentialToTheCurrentPrincipal() {
        PrincipalInfo renewed = new PrincipalInfo("Bearer renewed", "TENANT", "user-1", "office-sso");
        plans.save(new TaskPlanFact("plan-resume", 1, "PAUSED",
                managedPlanPayload(renewed.ownerKey()), "plan-resume-key"));
        TaskExecutionIdentityStore identities = identityStore();
        identities.saveForPlan("plan-resume", new TaskExecutionIdentityStore.TaskExecutionIdentity(
                "TENANT", renewed.ownerKey(), "Bearer original"));
        identities.copyPlanIdentityToTask("plan-resume", "probe-before-resume");
        TaskFacade facade = planFacade(mock(TaskNotificationStore.class), identities);

        facade.updatePlan(renewed, "plan-resume", 1, "resume", true, "office");

        assertThat(plans.find("plan-resume").orElseThrow().status()).isEqualTo("ENABLED");
        // A later occurrence copies the plan identity, so the rebind is what a
        // future execution actually consumes — the renewed token, same account.
        identities.copyPlanIdentityToTask("plan-resume", "probe-after-resume");
        assertThat(identities.loadForTask("probe-before-resume").orElseThrow().authorization())
                .isEqualTo("Bearer original");
        assertThat(identities.loadForTask("probe-after-resume").orElseThrow().authorization())
                .isEqualTo("Bearer renewed");
        assertThat(identities.loadForTask("probe-after-resume").orElseThrow().tenantCode())
                .isEqualTo("TENANT");
    }

    @Test
    void confirmedResumeProposalRebindsTheStoredPlanCredentialInTheSameTransaction() {
        PrincipalInfo renewed = new PrincipalInfo("Bearer renewed", "TENANT", "user-1", "office-sso");
        String payload = managedPlanPayload(renewed.ownerKey());
        plans.save(new TaskPlanFact("plan-resume-change", 1, "PAUSED", payload, "plan-resume-change-key"));
        TaskExecutionIdentityStore identities = identityStore();
        identities.saveForPlan("plan-resume-change", new TaskExecutionIdentityStore.TaskExecutionIdentity(
                "TENANT", renewed.ownerKey(), "Bearer original"));
        proposals.save(new TaskProposalFact("change-resume", 1, "PENDING_CONFIRMATION", "{}",
                "idem-resume-change", "CHANGE", "{\"type\":\"task_plan\",\"planId\":\"plan-resume-change\"}",
                payload, payload, "resume", "TENANT", renewed.ownerKey(), "office",
                Instant.parse("2030-01-02T00:00:00Z")));
        TaskFacade facade = planFacade(mock(TaskNotificationStore.class), identities);

        Map<String, Object> receipt = facade.confirmChange(renewed, "change-resume", 1, "office");

        assertThat(receipt).containsEntry("planId", "plan-resume-change");
        assertThat(plans.find("plan-resume-change").orElseThrow().status()).isEqualTo("ENABLED");
        assertThat(proposals.find("change-resume").orElseThrow().status()).isEqualTo("CONFIRMED");
        identities.copyPlanIdentityToTask("plan-resume-change", "probe-change-resume");
        assertThat(identities.loadForTask("probe-change-resume").orElseThrow().authorization())
                .isEqualTo("Bearer renewed");
        assertThat(identities.loadForTask("probe-change-resume").orElseThrow().tenantCode())
                .isEqualTo("TENANT");
    }

    private TaskFacade facadeWithScope(TaskFactStore factStore, TaskNotificationStore notices, List<String> deviceIds) {
        return facadeWithScope(factStore, notices, deviceIds, true, null);
    }

    private TaskFacade facadeWithScope(TaskFactStore factStore, TaskNotificationStore notices,
                                       List<String> deviceIds, boolean allTerminal,
                                       TaskExecutionIdentityStore identities) {
        IotAdapter iot = identity -> Mono.just(AuthorizedDeviceScope.of(identity, IotSourceEnum.OFFICE,
                deviceIds.stream().map(id -> new AuthorizedDevice(id, "lighting", "灯", "办公室", false)).toList()));
        return new TaskFacade(mapper, factStore, proposals, plans, notices, mock(TaskScheduler.class),
                allTerminalStore(allTerminal), iot, identities, null,
                mock(ltd.qianc.iotagent.readmodel.ReadSnapshots.class),
                TaskAtomicTransactions.direct(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static ltd.qianc.iotagent.task.TaskPhaseStore allTerminalStore(boolean allTerminal) {
        return new ltd.qianc.iotagent.task.TaskPhaseStore() {
            @Override public List<ltd.qianc.iotagent.task.TaskPhaseStore.TaskPhase> due(java.time.Instant now, int limit) { return List.of(); }
            @Override public boolean claim(String phaseId, long expectedVersion, java.time.Instant claimedAt) { return false; }
            @Override public Optional<ltd.qianc.iotagent.task.TaskPhaseStore.TaskPhase> find(String phaseId) { return Optional.empty(); }
            @Override public boolean finish(String phaseId, long expectedVersion, String status, String payloadJson, java.time.Instant endedAt) { return false; }
            @Override public int recoverExecuting(java.time.Instant at) { return 0; }
            @Override public boolean allTerminal(String taskId) { return allTerminal; }
        };
    }

    private static ltd.qianc.iotagent.task.TaskPhaseStore settledPhases(String taskId) {
        return allTerminalStore(true);
    }

    private static String scopedPayload() {
        return "{\"source\":\"office\",\"tenantCode\":\"TENANT\",\"ownerKey\":\"other-owner\","
                + "\"phases\":[{\"phaseId\":\"phase-x\",\"at\":\"2030-01-01T09:00:00Z\",\"actions\":["
                + "{\"deviceId\":\"dev-1\",\"kind\":\"lighting\",\"command\":{\"power\":\"off\"}},"
                + "{\"deviceId\":\"dev-2\",\"kind\":\"lighting\",\"command\":{\"power\":\"off\"}}]}]}";
    }

    private static String planPayload() {
        return "{\"source\":\"office\",\"tenantCode\":\"TENANT\",\"ownerKey\":\"" + PRINCIPAL.ownerKey()
                + "\",\"label\":\"每日巡检\",\"repeat\":{\"frequency\":\"daily\"},"
                + "\"phases\":[{\"at\":\"2030-01-02T09:00:00Z\",\"actions\":[]}]}";
    }

    private TaskFacade planFacade(TaskNotificationStore notices, TaskExecutionIdentityStore identities) {
        IotAdapter iot = identity -> Mono.just(AuthorizedDeviceScope.of(identity, IotSourceEnum.OFFICE, List.of(
                new AuthorizedDevice("dev-1", "lighting", "灯一", "办公室", false))));
        return new TaskFacade(mapper, tasks, proposals, plans, notices, TaskSchedulerFactory.jdbc(jdbc, mapper),
                phaseStore, iot, identities, null,
                mock(ltd.qianc.iotagent.readmodel.ReadSnapshots.class), atomic,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private TaskExecutionIdentityStore identityStore() {
        return TaskExecutionIdentityStoreFactory.jdbc(jdbc, mapper,
                "0f1e2d3c4b5a69788796a5b4c3d2e1f000112233445566778899aabbccddeeff");
    }

    private void insertMaterializedOccurrence(String taskId, String taskStatus, String occurrenceDate,
                                              String[][] phases) {
        String payload = "{\"source\":\"office\",\"tenantCode\":\"TENANT\",\"ownerKey\":\"" + PRINCIPAL.ownerKey()
                + "\",\"label\":\"每日巡检\",\"sourcePlanId\":\"plan-whole-cancel\","
                + "\"phases\":[{\"at\":\"2030-01-02T09:00:00Z\",\"actions\":[]}]}";
        tasks.save(new TaskFact(taskId, 1, taskStatus, payload, "task:" + taskId));
        for (String[] phase : phases) {
            jdbc.update("INSERT INTO task_phases(phase_id,task_id,source,version,status,due_at,payload_json,create_time,update_time) "
                            + "VALUES (?,?,?,1,?,?,?,NOW(),NOW())",
                    phase[0], taskId, "office", phase[1],
                    java.sql.Timestamp.from(Instant.parse("2030-01-02T09:00:00Z")), payload);
        }
        jdbc.update("INSERT INTO task_plan_occurrences(plan_id,occurrence_date,task_id,status,version,create_time,update_time) "
                        + "VALUES ('plan-whole-cancel',?,?,'MATERIALIZED',1,NOW(),NOW())",
                java.sql.Date.valueOf(occurrenceDate), taskId);
    }

    private String phaseRowStatus(String phaseId) {
        return jdbc.queryForObject("SELECT status FROM task_phases WHERE phase_id=?", String.class, phaseId);
    }

    private static String managedPlanPayload(String ownerKey) {
        return "{\"source\":\"office\",\"tenantCode\":\"TENANT\",\"ownerKey\":\"" + ownerKey
                + "\",\"label\":\"每日巡检\",\"repeat\":{\"frequency\":\"daily\"},"
                + "\"phases\":[{\"at\":\"2030-01-02T09:00:00Z\",\"actions\":"
                + "[{\"deviceId\":\"dev-1\",\"kind\":\"lighting\",\"command\":{\"power\":\"off\"}}]}]}";
    }
}
