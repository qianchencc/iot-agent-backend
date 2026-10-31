package ltd.qianc.iotagent.iot;

/** Authorized playback card prepared without issuing or persisting a stream URL. */
public record VideoPlaybackPreparation(
        String source,
        String deviceId,
        String name,
        String location,
        String beginTime,
        String endTime) {
}
