package ltd.qianc.iotagent.iot;

import java.util.List;

/** A device identity returned by a server-authorized scope. Internal references are never HTTP views. */
public record AuthorizedDevice(
        String id,
        String kind,
        String name,
        String installAddress,
        boolean observationOnly,
        String controlRef,
        String officeDeviceRef,
        String hubDeviceId,
        String smartAreaCode,
        List<List<String>> locationPaths) {

    /** Compatibility constructor for existing callers and fixtures. */
    public AuthorizedDevice(String id, String kind, String name, String installAddress, boolean observationOnly) {
        this(id, kind, name, installAddress, observationOnly, null, null, null, null, List.of());
    }

    public AuthorizedDevice {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Device id is required");
        if (kind == null || kind.isBlank()) throw new IllegalArgumentException("Device kind is required");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Device name is required");
        installAddress = installAddress == null ? "" : installAddress;
        locationPaths = locationPaths == null ? List.of() : locationPaths.stream()
                .map(path -> List.copyOf(path == null ? List.of() : path)).toList();
    }
}
