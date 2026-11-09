package ltd.qianc.iotagent.iot.internal;

import java.time.Clock;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

import ltd.qianc.iotagent.auth.AccountPrincipalVerifier;
import ltd.qianc.iotagent.iot.DeviceDirectory;
import ltd.qianc.iotagent.readmodel.ReadSnapshots;
import ltd.qianc.iotagent.schema.SchemaMigrations;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "agent.storage.mysql.enabled", havingValue = "true")
@EnableConfigurationProperties({CompanyDirectoryConfiguration.Credentials.class, CompanyDirectoryConfiguration.Gateway.class})
public class CompanyDirectoryConfiguration {
    @ConfigurationProperties("agent.local-data")
    public record Credentials(String credentialKey) {
        @Override public String toString() { return "LocalDataCredentials[redacted]"; }
    }

    @ConfigurationProperties("iot.gateway")
    public record Gateway(String baseUrl) { }

    @Bean(initMethod = "start", destroyMethod = "close")
    @DependsOn("storageSchemaInitializer")
    @ConditionalOnMissingBean(DeviceDirectory.class)
    JdbcCompanyDirectory companyDirectory(DataSource dataSource, JdbcTemplate jdbc, TransactionTemplate transactions,
            ObjectMapper mapper, IotAdapterConfiguration.Source upstream, AccountPrincipalVerifier verifier,
            org.springframework.beans.factory.ObjectProvider<ObservationAdapterConfiguration.Source> sources,
            ReadSnapshots snapshots, ReadSnapshots.Barrier barrier, Credentials credentials, Gateway gateway) {
        JdbcCompanyDirectory directory = new JdbcCompanyDirectory(jdbc, transactions, mapper, upstream.adapter(),
                () -> sources.getObject().adapter(), verifier, snapshots, barrier,
                gateway.baseUrl(), credentials.credentialKey(), Clock.systemUTC());
        try {
            new SchemaMigrations(dataSource).ensureLocalCompanyDirectory();
            return directory;
        } catch (RuntimeException failure) {
            directory.close();
            throw failure;
        }
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnBean(JdbcCompanyDirectory.class)
    @ConditionalOnMissingBean(ltd.qianc.iotagent.iot.DeviceObservations.class)
    ltd.qianc.iotagent.iot.DeviceObservations deviceObservations(JdbcCompanyDirectory directory) {
        return directory.observations();
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnBean(JdbcCompanyDirectory.class)
    @ConditionalOnMissingBean(ltd.qianc.iotagent.iot.EnergyHistory.class)
    ltd.qianc.iotagent.iot.EnergyHistory energyHistory(JdbcCompanyDirectory directory) {
        return directory.energyHistory();
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnBean(JdbcCompanyDirectory.class)
    @ConditionalOnMissingBean(ltd.qianc.iotagent.iot.EnvironmentHistory.class)
    ltd.qianc.iotagent.iot.EnvironmentHistory environmentHistory(JdbcCompanyDirectory directory) {
        return directory.environmentHistory();
    }
}
