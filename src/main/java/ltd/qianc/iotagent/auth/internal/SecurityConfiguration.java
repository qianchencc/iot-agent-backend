package ltd.qianc.iotagent.auth.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import ltd.qianc.iotagent.auth.AccountIdentityCachePolicy;
import ltd.qianc.iotagent.auth.AccountPrincipalVerifier;
import ltd.qianc.iotagent.readmodel.ReadSnapshots;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class SecurityConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(AccountPrincipalVerifier.class)
    AccountPrincipalVerifier accountPrincipalVerifier(
            WebClient.Builder builder,
            ReadSnapshots snapshots,
            org.springframework.beans.factory.ObjectProvider<ReadSnapshots.Persistence> persistence,
            org.springframework.beans.factory.ObjectProvider<ReadSnapshots.Barrier> barrier,
            ObjectMapper mapper,
            @Value("${iot.gateway.base-url:}") String baseUrl,
            @Value("${iot.identity.verify-timeout-ms:10000}") long timeoutMs,
            @Value("${iot.identity.refresh-interval-ms:60000}") long refreshIntervalMs,
            @Value("${iot.identity.max-validation-age-ms:86400000}") long maxValidationAgeMs,
            @Value("${iot.identity.retry-cooldown-ms:60000}") long retryCooldownMs) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return new FailClosedAccountPrincipalVerifier("物联身份核验上游未配置");
        }
        try {
            return AccountPrincipalVerifier.gateway(builder.build(), baseUrl,
                    new AccountIdentityCachePolicy(Duration.ofMillis(timeoutMs), Duration.ofMillis(refreshIntervalMs),
                            Duration.ofMillis(maxValidationAgeMs), Duration.ofMillis(retryCooldownMs)),
                    Clock.systemUTC(),
                    credential -> snapshots.discard(snapshotIdentity(credential)),
                    credential -> snapshots.admit(snapshotIdentity(credential)),
                    persistence.getIfAvailable(), barrier.getIfAvailable(), mapper);
        } catch (RuntimeException invalidConfiguration) {
            return new FailClosedAccountPrincipalVerifier("物联身份核验配置无效");
        }
    }

    private static ReadSnapshots.Identity snapshotIdentity(ltd.qianc.iotagent.auth.PrincipalInfo credential) {
        return new ReadSnapshots.Identity(credential.tenantCode(), credential.authorization(), "company", "");
    }

    @Bean
    AgentIdentityFilter agentIdentityFilter(
            ObjectMapper objectMapper,
            AccountPrincipalVerifier verifier,
            @Value("${iot.identity.verify:true}") boolean verifyIdentity) {
        return new AgentIdentityFilter(objectMapper, verifier, verifyIdentity);
    }

    @Bean
    BridgeAuthenticationFilter bridgeAuthenticationFilter(
            ObjectMapper objectMapper,
            @Value("${agent.bridge-token:}") String expectedToken) {
        return new BridgeAuthenticationFilter(objectMapper, expectedToken);
    }

    @Bean
    UserDetailsService noInteractiveUsers() {
        return username -> {
            throw new UsernameNotFoundException("交互式用户名密码登录未接入");
        };
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            AgentIdentityFilter agentIdentityFilter,
            BridgeAuthenticationFilter bridgeAuthenticationFilter,
            @Value("${agent.video.allowed-origin:}") String videoAllowedOrigin) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .addFilterBefore(bridgeAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(agentIdentityFilter, UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/healthz").permitAll()
                        .anyRequest().permitAll());
        // Scoped before the identity filter so a valid preflight is answered without
        // credentials while the actual GET still requires Authorization + Tenantcode.
        VideoStreamCorsFilter videoCors = VideoStreamCorsFilter.create(videoAllowedOrigin);
        if (videoCors != null) http.addFilterBefore(videoCors, AgentIdentityFilter.class);
        return http.build();
    }
}
