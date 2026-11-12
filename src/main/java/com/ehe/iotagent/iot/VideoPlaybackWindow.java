package com.ehe.iotagent.iot;

/** Controller-validated, timezone-bearing playback interval. */
public record VideoPlaybackWindow(String beginTime, String endTime) {
    public VideoPlaybackWindow {
        if (beginTime == null || beginTime.isBlank() || endTime == null || endTime.isBlank()) {
            throw new IllegalArgumentException("Playback window is required");
        }
    }
}
