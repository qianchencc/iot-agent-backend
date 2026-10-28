package ltd.qianc.iotagent;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeContractGateTest {

    @Test
    void authoritativeRuntimeContractContainsStreamingSurface() throws Exception {
        Path openapi = Path.of("..", "iot-agent-runtime", "docs", "api", "openapi.yaml");
        assertThat(Files.exists(openapi)).as("Runtime OpenAPI contract must be present").isTrue();
        String contract = Files.readString(openapi);
        assertThat(contract).contains("/healthz:");
        assertThat(contract).contains("/api/agent/conversations/{id}/messages:");
        assertThat(contract).contains("application/x-ndjson");
    }
}
