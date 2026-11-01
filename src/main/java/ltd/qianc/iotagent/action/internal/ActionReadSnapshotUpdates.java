package ltd.qianc.iotagent.action.internal;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import ltd.qianc.iotagent.auth.PrincipalContext;
import ltd.qianc.iotagent.auth.PrincipalInfo;
import ltd.qianc.iotagent.readmodel.ReadSnapshots;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** A confirmed operation schedules new display facts without erasing the last visible result. */
@Configuration(proxyBeanMethods = false)
public class ActionReadSnapshotUpdates implements WebMvcConfigurer {
    private final ReadSnapshots snapshots;

    public ActionReadSnapshotUpdates(ReadSnapshots snapshots) {
        this.snapshots = snapshots;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                    Object handler, Exception failure) {
                if (!"POST".equals(request.getMethod()) || failure != null
                        || response.getStatus() >= 500 && response.getStatus() != 502
                        || response.getStatus() >= 400 && response.getStatus() < 500) return;
                PrincipalInfo principal = PrincipalContext.getPrincipalInfo();
                String source = "company".equals(request.getParameter("source")) ? "COMPANY" : "OFFICE";
                snapshots.invalidate(new ReadSnapshots.Identity(principal.tenantCode(), principal.authorization(),
                        source, principal.ownerKey()));
            }
        }).addPathPatterns("/api/agent/action-drafts/*/confirm");
    }
}
