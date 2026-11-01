package ltd.qianc.iotagent.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import ltd.qianc.iotagent.action.internal.WebClientActionCommandGateway;
import ltd.qianc.iotagent.iot.IotAdapter;
import org.springframework.web.reactive.function.client.WebClient;

/** Public construction seam for the customer control adapter; protocol code remains internal. */
public final class ActionCommandGatewayFactory {
    private ActionCommandGatewayFactory() {}

    public static ActionCommandGateway webClient(ObjectMapper mapper, WebClient client, IotAdapter iot,
                                                   Duration timeout, String lightingPath, String accessPath, String acPath) {
        return webClient(mapper, client, iot, timeout, lightingPath, accessPath, acPath, Duration.ofSeconds(6));
    }

    public static ActionCommandGateway webClient(ObjectMapper mapper, WebClient client, IotAdapter iot,
                                                   Duration timeout, String lightingPath, String accessPath, String acPath,
                                                   Duration readbackWait) {
        return new WebClientActionCommandGateway(mapper, client, iot, timeout, lightingPath, accessPath, acPath, readbackWait);
    }
}
