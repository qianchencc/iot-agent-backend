package com.ehe.iotagent.admin;

import org.springframework.web.reactive.function.client.WebClient;
import com.ehe.iotagent.admin.internal.SupervisedAdminApplyCoordinator;

/** Construction entry point for explicitly supervised configuration apply. */
public final class AdminApplyCoordinatorFactory {
    private AdminApplyCoordinatorFactory() { }

    public static AdminApplyCoordinator supervised(AdminConfigurationStore store, AdminWorkState workState,
            WebClient supervisor, String supervisorToken) {
        return new SupervisedAdminApplyCoordinator(store, workState, supervisor, supervisorToken);
    }
}
