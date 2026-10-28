package ltd.qianc.iotagent.iot;

/** Explicit state of a read; EMPTY and PARTIAL are not successful full coverage. */
public enum ObservationStatus {
    AVAILABLE,
    PARTIAL,
    EMPTY,
    UNAVAILABLE
}
