package ltd.qianc.iotagent.iot.internal;

import java.time.Instant;
import java.util.List;
import ltd.qianc.iotagent.error.BusinessException;
import ltd.qianc.iotagent.iot.AuthorizedDeviceScope;
import ltd.qianc.iotagent.iot.EnergyHistory;
import ltd.qianc.iotagent.iot.ObservationRequest;
import ltd.qianc.iotagent.iot.ObservationResponse;
import ltd.qianc.iotagent.iot.RequestIdentity;

/** Storage disabled is an explicit unavailable boundary, never a transport fallback. */
final class UnavailableEnergyHistory implements EnergyHistory {
    @Override public AuthorizedDeviceScope authorizedScope(RequestIdentity identity) { throw unavailable(); }
    @Override public Window window(RequestIdentity identity, List<String> ids, Instant from, Instant to, boolean latest) { throw unavailable(); }
    @Override public ObservationResponse read(ObservationRequest request) { throw unavailable(); }
    private static BusinessException unavailable() {
        return new BusinessException("ENERGY_HISTORY_UNAVAILABLE", "本地计量历史存储未配置", 503);
    }
}
