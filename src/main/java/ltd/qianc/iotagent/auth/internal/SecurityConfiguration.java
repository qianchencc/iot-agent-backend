package ltd.qianc.iotagent.auth.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfiguration {

    @Bean
    AgentIdentityFilter agentIdentityFilter(ObjectMapper objectMapper) {
        return new AgentIdentityFilter(objectMapper);
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
