package com.ehe.iotagent.iot;

import java.util.Arrays;
import java.util.Objects;

/** Binary report seam; bytes are returned only after current-scope authorization. */
public record ReportDownload(String contentType, String filename, byte[] content) {
    public ReportDownload {
        if (contentType == null || contentType.isBlank()) throw new IllegalArgumentException("contentType is required");
        if (filename == null || filename.isBlank()) throw new IllegalArgumentException("filename is required");
        if (filename.indexOf('\r') >= 0 || filename.indexOf('\n') >= 0 || filename.indexOf('"') >= 0) {
            throw new IllegalArgumentException("filename contains header characters");
        }
        Objects.requireNonNull(content, "content");
        content = Arrays.copyOf(content, content.length);
    }

    @Override
    public byte[] content() {
        return Arrays.copyOf(content, content.length);
    }
}
