package ltd.qianc.iotagent.iot.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import ltd.qianc.iotagent.iot.AuthorizedDevice;
import ltd.qianc.iotagent.iot.RequestIdentity;

/** Stable source identity; credential generations are publication guards, never object keys. */
public final class LocalDataKeys {
    private LocalDataKeys() { }
    public static String directory(String gatewayHash, RequestIdentity identity) {
        return hash(gatewayHash + "\u0000" + identity.tenantCode() + "\u0000" + identity.ownerKey() + "\u0000"
                + identity.source().wireValue() + "\u0000" + identity.source().name() + "_DIRECTORY");
    }
    public static String device(AuthorizedDevice device) { return hash(device.kind() + "\u0000" + device.id()); }
    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
