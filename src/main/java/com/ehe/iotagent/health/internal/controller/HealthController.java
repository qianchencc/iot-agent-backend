package com.ehe.iotagent.health.internal.controller;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public liveness summary; it intentionally contains no URLs, credentials, or internal stack details. */
@RestController
public class HealthController {
    private final Environment environment;
    private final ObjectProvider<JdbcTemplate> jdbcTemplates;
    private final ObjectProvider<StringRedisTemplate> redisTemplates;

    public HealthController(Environment environment, ObjectProvider<JdbcTemplate> jdbcTemplates,
            ObjectProvider<StringRedisTemplate> redisTemplates) {
        this.environment = environment;
        this.jdbcTemplates = jdbcTemplates;
        this.redisTemplates = redisTemplates;
    }

    @GetMapping("/healthz")
    public Map<String, Object> healthz() {
        Map<String, Object> checks = new LinkedHashMap<>();
        checks.put("java", Map.of("status", "ok"));
        checks.put("nodeBridge", nodeBridge());
        checks.put("mysql", mysql());
        checks.put("redis", redis());
        checks.put("piSession", Map.of("status", "boundary", "owner", "node-sidecar"));
        return Map.of("service", "iot-agent-backend", "status", "ok", "version", "0.1.0", "checks", checks);
    }

    private Map<String, String> nodeBridge() {
        String baseUrl = environment.getProperty("agent.bridge.base-url", "");
        String token = environment.getProperty("agent.bridge.token", "");
        if (baseUrl.isBlank() || token.isBlank()) return Map.of("status", "not_configured");
        return Map.of("status", "configured");
    }

    private Map<String, String> mysql() {
        if (!Boolean.parseBoolean(environment.getProperty("agent.storage.mysql.enabled", "false"))) {
            return Map.of("status", "disabled");
        }
        JdbcTemplate jdbc = jdbcTemplates.getIfAvailable();
        if (jdbc == null) return Map.of("status", "unavailable");
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            return Map.of("status", "ok");
        } catch (RuntimeException ignored) {
            return Map.of("status", "unavailable");
        }
    }

    private Map<String, String> redis() {
        if (!Boolean.parseBoolean(environment.getProperty("agent.storage.redis.enabled", "false"))) {
            return Map.of("status", "disabled");
        }
        StringRedisTemplate redis = redisTemplates.getIfAvailable();
        if (redis == null || redis.getConnectionFactory() == null) return Map.of("status", "unavailable");
        try (var connection = redis.getConnectionFactory().getConnection()) {
            connection.ping();
            return Map.of("status", "ok");
        } catch (RuntimeException ignored) {
            return Map.of("status", "unavailable");
        } catch (Exception ignored) {
            return Map.of("status", "unavailable");
        }
    }
}
