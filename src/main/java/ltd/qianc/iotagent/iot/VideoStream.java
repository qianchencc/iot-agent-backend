package ltd.qianc.iotagent.iot;

/** Short-lived stream entry returned by the authorized video gateway. */
public record VideoStream(String name, String url) {
    public VideoStream {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Video name is required");
        if (url == null || url.isBlank()) throw new IllegalArgumentException("Video URL is required");
    }
}
