package ltd.qianc.iotagent.iot;

/** A device identity returned by a server-authorized scope. */
public record AuthorizedDevice(
        String id,
        String kind,
        String name,
        String installAddress,
        boolean observationOnly) {

    public AuthorizedDevice {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Device id is required");
        }
        if (kind == null || kind.isBlank()) {
            throw new IllegalArgumentException("Device kind is required");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Device name is required");
        }
        installAddress = installAddress == null ? "" : installAddress;
    }
}
