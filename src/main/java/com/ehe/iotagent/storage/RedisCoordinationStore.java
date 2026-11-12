package com.ehe.iotagent.storage;

import java.time.Duration;
import java.util.Optional;

/** Rebuildable runtime coordination; never the source of business facts. */
public interface RedisCoordinationStore {
    Optional<String> get(String key);
    void put(String key, String value, Duration ttl);
    boolean delete(String key);
    boolean acquireLock(String key, String owner, Duration lease);
    boolean releaseLock(String key, String owner);
    boolean markIdempotent(String key, Duration ttl);
    boolean tryConsume(String key, long limit, Duration window);
}
