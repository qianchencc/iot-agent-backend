package ltd.qianc.iotagent.iot;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Explicit upstream paths; no guessed production endpoint is used by default. */
public record IotGatewayRoutes(
        String officeScopePath,
        String companyScopePath,
        String officeMembershipPath,
        List<OfficeDeviceSource> officeDeviceSources) {

    /** Compatibility constructor for existing scope callers and contract fixtures. */
    public IotGatewayRoutes(String officeScopePath, String companyScopePath) {
        this(officeScopePath, companyScopePath, null, List.of());
    }

    public IotGatewayRoutes {
        officeScopePath = requirePath(officeScopePath, "officeScopePath");
        companyScopePath = requirePath(companyScopePath, "companyScopePath");
        if (officeMembershipPath != null && !officeMembershipPath.isBlank()) {
            officeMembershipPath = requirePath(officeMembershipPath, "officeMembershipPath");
        } else {
            officeMembershipPath = null;
        }
        officeDeviceSources = List.copyOf(officeDeviceSources == null ? List.of() : officeDeviceSources);
    }

    public String path(IotSource source) {
        Objects.requireNonNull(source, "source");
        return source == IotSource.OFFICE ? officeScopePath : companyScopePath;
    }

    public boolean hasOfficeDirectoryRoutes() {
        return officeMembershipPath != null && !officeDeviceSources.isEmpty();
    }

    public static IotGatewayRoutes officeDirectory(
            String officeScopePath,
            String companyScopePath,
            String membershipPath,
            List<OfficeDeviceSource> sources) {
        return new IotGatewayRoutes(officeScopePath, companyScopePath, membershipPath, sources);
    }

    /** Runtime's verified read-only Office directory sources. Paths remain configurable. */
    public static List<OfficeDeviceSource> runtimeOfficeSources(
            String acMyOffice,
            String lightingMyOffice,
            String accessMyOffice,
            String videoMyOffice,
            String acAssociation,
            String lightingAssociation,
            String accessAssociation) {
        return List.of(
                new OfficeDeviceSource("my-office.ac", "ac", acMyOffice, Map.of("type", "AirCold"), true),
                new OfficeDeviceSource("my-office.lighting", "lighting", lightingMyOffice, Map.of("type", "LIGHTING"), true),
                new OfficeDeviceSource("my-office.access", "access", accessMyOffice, Map.of("type", "SECURITY_PROTECTION", "subClass", "ACCESS"), true),
                new OfficeDeviceSource("my-office.video", "video", videoMyOffice, Map.of("type", "SECURITY_PROTECTION", "subClass", "VIDEO"), true),
                new OfficeDeviceSource("office-association.ac", "ac", acAssociation, Map.of(), false),
                new OfficeDeviceSource("office-association.lighting", "lighting", lightingAssociation, Map.of(), false),
                new OfficeDeviceSource("office-association.access", "access", accessAssociation, Map.of(), false));
    }

    private static String requirePath(String value, String name) {
        if (value == null || value.isBlank() || !value.startsWith("/")) {
            throw new IllegalArgumentException(name + " must be an absolute path");
        }
        return value;
    }
}
