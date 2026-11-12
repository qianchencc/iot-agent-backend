package com.ehe.iotagent.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import com.ehe.iotagent.iot.AutomationSummaryReader;
import com.ehe.iotagent.iot.AuthorizedDevice;
import com.ehe.iotagent.iot.AuthorizedDeviceScope;
import com.ehe.iotagent.iot.IotAdapter;
import com.ehe.iotagent.iot.IotSourceEnum;
import com.ehe.iotagent.iot.RequestIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** A cold dashboard summary must work even when its subscription arrives on Reactor I/O. */
@SpringBootTest(properties = {"agent.storage.mysql.enabled=false", "agent.storage.redis.enabled=false",
        "iot.identity.verify=false", "agent.heartbeat-ms=0", "agent.task.scanner.poll-ms=3600000"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AutomationSummaryReaderContractTest {
    @MockitoBean private IotAdapter iot;
    @MockitoBean private TaskProjectionStore database;
    @Autowired private TaskFacade tasks;
    @Autowired private AutomationSummaryReader reader;

    @Test
    void coldSummaryComposesAuthorizationAndLocalResultsOffTheNonblockingSubscriber() {
        // Defer exercises local composition off the nonblocking subscription thread.
        when(iot.readDirectory(any())).thenAnswer(invocation -> {
            RequestIdentity identity = invocation.getArgument(0);
            return Mono.defer(() -> Mono.just(new com.ehe.iotagent.iot.DeviceDirectoryBatch(
                    AuthorizedDeviceScope.of(identity, IotSourceEnum.COMPANY,
                            List.of(new AuthorizedDevice("light-1", "lighting", "灯", "26楼", false))), List.of())));
        });
        // Substitute only the database port; authorization, snapshots and runtime composition are real.
        when(database.listByIdentity(any(), isNull(), isNull())).thenAnswer(invocation -> {
            tasks.loadScope(invocation.getArgument(0));
            return List.of(Map.of("id", "running", "status", "EXECUTING"),
                    Map.of("id", "next", "status", "SCHEDULED"),
                    Map.of("id", "finished", "status", "COMPLETED", "reportId", "report-1"));
        });
        Map<String, Object> result = reader.read(new RequestIdentity(
                        "Bearer summary-reader", "TENANT", IotSourceEnum.COMPANY))
                .subscribeOn(Schedulers.parallel()).block(Duration.ofSeconds(5));

        assertThat(result).containsEntry("active", 1L);
        assertThat(result.get("upcoming")).asList().containsExactly(Map.of("id", "next", "status", "SCHEDULED"));
        assertThat(result.get("latestResults")).asList()
                .containsExactly(Map.of("id", "finished", "status", "COMPLETED", "reportId", "report-1"));
    }
}
