package com.poliproger.endpointchecker;

import io.prometheus.client.Counter;
import io.prometheus.client.Gauge;

public final class Metrics {

    public static final Gauge ENDPOINT_UP = Gauge.build()
            .name("soap_endpoint_up")
            .help("1 if the last probe succeeded, 0 otherwise")
            .labelNames("name", "url")
            .register();

    public static final Gauge ENDPOINT_RESPONSE_SECONDS = Gauge.build()
            .name("soap_endpoint_response_seconds")
            .help("Response time of the last probe in seconds")
            .labelNames("name", "url")
            .register();

    public static final Gauge ENDPOINT_STATUS_CODE = Gauge.build()
            .name("soap_endpoint_status_code")
            .help("HTTP status code returned by the last probe")
            .labelNames("name", "url")
            .register();

    public static final Counter ENDPOINT_CHECKS_TOTAL = Counter.build()
            .name("soap_endpoint_checks_total")
            .help("Total number of probes performed")
            .labelNames("name", "url", "result")
            .register();

    private Metrics() {}
}
