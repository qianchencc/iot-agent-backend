package ltd.qianc.iotagent.storage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import ltd.qianc.iotagent.action.ActionDraftFact;
import ltd.qianc.iotagent.action.ActionDraftStore;
import ltd.qianc.iotagent.audit.AuditEventFact;
import ltd.qianc.iotagent.audit.AuditEventStore;
import ltd.qianc.iotagent.conversation.ConversationEventFact;
import ltd.qianc.iotagent.conversation.ConversationEventStore;
import ltd.qianc.iotagent.report.ReportFact;
import ltd.qianc.iotagent.report.ReportFactStore;
import ltd.qianc.iotagent.task.TaskFact;
import ltd.qianc.iotagent.task.TaskFactStore;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BusinessFactRepositoryContractTest {

    @Test
    void conversationEventsAppendInSequenceAndRejectDuplicateIdempotency() {
        ConversationMemory store = new ConversationMemory();
        store.append(event("c-1", 1, "run.started", "start"));
        store.append(event("c-1", 2, "run.completed", "done"));

        assertThat(store.read("c-1")).extracting(ConversationEventFact::sequence)
                .containsExactly(1L, 2L);
        assertThat(store.findByIdempotencyKey("c-1", "event-2")).isPresent();
        assertThatThrownBy(() -> store.append(new ConversationEventFact(
                "c-1", "turn-1", 3, "run.completed", "duplicate", "event-2")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("idempotency");
    }

    @Test
    void actionDraftUpdateRequiresExpectedRevision() {
        ActionMemory store = new ActionMemory();
        store.create(new ActionDraftFact("draft-1", 1, "PENDING_CONFIRMATION", "{}", "draft-1"));
        store.update("draft-1", 1, new ActionDraftFact("draft-1", 2, "CANCELLED", "{}", "draft-1-cancel"));

        assertThat(store.find("draft-1").orElseThrow().status()).isEqualTo("CANCELLED");
        assertThatThrownBy(() -> store.update("draft-1", 1,
                new ActionDraftFact("draft-1", 3, "EXECUTING", "{}", "draft-1-execute")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("revision");
    }

    @Test
    void taskReportAndAuditStoresExposeFactsWithoutInMemoryFallbackContract() {
        TaskMemory tasks = new TaskMemory();
        ReportMemory reports = new ReportMemory();
        AuditMemory audit = new AuditMemory();

        tasks.save(new TaskFact("task-1", 1, "SCHEDULED", "{}", "create-1"));
        reports.save(new ReportFact("report-1", "company", "{}"));
        audit.append(new AuditEventFact("audit-1", "XSJ", "TASK_CREATED", "{}"));

        assertThat(tasks.findByIdempotencyKey("create-1")).isPresent();
        assertThat(reports.find("report-1")).contains(new ReportFact("report-1", "company", "{}"));
        assertThat(audit.readByTenant("XSJ")).extracting(AuditEventFact::eventId)
                .containsExactly("audit-1");
        assertThat(audit.findByEventId("audit-1")).isPresent();
        assertThat(audit.findByEventId("missing")).isEmpty();
    }

    @Test
    void storageSettingsFailClosedWhenRequiredEnvironmentIsMissing() {
        assertThatThrownBy(() -> StorageSettings.required(Map.of()))
                .isInstanceOf(StorageConfigurationException.class)
                .hasMessageContaining("AGENT_MYSQL_URL");

        assertThat(StorageSettings.required(Map.of(
                "AGENT_MYSQL_URL", "jdbc:mysql://db/agent",
                "AGENT_MYSQL_USERNAME", "agent",
                "AGENT_MYSQL_PASSWORD", "secret",
                "AGENT_REDIS_URL", "redis://cache"))).isEqualTo(
                new StorageSettings("jdbc:mysql://db/agent", "agent", "secret", "redis://cache"));
    }

    private static ConversationEventFact event(String conversationId, long sequence, String type, String payload) {
        return new ConversationEventFact(conversationId, "turn-1", sequence, type, payload, "event-" + sequence);
    }

    private static final class ConversationMemory implements ConversationEventStore {
        private final Map<String, List<ConversationEventFact>> events = new LinkedHashMap<>();

        @Override
        public ConversationEventFact append(ConversationEventFact event) {
            List<ConversationEventFact> current = events.computeIfAbsent(event.conversationId(), ignored -> new ArrayList<>());
            if (current.stream().anyMatch(item -> item.sequence() == event.sequence())) {
                throw new IllegalStateException("sequence");
            }
            if (current.stream().anyMatch(item -> item.idempotencyKey().equals(event.idempotencyKey()))) {
                throw new IllegalStateException("idempotency");
            }
            current.add(event);
            return event;
        }

        @Override
        public List<ConversationEventFact> read(String conversationId) {
            return List.copyOf(events.getOrDefault(conversationId, List.of()));
        }

        @Override
        public Optional<ConversationEventFact> findByIdempotencyKey(String conversationId, String idempotencyKey) {
            return read(conversationId).stream()
                    .filter(event -> event.idempotencyKey().equals(idempotencyKey))
                    .findFirst();
        }
    }

    private static final class ActionMemory implements ActionDraftStore {
        private final Map<String, ActionDraftFact> drafts = new HashMap<>();

        @Override
        public ActionDraftFact create(ActionDraftFact draft) {
            if (drafts.putIfAbsent(draft.draftId(), draft) != null) throw new IllegalStateException("duplicate");
            return draft;
        }

        @Override
        public Optional<ActionDraftFact> find(String draftId) {
            return Optional.ofNullable(drafts.get(draftId));
        }

        @Override
        public ActionDraftFact update(String draftId, long expectedRevision, ActionDraftFact replacement) {
            ActionDraftFact current = drafts.get(draftId);
            if (current == null || current.revision() != expectedRevision) throw new IllegalStateException("revision");
            drafts.put(draftId, replacement);
            return replacement;
        }
    }

    private static final class TaskMemory implements TaskFactStore {
        private final Map<String, TaskFact> tasks = new HashMap<>();

        @Override
        public TaskFact save(TaskFact task) {
            tasks.put(task.taskId(), task);
            return task;
        }

        @Override
        public Optional<TaskFact> find(String taskId) {
            return Optional.ofNullable(tasks.get(taskId));
        }

        @Override
        public Optional<TaskFact> findByIdempotencyKey(String idempotencyKey) {
            return tasks.values().stream().filter(task -> task.idempotencyKey().equals(idempotencyKey)).findFirst();
        }
    }

    private static final class ReportMemory implements ReportFactStore {
        private final Map<String, ReportFact> reports = new HashMap<>();

        @Override
        public ReportFact save(ReportFact report) {
            reports.put(report.reportId(), report);
            return report;
        }

        @Override
        public Optional<ReportFact> find(String reportId) {
            return Optional.ofNullable(reports.get(reportId));
        }
    }

    private static final class AuditMemory implements AuditEventStore {
        private final List<AuditEventFact> events = new ArrayList<>();

        @Override
        public AuditEventFact append(AuditEventFact event) {
            events.add(event);
            return event;
        }

        @Override
        public List<AuditEventFact> readByTenant(String tenantCode) {
            return events.stream().filter(event -> event.tenantCode().equals(tenantCode)).toList();
        }

        @Override
        public List<String> tenants() {
            return events.stream().map(AuditEventFact::tenantCode).distinct().sorted().toList();
        }
        @Override
        public Optional<AuditEventFact> findByEventId(String eventId) {
            return events.stream().filter(event -> event.eventId().equals(eventId)).findFirst();
        }

    }
}
