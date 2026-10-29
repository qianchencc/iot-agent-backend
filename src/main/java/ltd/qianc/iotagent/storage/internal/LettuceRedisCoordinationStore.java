package ltd.qianc.iotagent.storage.internal;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import ltd.qianc.iotagent.storage.RedisCoordinationStore;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
public final class LettuceRedisCoordinationStore implements RedisCoordinationStore {
    private static final DefaultRedisScript<Long> RELEASE_OWNER = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end", Long.class);
    private final StringRedisTemplate redis;
    public LettuceRedisCoordinationStore(StringRedisTemplate redis) { this.redis = redis; }
    @Override public Optional<String> get(String key) { return Optional.ofNullable(redis.opsForValue().get(key)); }
    @Override public void put(String key, String value, Duration ttl) { redis.opsForValue().set(key, value, ttl); }
    @Override public boolean delete(String key) { return Boolean.TRUE.equals(redis.delete(key)); }
    @Override public boolean acquireLock(String key, String owner, Duration lease) { return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent("lock:" + key, owner, lease)); }
    @Override public boolean releaseLock(String key, String owner) { Long deleted = redis.execute(RELEASE_OWNER, List.of("lock:" + key), owner); return Long.valueOf(1L).equals(deleted); }
    @Override public boolean markIdempotent(String key, Duration ttl) { return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent("idem:" + key, "1", ttl)); }
    @Override public boolean tryConsume(String key, long limit, Duration window) { Long count = redis.opsForValue().increment("rate:" + key); if (count != null && count == 1) redis.expire("rate:" + key, window); return count != null && count <= limit; }
}
