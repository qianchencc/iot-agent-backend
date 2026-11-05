package ltd.qianc.iotagent.iot;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Explicit upstream paths; no guessed production endpoint is used by default. */
public record IotGatewayRoutes(
        String officeScopePath,
        String companyScopePath,
        String officeMembershipPath,
        List<OfficeDeviceSource> officeDeviceSources,
        String companyEnvironmentPath,
        String companyEnergyPath,
        String companySafetyPath,
        String companySafetyDetailPath,
        String companySmartBasePath,
        String companyAcAreaPath,
        String companyAcDevicePath,
        String companyAcBindingPath,
        String companyMonitorDownPath) {

    /** Compatibility constructor for existing route callers. */
    public IotGatewayRoutes(String officeScopePath, String companyScopePath, String officeMembershipPath,
                            List<OfficeDeviceSource> officeDeviceSources, String companyEnvironmentPath,
                            String companyEnergyPath, String companySafetyPath, String companySafetyDetailPath,
                            String companySmartBasePath, String companyAcAreaPath, String companyAcDevicePath,
                            String companyAcBindingPath) {
        this(officeScopePath, companyScopePath, officeMembershipPath, officeDeviceSources,
                companyEnvironmentPath, companyEnergyPath, companySafetyPath, companySafetyDetailPath,
                companySmartBasePath, companyAcAreaPath, companyAcDevicePath, companyAcBindingPath,
                "/micro-api/api/office/app/device/monitor/down");
    }

    public IotGatewayRoutes(String officeScopePath, String companyScopePath) {
        this(officeScopePath, companyScopePath, null, List.of(), null, null, null, null, null, null, null, null);
    }

    public IotGatewayRoutes(String officeScopePath, String companyScopePath, String membershipPath,
                            List<OfficeDeviceSource> sources) {
        this(officeScopePath, companyScopePath, membershipPath, sources, null, null, null, null, null, null, null, null);
    }
    public IotGatewayRoutes {
        officeScopePath = requirePath(officeScopePath, "officeScopePath");
        companyScopePath = optionalPath(companyScopePath, "companyScopePath");
        officeMembershipPath = optionalPath(officeMembershipPath, "officeMembershipPath");
        officeDeviceSources = List.copyOf(officeDeviceSources == null ? List.of() : officeDeviceSources);
        companyEnvironmentPath = optionalPath(companyEnvironmentPath, "companyEnvironmentPath");
        companyEnergyPath = optionalPath(companyEnergyPath, "companyEnergyPath");
        companySafetyPath = optionalPath(companySafetyPath, "companySafetyPath");
        companySafetyDetailPath = optionalPath(companySafetyDetailPath, "companySafetyDetailPath");
        companySmartBasePath = optionalPath(companySmartBasePath, "companySmartBasePath");
        companyAcAreaPath = optionalPath(companyAcAreaPath, "companyAcAreaPath");
        companyAcDevicePath = optionalPath(companyAcDevicePath, "companyAcDevicePath");
        companyAcBindingPath = optionalPath(companyAcBindingPath, "companyAcBindingPath");
        companyMonitorDownPath = optionalPath(companyMonitorDownPath, "companyMonitorDownPath");
    }

    public String path(IotSourceEnum source) {
        Objects.requireNonNull(source, "source");
        return source == IotSourceEnum.OFFICE ? officeScopePath : companyScopePath;
    }
    public boolean hasOfficeDirectoryRoutes() { return officeMembershipPath != null && !officeDeviceSources.isEmpty(); }
    public boolean hasCompanyCatalogRoutes() {
        return companyEnvironmentPath != null && companyEnergyPath != null && companySafetyPath != null
                && companySmartBasePath != null && companyAcAreaPath != null
                && companyAcDevicePath != null && companyAcBindingPath != null;
    }
    public static IotGatewayRoutes officeDirectory(String officeScopePath, String companyScopePath,
                                                    String membershipPath, List<OfficeDeviceSource> sources) {
        return new IotGatewayRoutes(officeScopePath, companyScopePath, membershipPath, sources);
    }
    public static IotGatewayRoutes runtimeCompany(String officeScopePath, String companyScopePath,
                                                   String environment, String energy, String safety,
                                                   String safetyDetail, String smartBase, String acArea,
                                                   String acDevice, String acBinding) {
        return new IotGatewayRoutes(officeScopePath, companyScopePath, null, List.of(), environment, energy,
                safety, safetyDetail, smartBase, acArea, acDevice, acBinding);
    }
    public static List<OfficeDeviceSource> runtimeOfficeSources(String acMyOffice, String lightingMyOffice,
            String accessMyOffice, String videoMyOffice, String acAssociation, String lightingAssociation,
            String accessAssociation) {
        return List.of(new OfficeDeviceSource("my-office.ac", "ac", acMyOffice, Map.of("type", "AirCold"), true),
                new OfficeDeviceSource("my-office.lighting", "lighting", lightingMyOffice, Map.of("type", "LIGHTING"), true),
                new OfficeDeviceSource("my-office.access", "access", accessMyOffice, Map.of("type", "SECURITY_PROTECTION", "subClass", "ACCESS"), true),
                new OfficeDeviceSource("my-office.video", "video", videoMyOffice, Map.of("type", "SECURITY_PROTECTION", "subClass", "VIDEO"), true),
                new OfficeDeviceSource("office-association.ac", "ac", acAssociation, Map.of(), false),
                new OfficeDeviceSource("office-association.lighting", "lighting", lightingAssociation, Map.of(), false),
                new OfficeDeviceSource("office-association.access", "access", accessAssociation, Map.of(), false));
    }
    private static String requirePath(String value, String name) {
        if (value == null || value.isBlank() || !value.startsWith("/")) throw new IllegalArgumentException(name + " must be an absolute path");
        return value;
    }
    private static String optionalPath(String value, String name) {
        return value == null || value.isBlank() ? null : requirePath(value, name);
    }
}
