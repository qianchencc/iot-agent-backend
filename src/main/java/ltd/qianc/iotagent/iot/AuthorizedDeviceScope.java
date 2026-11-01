package ltd.qianc.iotagent.iot;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Server-derived device authorization. Implementations MUST only contain records
 * returned by the current upstream identity and MUST retain the source boundary.
 */
public interface AuthorizedDeviceScope {

    RequestIdentity identity();

    IotSource source();

    List<AuthorizedDevice> devices();

    /** Non-secret fingerprint of the complete authorized catalog snapshot. */
    default String catalogFingerprint() {
        return fingerprint(devices().stream()
                .sorted(Comparator.comparing(AuthorizedDevice::id).thenComparing(AuthorizedDevice::kind))
                .map(device -> device.id() + ":" + device.catalogFingerprint())
                .toList());
    }

    /** Stronger non-secret fingerprint including current control bindings. */
    default String bindingFingerprint() {
        return fingerprint(devices().stream()
                .sorted(Comparator.comparing(AuthorizedDevice::id).thenComparing(AuthorizedDevice::kind))
                .map(device -> device.id() + ":" + device.bindingFingerprint())
                .toList());
    }

    private static String fingerprint(List<String> values) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(String.join("|", values).getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) result.append(String.format("%02x", item));
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    default Optional<AuthorizedDevice> device(String id) {
        return devices().stream().filter(device -> device.id().equals(id)).findFirst();
    }

    /** Narrows the server-verified scope without creating a second authorization convention. */
    default AuthorizedDeviceScope retain(java.util.function.Predicate<AuthorizedDevice> predicate) {
        return of(identity(), source(), devices().stream().filter(predicate).toList());
    }

    static AuthorizedDeviceScope of(RequestIdentity identity, IotSource source, List<AuthorizedDevice> devices) {
        return new ScopedAuthorizedDevices(identity, source, devices);
    }
}

record ScopedAuthorizedDevices(RequestIdentity identity, IotSource source, List<AuthorizedDevice> devices)
        implements AuthorizedDeviceScope {
    ScopedAuthorizedDevices {
        java.util.Objects.requireNonNull(identity, "identity");
        java.util.Objects.requireNonNull(source, "source");
        devices = List.copyOf(devices == null ? List.of() : devices);
    }
}
