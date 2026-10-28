package ltd.qianc.iotagent.iot;

/** Read-only observation operations exposed by the Runtime-compatible Agent routes. */
public enum ObservationOperation {
    DASHBOARD,
    ENVIRONMENT_HISTORY,
    SAFETY_EVENTS,
    SAFETY_EVENT,
    SAFETY_EVENT_CAMERAS,
    REGION_LIST,
    REGION_SEEN,
    REGION_DEVICES,
    REGION_STATES,
    REGION_TIMELINE,
    REGION_CALENDAR,
    DEVICE_TIMELINE,
    DEVICE_CALENDAR,
    ENERGY_HISTORY,
    ENERGY_COMPARISON,
    ENERGY_REPORT,
    COMPANY_ENERGY_DAILY,
    COMPANY_ENERGY_REPORT,
    COMPANY_METER_REPORT,
    VIDEO_STREAM,
    REPORT,
    REPORT_DOWNLOAD
}
