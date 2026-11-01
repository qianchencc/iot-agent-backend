package ltd.qianc.iotagent.iot;

/** A real Office membership region from the current user's authorization. */
public record OfficeRegion(String id, String name) {
    public OfficeRegion {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("region id is required");
        if (name == null || name.isBlank()) name = "未命名区域";
    }
}
