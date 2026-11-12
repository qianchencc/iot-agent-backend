package com.ehe.iotagent.iot;

import reactor.core.publisher.Mono;

/**
 * Read-only protocol boundary for dashboard, history, safety, energy, region,
 * video and report routes. Implementations MUST re-check the supplied identity
 * and source for every request; no result may be sourced from a local fallback.
 */
public interface ObservationAdapter {
    Mono<ObservationResponse> read(ObservationRequest request);

    /** Report downloads use the same authorization boundary as report reads. */
    default Mono<ReportDownload> download(ObservationRequest request) {
        return Mono.error(new ObservationUnavailableException("报告下载适配器未接入"));
    }
}
