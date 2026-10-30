package ltd.qianc.iotagent.auth.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import ltd.qianc.iotagent.auth.AccountPrincipalVerifier;
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

    @Bean
    @ConditionalOnMissingBean(AccountPrincipalVerifier.class)
    AccountPrincipalVerifier accountPrincipalVerifier(
            WebClient.Builder builder,
            @Value("${iot.gateway.base-url:}") String baseUrl,
            @Value("${iot.identity.verify-timeout-ms:10000}") long timeoutMs) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return new FailClosedAccountPrincipalVerifier("物联身份核验上游未配置");
        }
        try {
            return new WebClientAccountPrincipalVerifier(
                    builder.build(), baseUrl, Duration.ofMillis(timeoutMs));
        } catch (RuntimeException invalidConfiguration) {
            return new FailClosedAccountPrincipalVerifier("物联身份核验配置无效");
        }
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
            BridgeAuthenticationFilter bridgeAuthenticationFilter) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .addFilterBefore(bridgeAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(agentIdentityFilter, UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/healthz").permitAll()
                        .anyRequest().permitAll());
        return http.build();
    }
}
