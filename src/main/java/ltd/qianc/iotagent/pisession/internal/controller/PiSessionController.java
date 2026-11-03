package ltd.qianc.iotagent.pisession.internal.controller;

import java.util.Map;
import ltd.qianc.iotagent.pisession.PiSessionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/agent/sessions")
@ConditionalOnProperty(name="agent.storage.mysql.enabled",havingValue="true")
public final class PiSessionController {
    private final PiSessionService sessions;
    public PiSessionController(PiSessionService sessions){this.sessions=sessions;}
    @PostMapping public Map<String,Object> execute(@RequestBody Map<String,Object> request){return sessions.execute(request);}
}
