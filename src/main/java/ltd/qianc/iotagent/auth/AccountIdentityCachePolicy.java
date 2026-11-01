package ltd.qianc.iotagent.auth;

import java.time.Duration;

/** Fixed authentication freshness limits; an outage never extends the last successful validation. */
public record AccountIdentityCachePolicy(
        Duration timeout,
        Duration refreshInterval,
        Duration maxValidationAge,
        Duration retryCooldown) {
    public AccountIdentityCachePolicy {
        requirePositive(timeout, "timeout");
        requirePositive(refreshInterval, "refreshInterval");
        requirePositive(maxValidationAge, "maxValidationAge");
        requirePositive(retryCooldown, "retryCooldown");
        if (refreshInterval.compareTo(maxValidationAge) > 0) {
            throw new IllegalArgumentException("identity refresh interval exceeds maximum validation age");
        }
    }

    public static AccountIdentityCachePolicy defaults(Duration timeout) {
        return new AccountIdentityCachePolicy(timeout, Duration.ofSeconds(60), Duration.ofDays(1), Duration.ofSeconds(60));
    }

    private static void requirePositive(Duration duration, String name) {
        if (duration == null || duration.toMillis() <= 0) {
            throw new IllegalArgumentException("identity " + name + " must be at least one millisecond");
        }
    }
}
