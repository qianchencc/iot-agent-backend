package ltd.qianc.iotagent.allowlist.internal;

import java.util.Map;
import ltd.qianc.iotagent.allowlist.PhoneAllowlist;
import ltd.qianc.iotagent.auth.PrincipalContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Only the current verified member's eligibility is exposed, never the phone list. */
@RestController
public final class CompanyTaskAccessController {
    private final PhoneAllowlist allowlist;

    public CompanyTaskAccessController(PhoneAllowlist allowlist) {
        this.allowlist = allowlist;
    }

    @GetMapping("/api/agent/company-task-access")
    public Map<String, Object> access() {
        return Map.of("enabled", allowlist.companyTasksEnabled(PrincipalContext.getPrincipalInfo()));
    }
}
