package ltd.qianc.iotagent.health.internal.controller;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    @GetMapping("/healthz")
    public Map<String, String> healthz() {
        return Map.of("service", "iot-agent-backend", "status", "ok", "version", "0.1.0");
    }
}
