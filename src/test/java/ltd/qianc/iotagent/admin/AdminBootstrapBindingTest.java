package ltd.qianc.iotagent.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import ltd.qianc.iotagent.error.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

class AdminBootstrapBindingTest {
    @TempDir Path directory;

    private WebApplicationContextRunner application(Path file) {
        return new WebApplicationContextRunner().withUserConfiguration(AdminModule.class)
                .withPropertyValues("agent.storage.mysql.enabled=false", "agent.storage.redis.enabled=false",
                        "admin.config-file=" + file,
                        "admin.config-secret-key=" + "01".repeat(32));
    }

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    @org.springframework.context.annotation.ComponentScan("ltd.qianc.iotagent.admin.internal")
    static class AdminModule {
        @org.springframework.context.annotation.Bean
        com.fasterxml.jackson.databind.ObjectMapper objectMapper() {
            return new com.fasterxml.jackson.databind.ObjectMapper();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"admin.initial.scan-interval-ms=not-an-integer", "admin.initial.reasoning=tru"})
    void invalidFirstBootSeedCannotSilentlyBecomeDefaults(String property) {
        Path file = directory.resolve("invalid.bin");
        application(file).withPropertyValues(property).run(context -> {
            assertThat(context).hasNotFailed();
            assertThatThrownBy(() -> context.getBean(AdminConfigurationStore.class).saved())
                    .isInstanceOf(BusinessException.class);
            assertThat(file).doesNotExist();
        });
    }

    @Test
    void existingRevisionIgnoresInvalidUnusedSeedOnRestart() {
        Path file = directory.resolve("existing.bin");
        application(file).withPropertyValues("admin.initial.scan-interval-ms=2345").run(context ->
                assertThat(context.getBean(AdminConfigurationStore.class).saved())
                        .containsEntry("scanIntervalMs", 2345));
        application(file).withPropertyValues("admin.initial.scan-interval-ms=invalid",
                "admin.initial.reasoning=tru").run(context ->
                assertThat(context.getBean(AdminConfigurationStore.class).saved())
                        .containsEntry("scanIntervalMs", 2345).containsEntry("reasoning", true));
    }
}
