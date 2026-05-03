package com.poliproger.endpointchecker;

import io.prometheus.client.Collector.MetricFamilySamples;
import io.prometheus.client.CollectorRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MetricsTest {

    @BeforeEach
    void resetMetrics() {
        Metrics.ENDPOINT_UP.clear();
        Metrics.ENDPOINT_RESPONSE_SECONDS.clear();
        Metrics.ENDPOINT_STATUS_CODE.clear();
        Metrics.ENDPOINT_CHECKS_TOTAL.clear();
    }

    private static MetricFamilySamples find(String name) {
        // Note: enumerator skips families without samples, so callers must populate
        // at least one labelled child before searching.
        for (var e = CollectorRegistry.defaultRegistry.metricFamilySamples(); e.hasMoreElements(); ) {
            MetricFamilySamples mfs = e.nextElement();
            if (mfs.name.equals(name)) return mfs;
        }
        return null;
    }

    @Test
    void allMetricsAreRegisteredInDefaultRegistry() {
        // Populate one child per metric so the family is emitted by the registry.
        Metrics.ENDPOINT_UP.labels("svc", "u").set(0);
        Metrics.ENDPOINT_RESPONSE_SECONDS.labels("svc", "u").set(0);
        Metrics.ENDPOINT_STATUS_CODE.labels("svc", "u").set(0);
        Metrics.ENDPOINT_CHECKS_TOTAL.labels("svc", "u", "success").inc(0);

        assertNotNull(find("soap_endpoint_up"));
        assertNotNull(find("soap_endpoint_response_seconds"));
        assertNotNull(find("soap_endpoint_status_code"));
        // Counter metric family is exposed under the user-supplied name (already ending in _total).
        assertNotNull(CollectorRegistry.defaultRegistry.getSampleValue(
                "soap_endpoint_checks_total",
                new String[]{"name", "url", "result"},
                new String[]{"svc", "u", "success"}));
    }

    @Test
    void gaugeIsLabelledByNameAndUrl() {
        Metrics.ENDPOINT_UP.labels("svc", "http://x/").set(1);

        Double v = CollectorRegistry.defaultRegistry.getSampleValue(
                "soap_endpoint_up",
                new String[]{"name", "url"},
                new String[]{"svc", "http://x/"});
        assertEquals(1.0, v);
    }

    @Test
    void counterCarriesResultLabelInAdditionToNameAndUrl() {
        Metrics.ENDPOINT_CHECKS_TOTAL.labels("svc", "http://x/", "success").inc();
        Metrics.ENDPOINT_CHECKS_TOTAL.labels("svc", "http://x/", "failure").inc(2);

        Map<String, Double> byResult = new HashMap<>();
        byResult.put("success", CollectorRegistry.defaultRegistry.getSampleValue(
                "soap_endpoint_checks_total",
                new String[]{"name", "url", "result"},
                new String[]{"svc", "http://x/", "success"}));
        byResult.put("failure", CollectorRegistry.defaultRegistry.getSampleValue(
                "soap_endpoint_checks_total",
                new String[]{"name", "url", "result"},
                new String[]{"svc", "http://x/", "failure"}));

        assertEquals(1.0, byResult.get("success"));
        assertEquals(2.0, byResult.get("failure"));
    }

    @Test
    void gaugesAreIndependentByLabels() {
        Metrics.ENDPOINT_STATUS_CODE.labels("a", "http://a/").set(200);
        Metrics.ENDPOINT_STATUS_CODE.labels("b", "http://b/").set(500);

        assertEquals(200.0, Metrics.ENDPOINT_STATUS_CODE.labels("a", "http://a/").get());
        assertEquals(500.0, Metrics.ENDPOINT_STATUS_CODE.labels("b", "http://b/").get());
    }
}
