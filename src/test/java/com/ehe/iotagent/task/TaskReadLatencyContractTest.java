package com.ehe.iotagent.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import com.ehe.iotagent.auth.PrincipalInfo;
import com.ehe.iotagent.error.BusinessException;
import com.ehe.iotagent.iot.AuthorizedDevice;
import com.ehe.iotagent.iot.AuthorizedDeviceScope;
import com.ehe.iotagent.iot.DeviceDirectory;
import com.ehe.iotagent.iot.DeviceDirectoryBatch;
import com.ehe.iotagent.iot.IotAdapter;
import com.ehe.iotagent.iot.IotSourceEnum;
import com.ehe.iotagent.iot.OfficeRegion;
import com.ehe.iotagent.iot.RequestIdentity;
import com.ehe.iotagent.readmodel.ReadSnapshots;
import com.ehe.iotagent.schema.SchemaMigrations;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;

class TaskReadLatencyContractTest {
    private static final Instant NOW = Instant.parse("2026-11-04T08:00:00Z");
    private static final PrincipalInfo PRINCIPAL = new PrincipalInfo("Bearer fixture-reader", "TENANT", "member", "gateway");

    @Test
    void coldLocalScopeDoesNotCrawlAndNewNotificationsRemainVisibleWithSourceDown() {
        try (Harness h = new Harness()) {
            assertThatThrownBy(() -> h.facade.notificationsView(PRINCIPAL, "company", 30, null))
                    .isInstanceOfSatisfying(BusinessException.class,
                            failure -> assertThat(failure.code()).isEqualTo("TASK_READ_PREPARING"));
            assertThat(h.sourceReads).isZero();
            h.directory.synchronizeDue();
            int beforeInteraction = h.sourceReads;
            h.upstreamAvailable = false;
            assertThat(rows(h.facade.notificationsView(PRINCIPAL, "company", 30, null)))
                    .extracting(row -> row.get("id")).containsExactly("notice-1");
            h.inbox.set(List.of(h.notification("notice-2"), h.notification("notice-1")));
            assertThat(rows(h.facade.notificationsView(PRINCIPAL, "company", 30, null)))
                    .extracting(row -> row.get("id")).containsExactly("notice-2", "notice-1");
            assertThat(h.sourceReads).isEqualTo(beforeInteraction);
        }
    }

    @Test
    void publishedMemberRemovalImmediatelyChangesTheTaskReadScope() {
        try (Harness h = new Harness()) {
            h.prepare("company");
            h.clock.now = NOW.plusSeconds(61);
            h.devices.set(List.of(new AuthorizedDevice("light-1", "lighting", "Fixture Light One", "Fixture Floor", false)));
            h.directory.synchronizeDue();
            int beforeInteraction = h.sourceReads;
            h.upstreamAvailable = false;
            assertThat(h.facade.scope(PRINCIPAL, "company").deviceIds()).containsExactly("light-1");
            assertThat(h.sourceReads).isEqualTo(beforeInteraction);
        }
    }

    @Test
    void cancellationUsesPublishedAuthorizationWithoutReachingTheUnavailableSource() {
        try (Harness h = new Harness()) {
            h.prepare("company");
            h.clock.now = NOW.plusSeconds(61);
            h.devices.set(List.of(new AuthorizedDevice("light-1", "lighting", "Fixture Light One", "Fixture Floor", false)));
            h.directory.synchronizeDue();
            int beforeInteraction = h.sourceReads;
            h.upstreamAvailable = false;
            assertThatThrownBy(() -> h.facade.cancel(PRINCIPAL, "task-1", 1, "company"))
                    .isInstanceOfSatisfying(BusinessException.class, failure -> {
                        assertThat(failure.code()).isEqualTo("TASK_FORBIDDEN");
                        assertThat(failure.httpStatus()).isEqualTo(403);
                    });
            assertThat(h.tasks.find("task-1").orElseThrow().status()).isEqualTo("SCHEDULED");
            assertThat(h.sourceReads).isEqualTo(beforeInteraction);
        }
    }

