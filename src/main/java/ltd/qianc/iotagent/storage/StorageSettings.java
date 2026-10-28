package ltd.qianc.iotagent.storage;

import java.util.Map;

public record StorageSettings(
        String mysqlUrl,
        String mysqlUsername,
        String mysqlPassword,
        String redisUrl) {

    public static StorageSettings required(Map<String, String> environment) {
        return new StorageSettings(
                required(environment, "AGENT_MYSQL_URL"),
                required(environment, "AGENT_MYSQL_USERNAME"),
                required(environment, "AGENT_MYSQL_PASSWORD"),
                required(environment, "AGENT_REDIS_URL"));
    }

    private static String required(Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) {
            throw new StorageConfigurationException("缺少存储配置：" + name);
        }
        return value;
    }
}
