package com.ehe.iotagent.iot;

import java.util.Map;
import reactor.core.publisher.Mono;

/** Optional task-summary seam consumed by the local Company dashboard composition. */
@FunctionalInterface
public interface AutomationSummaryReader {
    Mono<Map<String, Object>> read(RequestIdentity identity);
}
