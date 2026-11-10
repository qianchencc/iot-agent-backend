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
    public static String observations(String gatewayHash, String tenant, String source) {
        return hash("DEVICE_FACTS_V2\u0000" + gatewayHash + "\u0000" + tenant + "\u0000" + source);
    }
    public static String observation(AuthorizedDevice device) {
        return hash(device.kind() + "\u0000" + device.id() + "\u0000" + scalar(device.controlRef()) + "\u0000"
                + scalar(device.officeDeviceRef()) + "\u0000" + scalar(device.hubDeviceId()) + "\u0000"
                + scalar(device.smartAreaCode()) + "\u0000" + scalar(device.metadata().get("code")) + "\u0000"
                + scalar(device.metadata().get("companyAcDetailActionId")));
    }
    public static String observationProgress(String realm, String deviceKey) { return hash(realm + "\u0000" + deviceKey); }
    public static String environmentHistory(String realm, java.util.List<AuthorizedDevice> devices) {
        String members = devices.stream().filter(device -> "environment".equals(device.kind()))
                .map(LocalDataKeys::observation).sorted().collect(java.util.stream.Collectors.joining("\u0000"));
        return hash("ENVIRONMENT_FACTS_V2\u0000" + realm + "\u0000" + members);
    }
    private static String scalar(Object value) {
        return value == null ? "" : value instanceof com.fasterxml.jackson.databind.JsonNode node ? node.asText() : value.toString();
    }
    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
