package ltd.qianc.iotagent.pisession;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.zaxxer.hikari.HikariDataSource;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import ltd.qianc.iotagent.error.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Engineering-only public-SPI fixture. Never customer acceptance or a production identity route. */
public final class PiSessionConformanceServer {
    public static void main(String[] args) throws Exception {
        String url = required("AGENT_MYSQL_URL");
        URI database = URI.create(url.substring("jdbc:".length()));
        if (!url.startsWith("jdbc:mysql:") || !java.util.Set.of("127.0.0.1", "localhost").contains(database.getHost())
                || !database.getPath().matches("/[^/]*(contract|test)[^/]*")) {
            throw new IllegalArgumentException("Session contract fixture requires an isolated local contract/test MySQL schema");
        }
        HikariDataSource source = new HikariDataSource();
        source.setJdbcUrl(url); source.setUsername(required("AGENT_MYSQL_USERNAME"));
        source.setPassword(required("AGENT_MYSQL_PASSWORD")); source.setMaximumPoolSize(4);
        ObjectMapper mapper = new ObjectMapper();
        ThreadLocal<String> namespace = new ThreadLocal<>();
        PiSessionService sessions = PiSessionServiceFactory.jdbc(new JdbcTemplate(source),
                new TransactionTemplate(new DataSourceTransactionManager(source)), mapper,
                r -> new PiSessionService.Context("contract:"+namespace.get(), "fixture-owner", "office",
                        (String)r.get("conversationId"), (String)r.get("runId")), Clock.systemUTC());
        String token = System.getenv().getOrDefault("JAVA_SESSION_TEST_TOKEN", "test-java");
        int port = Integer.parseInt(System.getenv().getOrDefault("JAVA_SESSION_TEST_PORT", "3297"));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
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
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { server.stop(0); source.close(); }));
        server.start(); System.out.println("Pi Session MySQL engineering fixture ready on "+port);
        new CountDownLatch(1).await();
    }
    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(key+" is required");
        return value;
    }
}
