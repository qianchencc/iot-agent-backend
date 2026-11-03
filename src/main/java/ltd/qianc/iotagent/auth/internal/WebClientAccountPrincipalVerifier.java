package ltd.qianc.iotagent.auth.internal;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import ltd.qianc.iotagent.auth.AccountPrincipalVerifier;
import ltd.qianc.iotagent.auth.PrincipalInfo;
import ltd.qianc.iotagent.error.BusinessException;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;

/** Ports Runtime's GET /sso-api/account/me verification without creating a second login system. */
public final class WebClientAccountPrincipalVerifier implements AccountPrincipalVerifier {
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(WebClientAccountPrincipalVerifier.class);
    private final WebClient client;
    private final URI identityUri;
    private final Duration timeout;
    private final String identityRealm;

    public WebClientAccountPrincipalVerifier(WebClient client, String baseUrl, Duration timeout) {
        this.client = Objects.requireNonNull(client, "client");
        this.identityUri = URI.create(Objects.requireNonNull(baseUrl, "baseUrl").replaceAll("/+$", "") + "/sso-api/account/me");
        this.timeout = requireTimeout(timeout);
        this.identityRealm = URI.create(baseUrl).resolve("/").toString().replaceAll("/+$", "");
    }

    @Override
    public PrincipalInfo verify(PrincipalInfo presented) {
        Objects.requireNonNull(presented, "presented");
        try {
            PrincipalInfo verified = client.get()
                    .uri(identityUri)
                    .headers(headers -> {
                        headers.set("Authorization", presented.authorization());
                        headers.set("Tenantcode", presented.tenantCode());
                    })
                    .exchangeToMono(response -> decode(response, presented))
                    .timeout(timeout)
                    .block(timeout.plusMillis(250));
            if (verified == null) throw unavailable();
            return verified;
        } catch (BusinessException error) {
            throw error;
        } catch (RuntimeException error) {
            log.warn("身份核验调用失败 type={} cause={}", error.getClass().getName(),
                    error.getCause() == null ? "-" : error.getCause().getClass().getName());
            throw unavailable();
        }
    }

    private reactor.core.publisher.Mono<PrincipalInfo> decode(ClientResponse response, PrincipalInfo presented) {
        HttpStatusCode status = response.statusCode();
        if (status.value() == 401 || status.value() == 403) {
            return response.releaseBody().then(reactor.core.publisher.Mono.error(
                    new BusinessException("UNAUTHORIZED", "身份凭据无法核验", "请重新登录", 401)));
        }
        if (!status.is2xxSuccessful()) {
            return response.releaseBody().then(reactor.core.publisher.Mono.error(unavailable()));
        }
        return response.bodyToMono(JsonNode.class)
                .switchIfEmpty(reactor.core.publisher.Mono.error(unavailable()))
                .flatMap(body -> {
                    int code = body.path("code").asInt(-1);
                    if (code == 401 || code == 403) {
                        return reactor.core.publisher.Mono.error(
                                new BusinessException("UNAUTHORIZED", "身份凭据无法核验", "请重新登录", 401));
                    }
                    if (code != 0 && code != 200) return reactor.core.publisher.Mono.error(unavailable());
                    String principalId = body.path("content").path("id").asText("").trim();
                    if (principalId.isEmpty()) return reactor.core.publisher.Mono.error(unavailable());
                    return reactor.core.publisher.Mono.just(new PrincipalInfo(
                            presented.authorization(), presented.tenantCode(), principalId, identityRealm));
                });
    }

    private static BusinessException unavailable() {
        return new BusinessException("ACCOUNT_IDENTITY_UNAVAILABLE",
                "当前账户身份暂不可用，请稍后重试", "请稍后重试", 503);
    }

    private static Duration requireTimeout(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("identity verification timeout must be positive");
        }
        return timeout;
    }
}
