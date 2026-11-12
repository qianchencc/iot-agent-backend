package ltd.qianc.iotagent.iot.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import ltd.qianc.iotagent.auth.AccountPrincipalVerifier;
import ltd.qianc.iotagent.auth.PrincipalInfo;
import ltd.qianc.iotagent.error.BusinessException;
import ltd.qianc.iotagent.iot.AuthorizedDevice;
import ltd.qianc.iotagent.iot.AuthorizedDeviceScope;
import ltd.qianc.iotagent.iot.DeviceDirectory;
import ltd.qianc.iotagent.iot.DeviceDirectoryBatch;
import ltd.qianc.iotagent.iot.OfficeRegion;
import ltd.qianc.iotagent.iot.GatewayAuthorizationException;
import ltd.qianc.iotagent.iot.IotAdapter;
import ltd.qianc.iotagent.iot.IotSourceEnum;
import ltd.qianc.iotagent.iot.RequestIdentity;
import ltd.qianc.iotagent.readmodel.ReadSnapshots;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** One independently deployed backend owns its schema and cadence. Network work never holds a DB transaction. */
public final class JdbcCompanyDirectory implements DeviceDirectory {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long SYNC_INTERVAL_SECONDS = 60;
    private final JdbcDeviceCatalog catalog;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;
    private final IotAdapter upstream;
    private final AccountPrincipalVerifier verifier;
    private final ReadSnapshots snapshots;
    private final ReadSnapshots.Barrier barrier;
    private final String gateway;
    private final byte[] key;
    private final Clock clock;
    private final JdbcDeviceObservations observations;
    private final JdbcEnergyHistory energyHistory;
    private final JdbcEnvironmentHistory environmentHistory;
    private final JdbcSafetyHistory safetyHistory;
    private final java.util.function.Supplier<ltd.qianc.iotagent.iot.ObservationAdapter> historySource;
    private final ReentrantLock historyLock = new ReentrantLock();
    private final ReentrantLock syncLock = new ReentrantLock();
    private final ReentrantLock currentLock = new ReentrantLock();
    private final ScheduledThreadPoolExecutor cadence;
    private boolean started;
    private volatile boolean closed;
    private volatile String activeScope;
    private volatile Scope activeStateScope;