    @Test
    void synchronizedCredentialDenialBlocksThePersistedReadScopeWithoutInteractiveRevalidation() {
        try (Harness h = new Harness()) {
            h.prepare("company");
            h.upstreamDenial = new BusinessException("UNAUTHORIZED", "Fixture credential denied", 401);
            h.clock.now = NOW.plusSeconds(61);
            h.directory.synchronizeDue();
            int beforeInteraction = h.sourceReads;
            assertThatThrownBy(() -> h.facade.cancel(PRINCIPAL, "task-1", 1, "company"))
                    .isInstanceOfSatisfying(BusinessException.class, failure -> assertThat(failure.httpStatus()).isEqualTo(401));
            assertThatThrownBy(() -> h.facade.notificationsView(PRINCIPAL, "company", 30, null))
                    .isInstanceOfSatisfying(BusinessException.class, failure -> assertThat(failure.httpStatus()).isEqualTo(401));
            assertThat(h.sourceReads).isEqualTo(beforeInteraction);
        }
    }

    @Test
    void emptyAuthorizedOfficeRegionsSurviveWithoutInventingDeviceMembership() {
        try (Harness h = new Harness()) {
            h.prepare("office");
            int beforeInteraction = h.sourceReads;
            h.upstreamAvailable = false;
            TaskReadScope scope = h.facade.scope(PRINCIPAL, "office");
            assertThat(scope.regionIds()).containsExactly("fixture-empty-region");
            assertThat(scope.deviceIds()).isEmpty();
            assertThat(h.sourceReads).isEqualTo(beforeInteraction);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("notifications");
    }

    private static final class Harness implements AutoCloseable {
        final MutableClock clock = new MutableClock();
        final ObjectMapper mapper = new ObjectMapper();
        final AtomicReference<List<TaskNotificationFact>> inbox = new AtomicReference<>();
        final AtomicReference<List<AuthorizedDevice>> devices = new AtomicReference<>(List.of(
                new AuthorizedDevice("light-1", "lighting", "Fixture Light One", "Fixture Floor", false),
                new AuthorizedDevice("light-2", "lighting", "Fixture Light Two", "Fixture Floor", false)));
        final TaskFactStore tasks = mock(TaskFactStore.class);
        final MemoryBarrier barrier = new MemoryBarrier();
        final ReadSnapshots snapshots = ReadSnapshots.create(clock, Runnable::run, Runnable::run,
                null, barrier, mapper, ReadSnapshots.Settings.defaults());
        final JdbcTemplate jdbc;
        final DeviceDirectory directory;
        final TaskFacade facade;
        boolean upstreamAvailable = true;
        BusinessException upstreamDenial;
        int sourceReads;

        Harness() {
            JdbcDataSource database = new JdbcDataSource();
            database.setURL("jdbc:h2:mem:task_directory_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
            jdbc = new JdbcTemplate(database);
            new SchemaMigrations(database).ensureLocalCompanyDirectory();
            // H2 wraps bound JSON strings; the isolated MySQL smoke covers the real JSON column.
            jdbc.execute("ALTER TABLE local_company_devices ALTER COLUMN metadata_json VARCHAR(1000000) NOT NULL");
            IotAdapter iot = new IotAdapter() {
                @Override public Mono<AuthorizedDeviceScope> loadAuthorizedDeviceScope(RequestIdentity identity) {
                    sourceReads++;
                    if (upstreamDenial != null) return Mono.error(upstreamDenial);
                    if (!upstreamAvailable) return Mono.error(new IllegalStateException("Fixture source offline"));
                    return Mono.just(AuthorizedDeviceScope.of(identity, identity.source(),
                            identity.source() == IotSourceEnum.OFFICE ? List.of() : devices.get()));
                }
                @Override public Mono<DeviceDirectoryBatch> collectDirectory(RequestIdentity identity) {
                    return loadAuthorizedDeviceScope(identity).map(scope -> new DeviceDirectoryBatch(scope,
                            identity.source() == IotSourceEnum.OFFICE
                                    ? List.of(new OfficeRegion("fixture-empty-region", "Fixture Empty Region")) : List.of()));
                }
                @Override public Mono<AuthorizedDeviceScope> loadAuthorizedEnergyScope(RequestIdentity identity) {
                    return Mono.just(AuthorizedDeviceScope.of(identity, identity.source(), List.of()));
                }
                @Override public Mono<DeviceDirectoryBatch> readDirectory(RequestIdentity identity) {
                    return Mono.fromCallable(() -> directory.loadBatch(identity));
                }
            };
            directory = DeviceDirectory.jdbc(jdbc, new TransactionTemplate(new DataSourceTransactionManager(database)),
                    mapper, iot, com.ehe.iotagent.iot.ObservationAdapterFactory.failClosed("fixture history unavailable"),
                    presented -> new PrincipalInfo(presented.authorization(), presented.tenantCode(), "member", "gateway"),
                    snapshots, barrier, "https://gateway.example.invalid", "45".repeat(32), clock, request -> List.of(), 20);
            String payload = "{\"source\":\"company\",\"tenantCode\":\"TENANT\",\"ownerKey\":\""
                    + PRINCIPAL.ownerKey() + "\",\"label\":\"Fixture task\",\"phases\":[{\"actions\":["
                    + "{\"deviceId\":\"light-1\",\"kind\":\"lighting\"},{\"deviceId\":\"light-2\",\"kind\":\"lighting\"}]}]}";
            when(tasks.find("task-1")).thenReturn(Optional.of(new TaskFact("task-1", 1, "SCHEDULED", payload, "task-key")));
            TaskNotificationStore notifications = mock(TaskNotificationStore.class);
            inbox.set(List.of(notification("notice-1")));
            when(notifications.list(anyString(), anyString(), anyString(), anyInt(), nullable(String.class)))
                    .thenAnswer(invocation -> inbox.get());
            when(notifications.unread(anyString(), anyString(), anyString())).thenAnswer(invocation -> inbox.get());
            IotAdapter local = com.ehe.iotagent.iot.IotAdapterFactory.localReads(iot, () -> directory,
                    () -> mock(com.ehe.iotagent.iot.DeviceObservations.class), mock(com.ehe.iotagent.iot.DeviceCandidateSetStore.class));
            facade = new TaskFacade(mapper, tasks, mock(TaskProposalStore.class), mock(TaskPlanStore.class),
                    notifications, mock(TaskScheduler.class), null, local, snapshots, clock);
        }

        TaskNotificationFact notification(String id) {
            return new TaskNotificationFact(id, "task-1", "TENANT", PRINCIPAL.ownerKey(), "company",
                    "COMPLETED", NOW, null, "{\"outcome\":\"COMPLETED\"}");
        }
        void prepare(String source) {
            assertThatThrownBy(() -> facade.scope(PRINCIPAL, source)).isInstanceOfSatisfying(BusinessException.class,
                    failure -> assertThat(failure.code()).isEqualTo("TASK_READ_PREPARING"));
            directory.synchronizeDue();
        }
        @Override public void close() { directory.close(); snapshots.close(); jdbc.execute("SHUTDOWN"); }
    }

    private static final class MutableClock extends Clock {
        Instant now = NOW;
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }

    private static final class MemoryBarrier implements ReadSnapshots.Barrier {
        private final Map<Key, State> values = new LinkedHashMap<>();
        @Override public Checkpoint startInstance(String instance) { return new Checkpoint(true, "fixture-epoch"); }
        @Override public boolean markClean(String instance) { return true; }
        @Override public Optional<State> read(Key key) { return Optional.ofNullable(values.get(key)); }
        @Override public boolean write(Key key, State state) {
            State current = values.get(key);
            if (current != null && current.issuedAt().isAfter(state.issuedAt())) return false;
            values.put(key, state);
            return true;
        }
        @Override public boolean writeIfCurrent(Key key, String expected, State state) {
            State current = values.get(key);
            if (expected == null ? current != null : current == null || !expected.equals(current.generation())) return false;
            return write(key, state);
        }
    }
}
