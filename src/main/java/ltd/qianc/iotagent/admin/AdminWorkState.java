package ltd.qianc.iotagent.admin;

/** Read-only guard consulted before a deployment supervisor may apply configuration. */
public interface AdminWorkState {
    boolean busy();

    String reason();
}
