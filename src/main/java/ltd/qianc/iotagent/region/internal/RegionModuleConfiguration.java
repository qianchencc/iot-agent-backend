package ltd.qianc.iotagent.region.internal;

import ltd.qianc.iotagent.region.RegionSeenStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Region persistence is composed inside its owning module, not through another module's internals. */
@Configuration(proxyBeanMethods = false)
public class RegionModuleConfiguration {
    @Bean
    @ConditionalOnProperty(name = "agent.storage.mysql.enabled", havingValue = "true")
    @ConditionalOnMissingBean(RegionSeenStore.class)
    RegionSeenStore jdbcRegionSeenStore(JdbcTemplate jdbc) {
        return new JdbcRegionSeenStore(jdbc);
    }
}
