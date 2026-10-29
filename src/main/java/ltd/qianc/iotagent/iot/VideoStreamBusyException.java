package ltd.qianc.iotagent.iot;

import ltd.qianc.iotagent.error.BusinessException;

/** Runtime-compatible upstream session/busy response (business code 207). */
public final class VideoStreamBusyException extends BusinessException {
    public VideoStreamBusyException() {
        super("VIDEO_STREAM_BUSY", "上游取流暂不可用，请稍后重试", "请稍后重试", 503);
    }
}
