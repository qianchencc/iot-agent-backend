package ltd.qianc.iotagent.pisession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ltd.qianc.iotagent.error.BusinessException;

class PiSessionContractTest {
    @Test
    void acknowledgedAppendSurvivesReopenAndDuplicateCommitDoesNotDuplicateHistory() {
        JdbcTemplate jdbc = database();
        PiSessionService service = service(jdbc);
        Map<String,Object> claim = service.execute(request("claim", "writer-1", 0, 0));
        long epoch = ((Number) claim.get("epoch")).longValue();
        Map<String,Object> write = request("write", "writer-1", epoch, 0);
        write.put("key", "/sessions/history"); write.put("content", "header\n"); write.put("commitId", "init");
        service.execute(write);
        Map<String,Object> append = request("append", "writer-1", epoch, 1);
        append.put("key", "/sessions/history"); append.put("content", "tool-result\n"); append.put("commitId", "append-1");
        service.execute(append); service.execute(append);
        PiSessionService reopened = service(jdbc);
        Map<String,Object> read = request("read", "writer-1", epoch, 2); read.put("key", "/sessions/history");
        assertThat(reopened.execute(read).get("value")).isEqualTo("header\ntool-result\n");
        append.put("content", "different-result\n");
        assertThatThrownBy(() -> reopened.execute(append)).isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException)e).code()).isEqualTo("PI_SESSION_COMMIT_CONFLICT");
    }

    @Test
    void anotherWriterCannotEnterUntilReleaseAndOldWriterCannotAppendAfterTakeover() {
        PiSessionService service = service(database());
        Map<String,Object> first = service.execute(request("claim", "writer-1", 0, 0));
        long epoch = ((Number)first.get("epoch")).longValue();
        assertThatThrownBy(() -> service.execute(request("claim", "writer-2", 0, 0)))
                .isInstanceOf(BusinessException.class).extracting(e -> ((BusinessException)e).code())
                .isEqualTo("PI_SESSION_BUSY");
        service.execute(request("release", "writer-1", epoch, 0));
        Map<String,Object> next = service.execute(request("claim", "writer-2", 0, 0));
        assertThat(((Number)next.get("epoch")).longValue()).isGreaterThan(epoch);
        Map<String,Object> oldWrite = request("write", "writer-1", epoch, 0);
        oldWrite.put("key", "/sessions/history"); oldWrite.put("content", "stale"); oldWrite.put("commitId", "stale");
        assertThatThrownBy(() -> service.execute(oldWrite)).isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException)e).code()).isEqualTo("PI_SESSION_WRITER_LOST");
    }

    @Test
    void missingJournalChunkFailsInsteadOfRepeatingAnotherChunk() {
        JdbcTemplate jdbc = database();
        PiSessionService service = service(jdbc);
        long epoch = ((Number)service.execute(request("claim", "writer-1", 0, 0)).get("epoch")).longValue();
        Map<String,Object> write = request("write", "writer-1", epoch, 0);
        write.put("key", "/sessions/history"); write.put("content", "header\n"); write.put("commitId", "header");
        service.execute(write);
        Map<String,Object> append = request("append", "writer-1", epoch, 1);
        append.put("key", "/sessions/history"); append.put("content", "result\n"); append.put("commitId", "result");
        service.execute(append);
        jdbc.update("DELETE FROM pi_session_chunks WHERE chunk_no=0");
        Map<String,Object> read = request("read", "writer-1", epoch, 2); read.put("key", "/sessions/history");
        assertThatThrownBy(() -> service.execute(read)).isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException)e).code()).isEqualTo("PI_SESSION_CORRUPT");
        assertThat(service.health()).containsEntry("status", "unavailable");
    }

    private static Map<String,Object> request(String operation, String writer, long epoch, long version) {
        Map<String,Object> r = new LinkedHashMap<>();
        r.put("protocolVersion", 1); r.put("operation", operation); r.put("writerId", writer);
        r.put("runId", "run-1"); r.put("conversationId", "conversation-1"); r.put("turnId", "turn-1"); r.put("toolContextId", "context-1");
        r.put("epoch", epoch); r.put("expectedVersion", version);
        return r;
    }
    private static PiSessionService service(JdbcTemplate jdbc) {
        return PiSessionServiceFactory.jdbc(jdbc, new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())),
                new ObjectMapper(), r -> new PiSessionService.Context("tenant", "owner", "office", "conversation-1", "run-1"), Clock.systemUTC());
    }
    private static JdbcTemplate database() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:pi_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        return new JdbcTemplate(source);
    }
}
