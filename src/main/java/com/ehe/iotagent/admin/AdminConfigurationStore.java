package com.ehe.iotagent.admin;

import java.util.List;
import java.util.Map;

/** Durable, encrypted configuration revision boundary. Implementations must never expose secret values in views. */
public interface AdminConfigurationStore extends AutoCloseable {
    ConfigurationView view(DeploymentInfo deployment);
    SaveResult save(long expectedRevision, Map<String, Object> changes, String actor);
    SaveResult restore(long expectedRevision, long revision, String actor);
    List<ChangeRecord> changes();
    Map<String, Object> saved();
    Map<String, Object> active();
    boolean pendingRestart();

    record DeploymentInfo(String host, int port, String version, String environment, String timezone) {
    }

    @Override
    default void close() {
    }

    record ChangeRecord(long revision, String at, String actor, List<String> changedFields) {
    }

    record SaveResult(int status, String code, List<String> issues) {
        public static SaveResult ok() {
            return new SaveResult(200, null, List.of());
        }
    }

    record ConfigurationView(long revision, long activeRevision, Map<String, Object> saved,
            Map<String, Object> active, Map<String, Map<String, Boolean>> secrets,
            boolean pendingRestart, List<String> changedFields, boolean applyEnabled,
            DeploymentInfo deployment, List<AdminSettingField> fields, Map<String, Object> deploymentSettings,
            Map<String, Object> runtime) {
        public ConfigurationView(long revision, long activeRevision, Map<String, Object> saved,
                Map<String, Object> active, Map<String, Map<String, Boolean>> secrets,
                boolean pendingRestart, List<String> changedFields, boolean applyEnabled,
                DeploymentInfo deployment) {
            this(revision, activeRevision, saved, active, secrets, pendingRestart, changedFields,
                    applyEnabled, deployment, AdminSettingCatalog.fields(), Map.of(), Map.of());
        }
    }
}
