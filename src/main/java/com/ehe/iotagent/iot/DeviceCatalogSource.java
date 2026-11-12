package com.ehe.iotagent.iot;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import com.ehe.iotagent.iot.internal.WebClientDeviceCatalog;
import org.springframework.web.reactive.function.client.WebClient;

/** Collector for company-wide display records, never an authorization or control scope. */
@FunctionalInterface
public interface DeviceCatalogSource {
    List<Entry> collect(RequestIdentity identity);

    record Entry(String id, String name, String kind, String location) {
        public Entry {
            if (id == null || id.isBlank() || name == null || name.isBlank() || kind == null || kind.isBlank())
                throw new IllegalArgumentException("Incomplete device catalog entry");
            location = location == null ? "" : location;
        }
        public Map<String, Object> publicView() {
            return Map.of("id", id, "name", name, "kind", kind, "location", location);
        }
    }

    static DeviceCatalogSource http(WebClient client, String path, Duration timeout) {
        return new WebClientDeviceCatalog(client, path, timeout);
    }
}
