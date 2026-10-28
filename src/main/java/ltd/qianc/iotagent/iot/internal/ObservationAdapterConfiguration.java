package ltd.qianc.iotagent.iot.internal;

import ltd.qianc.iotagent.iot.ObservationAdapter;
import ltd.qianc.iotagent.iot.ObservationAdapterFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The customer observation paths and schemas are not present in the Java source
 * contract yet. Keep all routes visible but fail closed until an explicit adapter
 * is supplied (including in-process production-protocol fixtures).
 */
@Configuration(proxyBeanMethods = false)
public class ObservationAdapterConfiguration {
    @Bean
    @ConditionalOnMissingBean(ObservationAdapter.class)
    ObservationAdapter observationAdapter() {
        return ObservationAdapterFactory.failClosed("客户只读观察协议未配置");
    }
}
