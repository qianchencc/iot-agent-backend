package com.ehe.iotagent.admin.internal;

import java.util.Set;

/**
 * Deployment-owned knobs whose real consumers read Spring properties at process
 * construction. The admin store must not pretend a saved value can apply: saves
 * are rejected, views report the deployment property instead, and restores strip
 * them so historical revisions stay restorable.
 */
final class DeploymentReportedSettings {
    static final Set<String> NAMES = Set.of("draftTtlMs", "readbackWaitMs", "controlTimeoutMs");

    private DeploymentReportedSettings() {
    }
}
