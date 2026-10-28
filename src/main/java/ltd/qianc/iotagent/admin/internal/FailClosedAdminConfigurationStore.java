package ltd.qianc.iotagent.admin.internal;

import ltd.qianc.iotagent.admin.AdminConfigurationStore;
import ltd.qianc.iotagent.error.BusinessException;

import java.util.List;
import java.util.Map;

/** No in-memory production fallback: missing storage or key remains unavailable. */
public final class FailClosedAdminConfigurationStore implements AdminConfigurationStore {
    private static BusinessException unavailable() {
        return new BusinessException("ADMIN_CONFIG_UNAVAILABLE", "管理配置存储暂不可用，请稍后重试", 503);
    }

    @Override public ConfigurationView view(DeploymentInfo deployment) { throw unavailable(); }
    @Override public SaveResult save(long expectedRevision, Map<String, Object> changes, String actor) { throw unavailable(); }
    @Override public SaveResult restore(long expectedRevision, long revision, String actor) { throw unavailable(); }
    @Override public List<ChangeRecord> changes() { throw unavailable(); }
    @Override public Map<String, Object> saved() { throw unavailable(); }
    @Override public Map<String, Object> active() { throw unavailable(); }
    @Override public boolean pendingRestart() { throw unavailable(); }
}
