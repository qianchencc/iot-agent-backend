package com.ehe.iotagent.iot.internal;

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

import com.ehe.iotagent.auth.AccountPrincipalVerifier;
import com.ehe.iotagent.iot.DeviceDirectory;
import com.ehe.iotagent.readmodel.ReadSnapshots;
import com.ehe.iotagent.schema.SchemaMigrations;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "agent.storage.mysql.enabled", havingValue = "true")
@EnableConfigurationProperties({CompanyDirectoryConfiguration.Credentials.class, CompanyDirectoryConfiguration.Gateway.class})
public class CompanyDirectoryConfiguration {
    @ConfigurationProperties("agent.local-data")
    public record Credentials(String credentialKey,
            @org.springframework.boot.context.properties.bind.DefaultValue("20") long safetySyncIntervalSeconds) {
        public Credentials {
            if (safetySyncIntervalSeconds <= 0)
                throw new IllegalArgumentException("agent.local-data.safety-sync-interval-seconds must be positive");
        }
        @Override public String toString() { return "LocalDataCredentials[redacted]"; }
    }

    @ConfigurationProperties("iot.gateway")
    public record Gateway(String baseUrl,
            @org.springframework.boot.context.properties.bind.DefaultValue("/micro-api/api/iot-center/device/list") String deviceCatalogPath,
            @org.springframework.boot.context.properties.bind.DefaultValue("30000") long callTimeoutMs) { }

    @Bean(initMethod = "start", destroyMethod = "close")
    @DependsOn("storageSchemaInitializer")
    @ConditionalOnMissingBean(DeviceDirectory.class)
    JdbcCompanyDirectory companyDirectory(DataSource dataSource, JdbcTemplate jdbc, TransactionTemplate transactions,
            ObjectMapper mapper, IotAdapterConfiguration.Source upstream, AccountPrincipalVerifier verifier,
            org.springframework.beans.factory.ObjectProvider<ObservationAdapterConfiguration.Source> sources,
            ReadSnapshots snapshots, ReadSnapshots.Barrier barrier, Credentials credentials, Gateway gateway,
            org.springframework.web.reactive.function.client.WebClient.Builder builder,
            org.springframework.core.env.Environment environment) {
        JdbcCompanyDirectory directory = new JdbcCompanyDirectory(jdbc, transactions, mapper, upstream.adapter(),
                () -> sources.getObject().adapter(), verifier, snapshots, barrier,
                gateway.baseUrl(), credentials.credentialKey(), Clock.systemUTC(),
                com.ehe.iotagent.iot.DeviceCatalogSource.http(builder.baseUrl(gateway.baseUrl()).build(),
                        gateway.deviceCatalogPath(), java.time.Duration.ofMillis(gateway.callTimeoutMs())),
                ObservationAdapterConfiguration.cursorMacKey(environment), credentials.safetySyncIntervalSeconds());
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
    @ConditionalOnMissingBean(com.ehe.iotagent.iot.DeviceObservations.class)
    com.ehe.iotagent.iot.DeviceObservations deviceObservations(JdbcCompanyDirectory directory) {
        return directory.observations();
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnBean(JdbcCompanyDirectory.class)
    @ConditionalOnMissingBean(com.ehe.iotagent.iot.EnergyHistory.class)
    com.ehe.iotagent.iot.EnergyHistory energyHistory(JdbcCompanyDirectory directory) {
        return directory.energyHistory();
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnBean(JdbcCompanyDirectory.class)
    @ConditionalOnMissingBean(com.ehe.iotagent.iot.EnvironmentHistory.class)
    com.ehe.iotagent.iot.EnvironmentHistory environmentHistory(JdbcCompanyDirectory directory) {
        return directory.environmentHistory();
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnBean(JdbcCompanyDirectory.class)
    @ConditionalOnMissingBean(com.ehe.iotagent.iot.SafetyHistory.class)
    com.ehe.iotagent.iot.SafetyHistory safetyHistory(JdbcCompanyDirectory directory) {
        return directory.safetyHistory();
    }
}
