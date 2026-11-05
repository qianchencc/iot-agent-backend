package ltd.qianc.iotagent.pisession;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.zaxxer.hikari.HikariDataSource;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.time.Clock;
import ltd.qianc.iotagent.error.BusinessException;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.env.Environment;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Engineering-only public-SPI fixture. Never customer acceptance or a production identity route.
 * Reads standard Spring config data, so it starts exactly like the backend:
 * {@code java -cp ... PiSessionConformanceServer --spring.config.additional-location=file:/private/fixture-application.yaml}
 * with canonical spring.datasource.url/username/password, server.port and agent.bridge.token.
 * Canonical Spring properties only; engineering defaults keep bare runs isolated.
 */
public final class PiSessionConformanceServer {
    public static void main(String[] args) throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new SimpleCommandLinePropertySource(args));
        // Never inherit the backend's packaged application.yml here (its server.port
        // default is the real backend port 3381); this fixture owns its own defaults.
        environment.getSystemProperties().putIfAbsent("spring.config.name", "pi-session-conformance");
        ConfigDataEnvironmentPostProcessor.applyTo(environment);
        String url = required(environment, "spring.datasource.url");
        URI database = URI.create(url.substring("jdbc:".length()));
        if (!url.startsWith("jdbc:mysql:") || !java.util.Set.of("127.0.0.1", "localhost").contains(database.getHost())
                || !database.getPath().matches("/[^/]*(contract|test)[^/]*")) {
            throw new IllegalArgumentException("Session contract fixture requires an isolated local contract/test MySQL schema");
        }
        HikariDataSource source = new HikariDataSource();
        source.setJdbcUrl(url);
        source.setUsername(required(environment, "spring.datasource.username"));
        source.setPassword(required(environment, "spring.datasource.password"));
        source.setMaximumPoolSize(4);
        ObjectMapper mapper = new ObjectMapper();
        ThreadLocal<String> namespace = new ThreadLocal<>();
        PiSessionService sessions = PiSessionServiceFactory.jdbc(new JdbcTemplate(source),
                new TransactionTemplate(new DataSourceTransactionManager(source)), mapper,
                r -> new PiSessionService.Context("contract:"+namespace.get(), "fixture-owner", "office",
                        (String)r.get("conversationId"), (String)r.get("runId")), Clock.systemUTC());
        String token = environment.getProperty("agent.bridge.token", "test-java");
        int port = environment.getProperty("server.port", Integer.class, 3297);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        ThreadPoolExecutor executor = new ThreadPoolExecutor(4, 4, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(64), Thread.ofPlatform().daemon(true).name("pi-contract-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
        server.setExecutor(executor);
        server.createContext("/internal/agent/sessions", exchange -> {
            try {
                if (!"POST".equals(exchange.getRequestMethod()) || !token.equals(exchange.getRequestHeaders().getFirst("X-Agent-Bridge-Token"))) {
                    exchange.sendResponseHeaders(401, -1); return;
                }
                String query = exchange.getRequestURI().getRawQuery();
                if (query == null || !query.matches("case=[A-Za-z0-9-]{1,80}")) {
                    exchange.sendResponseHeaders(400, -1); return;
                }
                namespace.set(query.substring(5));
                Map<String,Object> request = mapper.readValue(exchange.getRequestBody(), new TypeReference<>() {});
                byte[] result = mapper.writeValueAsBytes(sessions.execute(request));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, result.length); exchange.getResponseBody().write(result);
            } catch (BusinessException error) {
                byte[] body = mapper.writeValueAsBytes(Map.of("code", error.code(), "message", error.getMessage()));
                exchange.sendResponseHeaders(error.httpStatus(), body.length); exchange.getResponseBody().write(body);
            } catch (Exception error) {
                System.err.println("Contract fixture failure: "+error.getClass().getSimpleName());
                exchange.sendResponseHeaders(500, -1);
            } finally { namespace.remove(); exchange.close(); }
        });
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { server.stop(0); executor.shutdownNow(); source.close(); }));
        server.start(); System.out.println("Pi Session MySQL engineering fixture ready on "+port);
        new CountDownLatch(1).await();
    }

    private static String required(Environment environment, String property) {
        String value = environment.getProperty(property);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(property + " is required");
        return value;
    }
}