    public JdbcCompanyDirectory(JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper mapper,
            IotAdapter upstream, java.util.function.Supplier<ltd.qianc.iotagent.iot.ObservationAdapter> historySource,
            AccountPrincipalVerifier verifier, ReadSnapshots snapshots,
            ReadSnapshots.Barrier barrier, String gatewayInstance, String credentialKey, Clock clock,
            ltd.qianc.iotagent.iot.DeviceCatalogSource catalogSource, byte[] safetyCursorKey) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = Objects.requireNonNull(transactions);
        this.mapper = Objects.requireNonNull(mapper);
        this.upstream = Objects.requireNonNull(upstream);
        this.historySource = Objects.requireNonNull(historySource);
        this.verifier = Objects.requireNonNull(verifier);
        this.snapshots = Objects.requireNonNull(snapshots);
        this.barrier = Objects.requireNonNull(barrier);
        this.clock = Objects.requireNonNull(clock);
        if (gatewayInstance == null || gatewayInstance.isBlank()) throw new IllegalArgumentException("IoT gateway instance is required");
        if (credentialKey == null || !credentialKey.matches("[a-fA-F0-9]{64}")) {
            throw new IllegalArgumentException("agent.local-data.credential-key must be 32 bytes of hex");
        }
        this.gateway = hash(gatewayInstance);
        this.key = HexFormat.of().parseHex(credentialKey);
        this.catalog = new JdbcDeviceCatalog(jdbc, transactions, Objects.requireNonNull(catalogSource), this.gateway, clock, SYNC_INTERVAL_SECONDS);
        this.observations = new JdbcDeviceObservations(jdbc, transactions, mapper, this::loadBatch, this::scopeKey, clock);
        this.energyHistory = new JdbcEnergyHistory(jdbc, transactions, mapper, this::loadBatch, this::scopeKey, clock);
        this.environmentHistory = new JdbcEnvironmentHistory(jdbc, transactions, mapper, this::loadBatch, this::scopeKey, observations, clock);
        this.safetyHistory = new JdbcSafetyHistory(jdbc, transactions, mapper, this::loadBatch, this::scopeKey, clock, safetyCursorKey);
        this.cadence = new ScheduledThreadPoolExecutor(3, task -> {
            Thread thread = new Thread(task, "local-company-sync");
            thread.setDaemon(true);
            return thread;
        });
        cadence.setRemoveOnCancelPolicy(true);
    }

    public ltd.qianc.iotagent.iot.DeviceObservations observations() { return observations; }

    @Override public ltd.qianc.iotagent.iot.EnergyHistory energyHistory() { return energyHistory; }

    @Override public ltd.qianc.iotagent.iot.EnvironmentHistory environmentHistory() { return environmentHistory; }

    @Override public ltd.qianc.iotagent.iot.SafetyHistory safetyHistory() { return safetyHistory; }

    @Override public Map<String, Object> readCatalog(RequestIdentity identity) {
        if (identity.source() != IotSourceEnum.COMPANY) throw new BusinessException("DEVICE_SOURCE_UNAVAILABLE", "设备目录仅支持公司来源", 404);
        try {
            verify(identity);
            register(identity);
            return catalog.read(identity);
        } catch (DataAccessException unavailable) {
            throw unavailable();
        }
    }

    @Override public Map<String, Object> read(RequestIdentity identity) {
        if (identity.source() != IotSourceEnum.COMPANY) throw new BusinessException("DEVICE_SOURCE_UNAVAILABLE", "公司目录投影仅支持公司来源", 404);
        try {
            verify(identity);
            Scope scope = register(identity);
            requireReadable(scope);
            requireAuthorization(identity, scope);
            Map<String, Object> body = snapshots.read(snapshotIdentity(identity), "COMPANY_DEVICES", Map.of(),
                    ReadSnapshots.PolicyEnum.CURRENT, () -> localBody(identity));
            if (body.get("cache") instanceof Map<?, ?> cache && "unavailable".equals(cache.get("status"))) {
                throw unavailable();
            }
            // A projection miss is not missing business data. Serve the same local loader immediately.
            if (!scope.generation().equals(body.get("_directoryGeneration"))
                    || !Long.toString(scope.version()).equals(body.get("_directoryVersion"))) {
                body = localBody(identity);
            }
            Scope current = find(scope.key(), false);
            requireReadable(current);
            if (!current.credentialHash().equals(scope.credentialHash())
                    || !current.generation().equals(body.get("_directoryGeneration"))
                    || !Long.toString(current.version()).equals(body.get("_directoryVersion"))) throw preparing();
            requireAuthorization(identity, current);
            Map<String, Object> result = new LinkedHashMap<>(body);
            result.remove("_directoryGeneration");
            result.remove("_directoryVersion");
            boolean stale = !"READY".equals(current.state()) || !clock.instant().isBefore(current.nextDue());
            Map<String, Object> cache = new LinkedHashMap<>();
            cache.put("status", stale ? "stale" : "fresh");
            cache.put("updatedAt", text(current.success()));
            cache.put("refreshing", current.key().equals(activeScope));
            result.put("cache", cache);
            result.put("sync", publicStatus(current));
            return result;
        } catch (DataAccessException unavailable) {
            throw unavailable();
        }
    }

    @Override public DeviceDirectoryBatch loadBatch(RequestIdentity identity) {
        try {
            verify(identity);
            Scope expected = register(identity);
            requireReadable(expected);
            requireAuthorization(identity, expected);
            DeviceDirectoryBatch result = transactions.execute(tx -> {
                Map<String, Map<Integer, List<String>>> paths = new LinkedHashMap<>();
                jdbc.query("SELECT device_key,path_index,segment_name FROM local_company_locations WHERE scope_key=? "
                                + "ORDER BY device_key,path_index,segment_index",
                        (org.springframework.jdbc.core.RowCallbackHandler) rs -> paths.computeIfAbsent(rs.getString(1), ignored -> new LinkedHashMap<>())
                                .computeIfAbsent(rs.getInt(2), ignored -> new ArrayList<>()).add(rs.getString(3)), expected.key());
                Map<String, List<String>> offices = new LinkedHashMap<>();
                jdbc.query("SELECT device_key,office_id FROM local_company_offices WHERE scope_key=? ORDER BY device_key,id",
                        (org.springframework.jdbc.core.RowCallbackHandler) rs -> offices.computeIfAbsent(rs.getString(1), ignored -> new ArrayList<>())
                                .add(rs.getString(2)), expected.key());
                List<AuthorizedDevice> devices = jdbc.query("SELECT d.* FROM local_company_devices d JOIN local_company_grants g "
                                + "ON g.scope_key=d.scope_key AND g.device_key=d.device_key WHERE d.scope_key=? AND g.batch_version=? ORDER BY d.id",
                        (rs, row) -> new AuthorizedDevice(rs.getString("source_device_id"), rs.getString("device_kind"),
                                rs.getString("device_name"), rs.getString("install_address"), rs.getBoolean("observation_only"),
                                rs.getString("control_ref"), rs.getString("office_device_ref"), rs.getString("hub_device_id"),
                                rs.getString("smart_area_code"),
                                List.copyOf(paths.getOrDefault(rs.getString("device_key"), Map.of()).values()),
                                List.copyOf(offices.getOrDefault(rs.getString("device_key"), List.of())),
                                metadata(rs.getString("metadata_json"))), expected.key(), expected.version());
                List<OfficeRegion> regions = identity.source() == IotSourceEnum.OFFICE
                        ? jdbc.query("SELECT source_region_id,region_name FROM local_office_regions WHERE scope_key=? AND batch_version=? ORDER BY id",
                                (rs, row) -> new OfficeRegion(rs.getString(1), rs.getString(2)), expected.key(), expected.version())
                        : List.of();
                return new DeviceDirectoryBatch(AuthorizedDeviceScope.of(identity, identity.source(), devices), regions,
                        text(expected.success()), !"READY".equals(expected.state()) || !clock.instant().isBefore(expected.nextDue()));
            });
            Scope current = find(expected.key(), false);
            requireReadable(current);
            if (!current.generation().equals(expected.generation()) || current.version() != expected.version()) throw preparing();
            requireAuthorization(identity, current);
            return result;
        } catch (DataAccessException unavailable) {
            throw unavailable();
        }
    }

    private Map<String, Object> metadata(String json) {
        try { return mapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { }); }
        catch (java.io.IOException invalid) { throw unavailable(); }
    }

    @Override public Map<String, Object> status(RequestIdentity identity) {
        try {
            verify(identity);
            checkBarrier(identity);
            Scope scope = find(scopeKey(identity), false);
            return scope == null ? Map.of("state", "UNREGISTERED") : publicStatus(scope);
        } catch (DataAccessException unavailable) {
            throw unavailable();
        }
    }

    private void verify(RequestIdentity identity) {
        if (closed) throw unavailable();
        PrincipalInfo principal = verifier.verify(new PrincipalInfo(identity.authorization(), identity.tenantCode()));
        if (principal == null || !principal.verified() || !identity.ownerKey().equals(principal.ownerKey())
                || !identity.tenantCode().equals(principal.tenantCode())
                || !identity.authorization().equals(principal.authorization())) throw new GatewayAuthorizationException(403);
    }

    private String checkBarrier(RequestIdentity identity) {
        var barrierKey = new ReadSnapshots.Barrier.Key(identity.tenantCode(),
                ReadSnapshots.credentialHash(identity.tenantCode(), identity.authorization()));
        var state = barrier.read(barrierKey).orElse(null);
        if (state == null) {
            Instant now = clock.instant();
            barrier.writeIfCurrent(barrierKey, null, new ReadSnapshots.Barrier.State(UUID.randomUUID().toString(),
                    now.plus(ReadSnapshots.Settings.defaults().retention().multipliedBy(2)), 0, now));
            state = barrier.read(barrierKey).orElseThrow(JdbcCompanyDirectory::unavailable);
        }
        if (state.denialStatus() != 0) throw new GatewayAuthorizationException(state.denialStatus());
        return state.generation();
    }

    private void requireAuthorization(RequestIdentity identity, Scope scope) {
        if (!scope.authorizationGeneration().equals(checkBarrier(identity))) throw preparing();
    }

    private Scope register(RequestIdentity identity) {
        return register(identity, null);
    }

    private Scope register(RequestIdentity identity, String expectedGeneration) {
        String scopeKey = scopeKey(identity);
        String credentialHash = ReadSnapshots.credentialHash(identity.tenantCode(), identity.authorization());
        String authorizationGeneration = checkBarrier(identity);
        Scope observed = find(scopeKey, false);
        if (expectedGeneration != null && (observed == null || !expectedGeneration.equals(observed.generation()))) throw preparing();
        if (registrationMatches(observed, credentialHash, authorizationGeneration)) return observed;
        String generation = UUID.randomUUID().toString();
        String encrypted = encrypt(scopeKey, generation, identity.authorization());
        return transactions.execute(tx -> {
            Timestamp now = Timestamp.from(clock.instant());
            // Insert before locking: two first readers must not lock the same absent-key gap.
            jdbc.update("INSERT IGNORE INTO local_sync_scopes(scope_key,gateway_instance,tenant_code,owner_key,source,data_kind,"
                            + "credential_hash,credential_cipher,credential_generation,authorization_generation,state,next_due_at,create_time,update_time) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,'PREPARING',?,?,?)",
                    scopeKey, gateway, identity.tenantCode(), identity.ownerKey(), identity.source().wireValue(), directoryKind(identity.source()), credentialHash,
                    encrypted, generation, authorizationGeneration, now, now, now);
            Scope current = find(scopeKey, true);
            if (expectedGeneration != null && !expectedGeneration.equals(current.generation())) throw preparing();
            if (!registrationMatches(current, credentialHash, authorizationGeneration)) {
                jdbc.update("UPDATE local_sync_scopes SET credential_hash=?,credential_cipher=?,credential_generation=?,"
                                + "authorization_generation=?,published_generation=NULL,state='PREPARING',denial_status=0,"
                                + "failure_code=NULL,next_due_at=?,update_time=? WHERE scope_key=?",
                        credentialHash, encrypted, generation, authorizationGeneration, now, now, scopeKey);
            }
            return find(scopeKey, false);
        });
    }

    private static boolean registrationMatches(Scope scope, String credentialHash, String authorizationGeneration) {
        return scope != null && credentialHash.equals(scope.credentialHash())
                && authorizationGeneration.equals(scope.authorizationGeneration())
                && !"SYNC_CREDENTIAL_UNAVAILABLE".equals(scope.failure());
    }

    private Map<String, Object> localBody(RequestIdentity identity) {
        try {
            Scope scope = find(scopeKey(identity), false);
            requireReadable(scope);
            requireAuthorization(identity, scope);
            if (!scope.credentialHash().equals(ReadSnapshots.credentialHash(identity.tenantCode(), identity.authorization()))) throw preparing();
            List<Map<String, Object>> devices = jdbc.query("SELECT d.source_device_id,d.device_kind,d.device_name,d.install_address,d.metadata_json "
                            + "FROM local_company_devices d JOIN local_company_grants g ON g.scope_key=d.scope_key AND g.device_key=d.device_key "
                            + "WHERE d.scope_key=? AND g.batch_version=? ORDER BY d.device_kind,d.source_device_id",
                    (rs, row) -> {
                        Map<String, Object> device = new LinkedHashMap<>();
                        device.put("id", rs.getString(1));
                        device.put("kind", rs.getString(2));
                        device.put("name", rs.getString(3));
                        device.put("location", rs.getString(4));
                        Object subtype = metadata(rs.getString(5)).get("subtype");
                        if (subtype instanceof String) device.put("subtype", subtype);
                        return device;
                    }, scope.key(), scope.version());
            Scope after = find(scope.key(), false);
            if (after == null || after.version() != scope.version() || !after.generation().equals(scope.generation())) throw preparing();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("source", "company");
            body.put("observedAt", text(scope.observed()));
            body.put("synchronizedAt", text(scope.success()));
            body.put("devices", devices);
            body.put("_directoryGeneration", scope.generation());
            body.put("_directoryVersion", Long.toString(scope.version()));
            return body;
        } catch (DataAccessException unavailable) {
            throw unavailable();
        }
    }

    @Override public synchronized void start() {
        if (started || closed) return;
        started = true;
        cadence.scheduleWithFixedDelay(() -> {
            try { synchronizeDirectoriesDue(); }
            catch (RuntimeException unavailable) {
                System.getLogger(JdbcCompanyDirectory.class.getName()).log(System.Logger.Level.WARNING,
                        "Local company synchronization unavailable; persisted progress retained");
            }
        }, 0, 5, TimeUnit.SECONDS);
        cadence.scheduleWithFixedDelay(() -> {
            try { synchronizeCurrentDue(); }
            catch (RuntimeException unavailable) {
                System.getLogger(JdbcCompanyDirectory.class.getName()).log(System.Logger.Level.WARNING,
                        "Local current-data synchronization unavailable; persisted progress retained");
            }
        }, 0, 5, TimeUnit.SECONDS);
        cadence.scheduleWithFixedDelay(() -> {
            try { synchronizeHistoryDue(); }
            catch (RuntimeException unavailable) {
                System.getLogger(JdbcCompanyDirectory.class.getName()).log(System.Logger.Level.WARNING,
                        "Local historical synchronization unavailable; persisted progress retained");
            }
        }, 0, 5, TimeUnit.SECONDS);
    }

    @Override public void synchronizeDue() {
        synchronizeDirectoriesDue();
        synchronizeCurrentDue();
    }

    private void synchronizeDirectoriesDue() {
        if (closed || !syncLock.tryLock()) return;
        try {
            List<Scope> due = jdbc.query("SELECT * FROM local_sync_scopes WHERE gateway_instance=? "
                            + "AND data_kind IN ('COMPANY_DIRECTORY','OFFICE_DIRECTORY') AND next_due_at<=? "
                            + "ORDER BY CASE WHEN published_generation IS NULL OR published_generation<>credential_generation THEN 0 ELSE 1 END,next_due_at,id",
                    JdbcCompanyDirectory::scope, gateway, Timestamp.from(clock.instant()));
            for (Scope scope : due) {
                if (closed) break;
                // Keep version fencing without letting routine refresh discard every slow state sample.
                Scope sampling = activeStateScope;
                if (sampling != null && sampling.key().equals(scope.key()) && sampling.generation().equals(scope.generation())) continue;
                activeScope = scope.key();
                try { synchronize(scope); }
                finally { activeScope = null; }
            }
        } finally {
            syncLock.unlock();
        }
    }

    private void synchronizeCurrentDue() {
        if (closed || !currentLock.tryLock()) return;
        try {
            List<Scope> due = jdbc.query("SELECT s.* FROM local_sync_scopes s LEFT JOIN local_device_state_sync p "
                            + "ON p.scope_key=s.scope_key AND p.credential_generation=s.credential_generation "
                            + "WHERE s.gateway_instance=? AND s.data_kind IN ('COMPANY_DIRECTORY','OFFICE_DIRECTORY') "
                            + "AND s.published_generation=s.credential_generation AND s.denial_status=0 AND s.state='READY' "
                            + "AND (p.last_attempt_at IS NULL OR p.last_attempt_at<=?) ORDER BY s.id",
                    JdbcCompanyDirectory::scope, gateway, Timestamp.from(clock.instant().minusSeconds(SYNC_INTERVAL_SECONDS)));
            for (Scope candidate : due) {
                if (closed) break;
                Scope scope = find(candidate.key(), false);
                RequestIdentity identity = null;
                try {
                    requireReadable(scope);
                    if (!"READY".equals(scope.state())) continue;
                    identity = new RequestIdentity(decrypt(scope), scope.tenant(), scope.source(), scope.owner());
                    verify(identity);
                    requireAuthorization(identity, scope);
                    AuthorizedDeviceScope devices = loadBatch(identity).scope();
                    Timestamp dispatched = Timestamp.from(clock.instant());
                    if (!synchronizeStates(scope, identity, devices)) continue;
                    jdbc.update("INSERT INTO local_device_state_sync(scope_key,state,credential_generation,last_attempt_at,create_time,update_time) "
                                    + "VALUES (?,'DISPATCHED',?,?,?,?) ON DUPLICATE KEY UPDATE state='DISPATCHED',credential_generation=VALUES(credential_generation),"
                                    + "last_attempt_at=VALUES(last_attempt_at),update_time=VALUES(update_time)",
                            scope.key(), scope.generation(), dispatched, dispatched, dispatched);
                    synchronizeSafety(scope, identity, false);
                    if (identity.source() == IotSourceEnum.COMPANY) {
                        synchronizeEnergy(scope, identity, false);
                        if (devices.devices().stream().anyMatch(device -> "environment".equals(device.kind())))
                            synchronizeEnvironment(scope, identity, false);
                    }
                } catch (RuntimeException failure) {
                    if (scope != null && failure instanceof BusinessException business
                            && (business.httpStatus() == 401 || business.httpStatus() == 403)) {
                        jdbc.update("UPDATE local_sync_scopes SET state='DENIED',published_generation=NULL,denial_status=?,"
                                        + "failure_code='DIRECTORY_SYNC_DENIED',update_time=? WHERE scope_key=? AND credential_generation=?",
                                business.httpStatus(), Timestamp.from(clock.instant()), scope.key(), scope.generation());
                        if (identity != null) snapshots.revoke(snapshotIdentity(identity), business);
                    }
                }
            }
        } finally {
            currentLock.unlock();
        }
    }

    @Override public void synchronizeHistoryDue() {
        if (closed || !historyLock.tryLock()) return;
        try {
            List<Scope> scopes = jdbc.query("SELECT * FROM local_sync_scopes WHERE gateway_instance=? AND data_kind IN ('COMPANY_DIRECTORY','OFFICE_DIRECTORY') "
                            + "AND published_generation=credential_generation AND denial_status=0 ORDER BY id",
                    JdbcCompanyDirectory::scope, gateway);
            for (Scope scope : scopes) {
                if (closed) break;
                synchronizeSafety(scope, null, true);
                if (scope.source() == IotSourceEnum.COMPANY) {
                    if (energyHistory.fullDue(scope.key(), scope.generation())) synchronizeEnergy(scope, null, true);
                    if (environmentHistory.fullDue(scope.key(), scope.generation())) synchronizeEnvironment(scope, null, true);
                }
            }
        } finally {
            historyLock.unlock();
        }
    }

    private void synchronizeSafety(Scope scope, RequestIdentity currentIdentity, boolean full) {
        RequestIdentity identity = currentIdentity;
        try {
            if (identity == null) identity = new RequestIdentity(decrypt(scope), scope.tenant(), scope.source(), scope.owner());
            verify(identity);
            requireAuthorization(identity, scope);
            RequestIdentity captured = identity;
            safetyHistory.synchronize(identity, historySource.get(), full,
                    () -> verify(captured), () -> requireAuthorization(captured, scope));
        } catch (RuntimeException failure) {
            if (failure instanceof BusinessException business && (business.httpStatus() == 401 || business.httpStatus() == 403)) {
                jdbc.update("UPDATE local_sync_scopes SET state='DENIED',published_generation=NULL,denial_status=?,failure_code='DIRECTORY_SYNC_DENIED',update_time=? "
                        + "WHERE scope_key=? AND credential_generation=?", business.httpStatus(), Timestamp.from(clock.instant()), scope.key(), scope.generation());
                if (identity != null) snapshots.revoke(snapshotIdentity(identity), business);
            }
        }
    }

    private void synchronizeEnvironment(Scope scope, RequestIdentity currentIdentity, boolean full) {
        RequestIdentity identity = currentIdentity;
        Instant attempt = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        String run = UUID.randomUUID().toString();
        String dataKey = null;
        try {
            if (identity == null) identity = new RequestIdentity(decrypt(scope), scope.tenant(), scope.source(), scope.owner());
            verify(identity);
            requireAuthorization(identity, scope);
            environmentHistory.revalidate(identity);
            dataKey = environmentHistory.begin(scope.key(), scope.generation(), full, attempt);
            if (dataKey == null) return;
            new EnvironmentHistoryCollector(historySource.get(), environmentHistory).collect(identity, run, dataKey,
                    full ? null : environmentHistory.currentCutoff(dataKey), full);
            verify(identity);
            RequestIdentity captured = identity;
            environmentHistory.publish(run, scope.key(), scope.generation(), dataKey, full, attempt, () -> {
                requireAuthorization(captured, scope);
                environmentHistory.revalidate(captured);
            });
        } catch (RuntimeException failure) {
            environmentHistory.failed(dataKey, full, attempt);
            if (failure instanceof BusinessException business && (business.httpStatus() == 401 || business.httpStatus() == 403)
                    && (failure instanceof GatewayAuthorizationException || !"FORBIDDEN".equals(business.code()))) {
                jdbc.update("UPDATE local_sync_scopes SET state='DENIED',published_generation=NULL,denial_status=?,failure_code='DIRECTORY_SYNC_DENIED',update_time=? "
                        + "WHERE scope_key=? AND credential_generation=?", business.httpStatus(), Timestamp.from(clock.instant()), scope.key(), scope.generation());
                if (identity != null) snapshots.revoke(snapshotIdentity(identity), business);
            }
        } finally {
            environmentHistory.discard(run);
        }
    }

    private void synchronizeEnergy(Scope scope, RequestIdentity currentIdentity, boolean full) {
        RequestIdentity identity = currentIdentity;
        Instant attempt = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        String run = UUID.randomUUID().toString();
        try {
            if (identity == null) identity = new RequestIdentity(decrypt(scope), scope.tenant(), scope.source(), scope.owner());
            verify(identity);
            requireAuthorization(identity, scope);
            if (!full && jdbc.queryForObject("SELECT COUNT(*) FROM local_energy_sync e JOIN local_sync_scopes s ON s.scope_key=e.scope_key "
                            + "WHERE e.scope_key=? AND e.credential_generation=? AND e.grants_published_at>=s.last_success_at",
                    Integer.class, scope.key(), scope.generation()) == 0) return;
            AuthorizedDeviceScope grant = full ? synchronizeEnergyGrants(scope, identity) : energyHistory.authorizedScope(identity);
            RequestIdentity captured = identity;
            if (!energyHistory.begin(scope.key(), scope.generation(), full, attempt)) return;
            java.util.Set<String> ids = new java.util.LinkedHashSet<>();
            for (AuthorizedDevice meter : grant.devices()) ids.add(meter.id());
            if (!ids.isEmpty()) new EnergyHistoryCollector(historySource.get(), energyHistory).collect(identity, run, scope.key(), ids,
                    full ? null : energyHistory.currentCutoff(scope.key(), scope.generation()), full);
            verify(identity);
            energyHistory.publish(run, scope.key(), scope.generation(), List.copyOf(ids), full, attempt,
                    () -> requireAuthorization(captured, scope));
        } catch (RuntimeException failure) {
            energyHistory.failed(scope.key(), scope.generation(), full, attempt);
            if (failure instanceof BusinessException business && (business.httpStatus() == 401 || business.httpStatus() == 403)) {
                jdbc.update("UPDATE local_sync_scopes SET state='DENIED',published_generation=NULL,denial_status=?,failure_code='DIRECTORY_SYNC_DENIED',update_time=? "
                        + "WHERE scope_key=? AND credential_generation=?",business.httpStatus(),Timestamp.from(clock.instant()),scope.key(),scope.generation());
                if (identity != null) snapshots.revoke(snapshotIdentity(identity), business);
            }
        } finally {
            energyHistory.discard(run);
        }
    }

    private void synchronize(Scope scope) {
        RequestIdentity identity = null;
        Instant attempted = clock.instant();
        if (jdbc.update("UPDATE local_sync_scopes SET last_attempt_at=?,next_due_at=?,update_time=? "
                        + "WHERE scope_key=? AND credential_generation=? AND batch_version=? AND next_due_at<=?",
                Timestamp.from(attempted), Timestamp.from(attempted.plusSeconds(SYNC_INTERVAL_SECONDS)), Timestamp.from(attempted),
                scope.key(), scope.generation(), scope.version(), Timestamp.from(attempted)) != 1) return;
        try {
            identity = new RequestIdentity(decrypt(scope), scope.tenant(), scope.source(), scope.owner());
            if (!scope.key().equals(scopeKey(identity))) throw new IllegalStateException("Synchronization scope mismatch");
            verify(identity);
            scope = register(identity, scope.generation());
            requireAuthorization(identity, scope);
            if (identity.source() == IotSourceEnum.COMPANY) {
                RequestIdentity catalogIdentity = identity;
                Scope registration = scope;
                catalog.synchronize(identity, () -> {
                    verify(catalogIdentity);
                    requireAuthorization(catalogIdentity, registration);
                });
            }
            DeviceDirectoryBatch batch = upstream.collectDirectory(identity).block();
            var collected = batch == null ? null : batch.scope();
            if (collected == null || !identity.equals(collected.identity()) || collected.source() != identity.source()) {
                throw new IllegalStateException("Invalid directory scope");
            }
            Map<String, AuthorizedDevice> unique = new LinkedHashMap<>();
            for (AuthorizedDevice device : collected.devices()) {
                String deviceKey = hash(device.kind() + "\u0000" + device.id());
                AuthorizedDevice previous = unique.putIfAbsent(deviceKey, device);
                if (previous != null && !previous.equals(device)) throw new IllegalStateException("Conflicting source identity");
            }
            // Revalidate trust after network collection; long or revoked runs cannot publish.
            verify(identity);
            requireAuthorization(identity, scope);
            scope = publish(scope, unique, batch.regions());
            if (scope != null) {
                RequestIdentity captured = identity;
                Scope published = scope;
                AuthorizedDeviceScope safetyGrant = identity.source() == IotSourceEnum.OFFICE
                        ? collected : upstream.loadAuthorizedSafetyScope(identity).block();
                verify(identity);
                safetyHistory.publishGrants(identity, safetyGrant, () -> requireAuthorization(captured, published));
            }
            if (scope != null && identity.source() == IotSourceEnum.COMPANY) {
                try { synchronizeEnergyGrants(scope, identity); }
                catch (RuntimeException failure) {
                    energyHistory.failed(scope.key(), scope.generation(), false, clock.instant());
                    if (failure instanceof BusinessException business
                            && (business.httpStatus() == 401 || business.httpStatus() == 403)) throw failure;
                }
            }
        } catch (RuntimeException failure) {
            int denial = failure instanceof BusinessException business && (business.httpStatus() == 401 || business.httpStatus() == 403)
                    ? business.httpStatus() : 0;
            boolean invalidCredential = failure instanceof BusinessException business
                    && "SYNC_CREDENTIAL_UNAVAILABLE".equals(business.code());
            Instant now = clock.instant();
            jdbc.update("UPDATE local_sync_scopes SET state=?,failure_code=?,denial_status=?,"
                            + "published_generation=CASE WHEN ?<>0 THEN NULL ELSE published_generation END,"
                            + "next_due_at=?,update_time=? WHERE scope_key=? AND credential_generation=? AND batch_version=?",
                    denial == 0 ? "FAILED" : "DENIED", invalidCredential ? "SYNC_CREDENTIAL_UNAVAILABLE"
                            : denial == 0 ? "DIRECTORY_SYNC_UNAVAILABLE" : "DIRECTORY_SYNC_DENIED",
                    denial, denial != 0 || invalidCredential ? 1 : 0, Timestamp.from(now.plusSeconds(SYNC_INTERVAL_SECONDS)), Timestamp.from(now),
                    scope.key(), scope.generation(), scope.version());
            if (denial != 0 && identity != null) snapshots.revoke(snapshotIdentity(identity), new GatewayAuthorizationException(denial));
        }
    }

    private AuthorizedDeviceScope synchronizeEnergyGrants(Scope scope, RequestIdentity identity) {
        AuthorizedDeviceScope grant = upstream.loadAuthorizedEnergyScope(identity).block();
        if (grant == null || !identity.equals(grant.identity()) || grant.source() != IotSourceEnum.COMPANY)
            throw new IllegalStateException("Invalid dedicated energy scope");
        verify(identity);
        energyHistory.publishGrants(scope.key(), scope.generation(), grant, () -> requireAuthorization(identity, scope));
        return grant;
    }

    private boolean synchronizeStates(Scope scope, RequestIdentity identity, AuthorizedDeviceScope collected) {
        JdbcDeviceObservations.CollectionRun attempt;
        if (!syncLock.tryLock()) return false;
        try {
            Scope sampling = activeStateScope;
            if (sampling != null && sampling.key().equals(scope.key()) && sampling.generation().equals(scope.generation())) return false;
            Scope latest = find(scope.key(), false);
            if (latest == null || latest.version() != scope.version() || !latest.generation().equals(scope.generation())) return false;
            attempt = observations.begin(scope.key(), scope.generation(), scope.version(), collected);
            if (attempt == null) return true;
            activeStateScope = scope;
        } finally {
            syncLock.unlock();
        }
        try {
            var requested = AuthorizedDeviceScope.of(identity, identity.source(), attempt.devices());
            var states = upstream.collectCurrentStates(identity, requested).block();
            verify(identity);
            requireAuthorization(identity, scope);
            observations.publish(scope.key(), scope.generation(), scope.version(), attempt, states,
                    () -> requireAuthorization(identity, scope));
        } catch (RuntimeException failure) {
            observations.failed(scope.generation(), attempt);
            if (failure instanceof BusinessException business && (business.httpStatus() == 401 || business.httpStatus() == 403)) throw failure;
        } finally {
            if (activeStateScope == scope) activeStateScope = null;
        }
        return true;
    }

    private Scope publish(Scope expected, Map<String, AuthorizedDevice> devices, List<OfficeRegion> regions) {
        return transactions.execute(tx -> {
            Scope current = find(expected.key(), true);
            if (current == null || !current.generation().equals(expected.generation()) || current.version() != expected.version()) return null;
            Map<String, StoredDevice> previous = new LinkedHashMap<>();
            jdbc.query("SELECT * FROM local_company_devices WHERE scope_key=? ORDER BY id",
                    (org.springframework.jdbc.core.RowCallbackHandler) rs -> previous.put(rs.getString("device_key"),
                            new StoredDevice(rs.getLong("id"), new DeviceBody(rs.getString("source_device_id"), rs.getString("device_kind"),
                                    rs.getString("device_name"), rs.getString("install_address"), rs.getBoolean("observation_only"),
                                    rs.getString("control_ref"), rs.getString("office_device_ref"), rs.getString("hub_device_id"),
                                    rs.getString("smart_area_code"), metadata(rs.getString("metadata_json")), rs.getString("catalog_fingerprint")))),
                    current.key());
            Map<String, Map<Integer, List<String>>> previousPaths = new LinkedHashMap<>();
            jdbc.query("SELECT device_key,path_index,segment_name FROM local_company_locations WHERE scope_key=? ORDER BY device_key,path_index,segment_index",
                    (org.springframework.jdbc.core.RowCallbackHandler) rs -> previousPaths.computeIfAbsent(rs.getString(1), ignored -> new LinkedHashMap<>())
                            .computeIfAbsent(rs.getInt(2), ignored -> new ArrayList<>()).add(rs.getString(3)), current.key());
            Map<String, List<String>> previousOffices = new LinkedHashMap<>();
            jdbc.query("SELECT device_key,office_id FROM local_company_offices WHERE scope_key=? ORDER BY device_key,id",
                    (org.springframework.jdbc.core.RowCallbackHandler) rs -> previousOffices.computeIfAbsent(rs.getString(1), ignored -> new ArrayList<>())
                            .add(rs.getString(2)), current.key());
            long version = Math.incrementExact(current.version());
            Timestamp now = Timestamp.from(clock.instant());
            List<Object[]> inserts = new ArrayList<>();
            List<Object[]> updates = new ArrayList<>();
            List<Object[]> reordered = new ArrayList<>();
            List<Object[]> grants = new ArrayList<>();
            List<Object[]> changedPaths = new ArrayList<>();
            List<Object[]> locations = new ArrayList<>();
            List<Object[]> changedOffices = new ArrayList<>();
            List<Object[]> offices = new ArrayList<>();
            long lastRetainedId = 0;
            boolean append = false;
            for (var entry : devices.entrySet()) {
                String deviceKey = entry.getKey();
                AuthorizedDevice device = entry.getValue();
                StoredDevice old = previous.remove(deviceKey);
                String metadataJson = json(device.metadata());
                String fingerprint = device.catalogFingerprint();
                DeviceBody body = new DeviceBody(device.id(), device.kind(), device.name(), device.installAddress(),
                        device.observationOnly(), device.controlRef(), device.officeDeviceRef(), device.hubDeviceId(),
                        device.smartAreaCode(), metadata(metadataJson), fingerprint);
                // Existing readers use row order. Only an actual source-order change rebuilds its affected tail.
                append = append || old == null || old.rowId() < lastRetainedId;
                if (append) {
                    if (old != null) reordered.add(new Object[]{current.key(), deviceKey});
                    inserts.add(new Object[]{current.key(), deviceKey, device.id(), device.kind(), device.name(), device.installAddress(),
                            device.observationOnly() ? 1 : 0, device.controlRef(), device.officeDeviceRef(), device.hubDeviceId(),
                            device.smartAreaCode(), metadataJson, fingerprint, now, now});
                } else {
                    lastRetainedId = old.rowId();
                    if (!body.equals(old.body())) updates.add(new Object[]{device.id(), device.kind(), device.name(), device.installAddress(),
                            device.observationOnly() ? 1 : 0, device.controlRef(), device.officeDeviceRef(), device.hubDeviceId(),
                            device.smartAreaCode(), metadataJson, fingerprint, now, current.key(), deviceKey});
                }
                if (old == null) grants.add(new Object[]{current.key(), deviceKey, version, now, now});
                if (!List.copyOf(previousPaths.getOrDefault(deviceKey, Map.of()).values()).equals(device.locationPaths())) {
                    changedPaths.add(new Object[]{current.key(), deviceKey});
                    for (int path = 0; path < device.locationPaths().size(); path++) {
                        List<String> segments = device.locationPaths().get(path);
                        for (int segment = 0; segment < segments.size(); segment++)
                            locations.add(new Object[]{current.key(), deviceKey, path, segment, segments.get(segment), now, now});
                    }
                }
                if (!previousOffices.getOrDefault(deviceKey, List.of()).equals(device.officeIds())) {
                    changedOffices.add(new Object[]{current.key(), deviceKey});
                    for (String office : device.officeIds()) offices.add(new Object[]{current.key(), deviceKey, office, now, now});
                }
            }
            List<Object[]> removed = previous.keySet().stream().map(deviceKey -> new Object[]{current.key(), deviceKey}).toList();
            if (!removed.isEmpty()) for (String table : List.of("local_company_grants", "local_company_locations", "local_company_offices", "local_company_devices"))
                jdbc.batchUpdate("DELETE FROM " + table + " WHERE scope_key=? AND device_key=?", removed);
            if (!reordered.isEmpty()) jdbc.batchUpdate("DELETE FROM local_company_devices WHERE scope_key=? AND device_key=?", reordered);
            if (!changedPaths.isEmpty()) jdbc.batchUpdate("DELETE FROM local_company_locations WHERE scope_key=? AND device_key=?", changedPaths);
            if (!changedOffices.isEmpty()) jdbc.batchUpdate("DELETE FROM local_company_offices WHERE scope_key=? AND device_key=?", changedOffices);
            if (!updates.isEmpty()) jdbc.batchUpdate("UPDATE local_company_devices SET source_device_id=?,device_kind=?,device_name=?,install_address=?,"
                    + "observation_only=?,control_ref=?,office_device_ref=?,hub_device_id=?,smart_area_code=?,metadata_json=?,catalog_fingerprint=?,update_time=? "
                    + "WHERE scope_key=? AND device_key=?", updates);
            if (!inserts.isEmpty()) jdbc.batchUpdate("INSERT INTO local_company_devices(scope_key,device_key,source_device_id,device_kind,device_name,"
                    + "install_address,observation_only,control_ref,office_device_ref,hub_device_id,smart_area_code,metadata_json,catalog_fingerprint,create_time,update_time) "
                    + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", inserts);
            jdbc.update("UPDATE local_company_grants SET batch_version=?,update_time=? WHERE scope_key=?", version, now, current.key());
            if (!grants.isEmpty()) jdbc.batchUpdate("INSERT INTO local_company_grants(scope_key,device_key,batch_version,create_time,update_time) VALUES (?,?,?,?,?)", grants);
            if (!locations.isEmpty()) jdbc.batchUpdate("INSERT INTO local_company_locations(scope_key,device_key,path_index,segment_index,segment_name,create_time,update_time) VALUES (?,?,?,?,?,?,?)", locations);
            if (!offices.isEmpty()) jdbc.batchUpdate("INSERT INTO local_company_offices(scope_key,device_key,office_id,create_time,update_time) VALUES (?,?,?,?,?)", offices);
            publishRegions(current.key(), regions, version, now);
            jdbc.update("UPDATE local_sync_scopes SET published_generation=credential_generation,batch_version=?,state='READY',"
                            + "denial_status=0,failure_code=NULL,last_success_at=?,next_due_at=?,update_time=? WHERE scope_key=?",
                    version, now, Timestamp.from(now.toInstant().plusSeconds(SYNC_INTERVAL_SECONDS)), now, current.key());
            return find(current.key(), false);
        });
    }

    private record DeviceBody(String sourceId, String kind, String name, String address, boolean observationOnly,
            String controlRef, String officeDeviceRef, String hubDeviceId, String smartAreaCode,
            Map<String, Object> metadata, String fingerprint) { }
    private record StoredDevice(long rowId, DeviceBody body) { }
    private record StoredRegion(long rowId, String name) { }

    private void publishRegions(String key, List<OfficeRegion> regions, long version, Timestamp now) {
        Map<String, StoredRegion> previous = new LinkedHashMap<>();
        jdbc.query("SELECT id,source_region_id,region_name FROM local_office_regions WHERE scope_key=? ORDER BY id",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> previous.put(rs.getString(2), new StoredRegion(rs.getLong(1), rs.getString(3))), key);
        List<Object[]> inserts = new ArrayList<>();
        List<Object[]> updates = new ArrayList<>();
        List<Object[]> deletes = new ArrayList<>();
        long lastRetainedId = 0;
        boolean append = false;
        for (OfficeRegion region : regions) {
            StoredRegion old = previous.remove(region.id());
            append = append || old == null || old.rowId() < lastRetainedId;
            if (append) {
                if (old != null) deletes.add(new Object[]{key, region.id()});
                inserts.add(new Object[]{key, region.id(), region.name(), version, now, now});
            } else {
                lastRetainedId = old.rowId();
                if (!region.name().equals(old.name()))
                    updates.add(new Object[]{region.name(), version, now, key, region.id()});
            }
        }
        previous.keySet().forEach(id -> deletes.add(new Object[]{key, id}));
        if (!deletes.isEmpty()) jdbc.batchUpdate("DELETE FROM local_office_regions WHERE scope_key=? AND source_region_id=?", deletes);
        if (!updates.isEmpty()) jdbc.batchUpdate("UPDATE local_office_regions SET region_name=?,batch_version=?,update_time=? WHERE scope_key=? AND source_region_id=?", updates);
        jdbc.update("UPDATE local_office_regions SET batch_version=?,update_time=? WHERE scope_key=? AND batch_version<>?", version, now, key, version);
        if (!inserts.isEmpty()) jdbc.batchUpdate("INSERT INTO local_office_regions(scope_key,source_region_id,region_name,batch_version,create_time,update_time) VALUES (?,?,?,?,?,?)", inserts);
    }

    private Scope find(String scopeKey, boolean lock) {
        List<Scope> rows = jdbc.query("SELECT * FROM local_sync_scopes WHERE scope_key=?" + (lock ? " FOR UPDATE" : ""),
                JdbcCompanyDirectory::scope, scopeKey);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static Scope scope(ResultSet rs, int row) throws SQLException {
        return new Scope(rs.getString("scope_key"), rs.getString("tenant_code"), rs.getString("owner_key"), IotSourceEnum.parse(rs.getString("source")),
                rs.getString("credential_hash"), rs.getString("credential_cipher"), rs.getString("credential_generation"),
                rs.getString("authorization_generation"),
                rs.getString("published_generation"), rs.getLong("batch_version"), rs.getString("state"), rs.getInt("denial_status"),
                rs.getString("failure_code"), instant(rs, "source_observed_at"), instant(rs, "last_attempt_at"),
                instant(rs, "last_success_at"), instant(rs, "next_due_at"));
    }

    private static Instant instant(ResultSet rs, String name) throws SQLException {
        Timestamp value = rs.getTimestamp(name);
        return value == null ? null : value.toInstant();
    }

    private static void requireReadable(Scope scope) {
        if (scope != null && scope.denial() != 0) throw new GatewayAuthorizationException(scope.denial());
        if (scope == null || scope.version() == 0 || !scope.generation().equals(scope.published())) throw preparing();
    }

    private static Map<String, Object> publicStatus(Scope scope) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("state", scope.state());
        result.put("version", Long.toString(scope.version()));
        result.put("sourceObservedAt", text(scope.observed()));
        result.put("lastAttemptAt", text(scope.attempt()));
        result.put("lastSuccessAt", text(scope.success()));
        result.put("failureCode", scope.failure());
        return result;
    }

    private String scopeKey(RequestIdentity identity) {
        return LocalDataKeys.directory(gateway, identity);
    }

    private static String directoryKind(IotSourceEnum source) { return source.name() + "_DIRECTORY"; }

    private static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private String encrypt(String scopeKey, String generation, String authorization) {
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, scopeKey, generation, iv);
            return Base64.getEncoder().encodeToString(iv) + ":"
                    + Base64.getEncoder().encodeToString(cipher.doFinal(authorization.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception invalid) { throw new IllegalStateException("Synchronization credential encryption unavailable"); }
    }

    private String decrypt(Scope scope) {
        try {
            String[] parts = scope.cipher().split(":", -1);
            if (parts.length != 2) throw new IllegalArgumentException();
            byte[] iv = Base64.getDecoder().decode(parts[0]);
            if (iv.length != 12) throw new IllegalArgumentException();
            return new String(cipher(Cipher.DECRYPT_MODE, scope.key(), scope.generation(), iv)
                    .doFinal(Base64.getDecoder().decode(parts[1])), StandardCharsets.UTF_8);
        } catch (Exception invalid) {
            throw new BusinessException("SYNC_CREDENTIAL_UNAVAILABLE", "同步凭据不可用，请重新登记", 503);
        }
    }

    private Cipher cipher(int mode, String scopeKey, String generation, byte[] iv) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        cipher.updateAAD(("LOCAL_COMPANY_SYNC\u0000" + scopeKey + "\u0000" + generation).getBytes(StandardCharsets.UTF_8));
        return cipher;
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception invalid) { throw new IllegalStateException("Invalid directory metadata"); }
    }

    private static ReadSnapshots.Identity snapshotIdentity(RequestIdentity identity) {
        return new ReadSnapshots.Identity(identity.tenantCode(), identity.authorization(), identity.source().name(), identity.ownerKey());
    }
    private static String text(Instant value) { return value == null ? null : value.toString(); }
    private static BusinessException preparing() { return new BusinessException("IOT_SCOPE_PREPARING", "当前设备目录准备中，请稍后重试", 503); }
    private static BusinessException unavailable() { return new BusinessException("IOT_SCOPE_UNAVAILABLE", "本地设备目录不可用，请稍后重试", 503); }

    @Override public synchronized void close() {
        closed = true;
        cadence.shutdownNow();
    }

    private record Scope(String key, String tenant, String owner, IotSourceEnum source, String credentialHash, String cipher,
            String generation, String authorizationGeneration, String published, long version, String state, int denial, String failure,
            Instant observed, Instant attempt, Instant success, Instant nextDue) {
        @Override public String toString() { return "CompanySyncScope[redacted]"; }
    }
}
