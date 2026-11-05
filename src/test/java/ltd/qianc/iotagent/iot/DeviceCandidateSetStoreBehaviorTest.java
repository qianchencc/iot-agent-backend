package ltd.qianc.iotagent.iot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import ltd.qianc.iotagent.error.BusinessException;
import ltd.qianc.iotagent.schema.SchemaMigrations;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 候选集句柄行为：旧 JSON 句柄迁移后仍可查询、同会话并发保存句柄唯一（序号行锁串行化，
 * O(1) 分配不回退不重号）、显式句柄推进序号、句柄冲突按存储不可用失败。走 iot 模块入口构造存储。
 */
class DeviceCandidateSetStoreBehaviorTest {
    private static final String CONVERSATION = "conv-1";

    private JdbcTemplate jdbc;
    private DeviceCandidateSetStore store;

    @BeforeEach
    void setUp() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:candidate_store_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE device_candidate_sets (id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY, "
                + "candidate_set_id VARCHAR(64) NOT NULL, conversation_id VARCHAR(64) NOT NULL, "
                + "tenant_code VARCHAR(128) NOT NULL, owner_key CHAR(64) NOT NULL, source VARCHAR(16) NOT NULL, "
                + "catalog_fingerprint CHAR(64) NOT NULL, payload_json TEXT NOT NULL, complete TINYINT NOT NULL, "
                + "total_matches INT NOT NULL, created_at VARCHAR(64) NOT NULL, create_time DATETIME NOT NULL, "
                + "update_time DATETIME NOT NULL, UNIQUE KEY uk_device_candidate_set_id (candidate_set_id), "
                + "KEY ix_device_candidate_conversation (conversation_id, tenant_code, owner_key, source))");
        insertLegacyCandidate("legacy-set-1", CONVERSATION, "s1");
        insertLegacyCandidate("legacy-set-2", "conv-2", "s1");
        insertLegacyCandidate("legacy-full-id-a", "full-id-only", null);
        insertLegacyCandidate("legacy-full-id-b", "full-id-only", null);
        new SchemaMigrations(source).ensureDeviceCandidateSets();
        store = IotAdapterFactory.candidateSets(jdbc, new ObjectMapper());
    }

    @Test
    void migratedLegacyHandleRemainsFindableAndAllocationContinuesAfterIt() {
        assertThat(store.find("s1", CONVERSATION)).isPresent()
                .get().extracting(DeviceCandidateRecord::candidateSetId).isEqualTo("legacy-set-1");
        assertThat(store.find("legacy-set-1", CONVERSATION)).isPresent();
        assertThat(store.find("s1", "conv-2")).isPresent()
                .get().extracting(DeviceCandidateRecord::candidateSetId).isEqualTo("legacy-set-2");

        DeviceCandidateRecord next = store.save(record("set-2", CONVERSATION, null));
        assertThat(next.handle()).isEqualTo("s2");
        assertThat(store.find("s2", CONVERSATION)).isPresent()
                .get().extracting(DeviceCandidateRecord::candidateSetId).isEqualTo("set-2");
        // 会话之间句柄独立计数。
        assertThat(store.save(record("set-3", "conv-2", null)).handle()).isEqualTo("s2");
    }

    @Test
    void handlelessLegacyCandidatesKeepTheirFullIdsWithoutInventedShortReferences() {
        assertThat(store.find("legacy-full-id-a", "full-id-only")).isPresent()
                .get().extracting(DeviceCandidateRecord::handle).isNull();
        assertThat(store.find("legacy-full-id-b", "full-id-only")).isPresent()
                .get().extracting(DeviceCandidateRecord::handle).isNull();
        assertThat(store.find("s1", "full-id-only")).isEmpty();

        DeviceCandidateRecord next = store.save(record("new-after-legacy", "full-id-only", null));
        assertThat(next.handle()).isEqualTo("s1");
        assertThat(store.find("s1", "full-id-only")).isPresent()
                .get().extracting(DeviceCandidateRecord::candidateSetId).isEqualTo("new-after-legacy");
        assertThat(store.find("legacy-full-id-a", "full-id-only")).isPresent()
                .get().extracting(DeviceCandidateRecord::handle).isNull();
    }

    @Test
    void explicitHandleAdvancesTheSequenceSoNextAllocationDoesNotCollide() {
        assertThat(store.save(record("set-explicit", CONVERSATION, "s5")).handle()).isEqualTo("s5");
        assertThat(store.save(record("set-after", CONVERSATION, null)).handle()).isEqualTo("s6");
        assertThat(jdbc.queryForObject(
                "SELECT last_handle_no FROM device_candidate_set_sequences WHERE conversation_id=?",
                Long.class, CONVERSATION)).isEqualTo(6);
    }

    @Test
    void concurrentSavesInOneConversationReturnUniqueHandles() throws Exception {
        int threads = 8;
        int perThread = 5;
        ThreadPoolExecutor pool = new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(threads * perThread), new ThreadPoolExecutor.CallerRunsPolicy());
        try {
            CountDownLatch start = new CountDownLatch(1);
            Set<String> handles = ConcurrentHashMap.newKeySet();
            List<Future<Void>> jobs = IntStream.range(0, threads)
                    .<Future<Void>>mapToObj(thread -> pool.submit(() -> {
                        start.await();
                        for (int i = 0; i < perThread; i++) {
                            DeviceCandidateRecord saved = store.save(
                                    record("concurrent-" + thread + "-" + i, CONVERSATION, null));
                            handles.add(saved.handle());
                        }
                        return null;
                    })).toList();
            start.countDown();
            for (Future<Void> job : jobs) {
                job.get(60, TimeUnit.SECONDS);
            }
            assertThat(handles).hasSize(threads * perThread);
            assertThat(handles).allMatch(handle -> handle.matches("^s\\d+$"));
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM device_candidate_sets WHERE conversation_id=?", Long.class, CONVERSATION))
                    .isEqualTo(1L + threads * perThread);
            Long max = handles.stream().map(handle -> Long.parseLong(handle.substring(1)))
                    .max(Long::compare).orElseThrow();
            // 旧句柄 s1 之上顺序分配：1..(1+N) 连续无空洞，所有成功返回的句柄唯一。
            assertThat(max).isEqualTo(1L + threads * perThread);
            assertThat(jdbc.queryForObject(
                    "SELECT last_handle_no FROM device_candidate_set_sequences WHERE conversation_id=?",
                    Long.class, CONVERSATION)).isEqualTo(1L + threads * perThread);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void duplicateExplicitHandleFailsAsStoreUnavailableWithoutRewritingHistory() {
        assertThatThrownBy(() -> store.save(record("set-explicit", CONVERSATION, "s1")))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("CANDIDATE_SET_STORE_UNAVAILABLE");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM device_candidate_sets WHERE conversation_id=? AND handle='s1'",
                Long.class, CONVERSATION)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT handle FROM device_candidate_sets WHERE candidate_set_id='legacy-set-1'", String.class))
                .isEqualTo("s1");
        // 失败的显式保存不推进序号。
        assertThat(store.save(record("set-after-failure", CONVERSATION, null)).handle()).isEqualTo("s2");
    }

    // ------------------------------------------------------------------ fixtures

    private static DeviceCandidateRecord record(String candidateSetId, String conversationId, String handle) {
        return new DeviceCandidateRecord(candidateSetId, conversationId, "tenant", "owner-key", IotSourceEnum.OFFICE,
                "f".repeat(64), new DeviceCandidateQuery("lighting", List.of(), null, List.of(), List.of(), null, null),
                List.of(), true, 0, null, "exact", handle);
    }

    private void insertLegacyCandidate(String candidateSetId, String conversationId, String jsonHandle) {
        String payload = "{\"candidateSetId\":\"" + candidateSetId + "\",\"conversationId\":\"" + conversationId
                + "\",\"tenantCode\":\"tenant\",\"ownerKey\":\"owner-key\",\"source\":\"OFFICE\","
                + "\"catalogFingerprint\":\"" + "f".repeat(64) + "\",\"query\":{\"kind\":\"lighting\"},"
                + "\"members\":[],\"complete\":true,\"totalMatches\":0,\"createdAt\":\"2026-01-01T00:00:00Z\","
                + "\"resolutionMode\":\"exact\""
                + (jsonHandle == null ? "" : ",\"handle\":\"" + jsonHandle + "\"") + "}";
        jdbc.update("INSERT INTO device_candidate_sets(candidate_set_id,conversation_id,tenant_code,owner_key,source,"
                        + "catalog_fingerprint,payload_json,complete,total_matches,created_at,create_time,update_time) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                candidateSetId, conversationId, "tenant", "o".repeat(64), "office", "f".repeat(64),
                payload, 1, 0, "2026-01-01T00:00:00Z");
    }
}
