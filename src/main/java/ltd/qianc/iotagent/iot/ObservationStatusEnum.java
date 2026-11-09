package ltd.qianc.iotagent.iot;

/** Explicit state of a read; EMPTY and PARTIAL are not successful full coverage. */
public enum ObservationStatusEnum {
    AVAILABLE,
    PARTIAL,
    EMPTY,
    UNAVAILABLE;

    @com.fasterxml.jackson.annotation.JsonValue
    public String wireValue() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
