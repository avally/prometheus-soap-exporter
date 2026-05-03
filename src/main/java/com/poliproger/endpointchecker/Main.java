package com.poliproger.endpointchecker;

import io.prometheus.client.exporter.HTTPServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws Exception {
        String configPath = System.getenv().getOrDefault("ENDPOINTS_CONFIG", "/app/endpoints.yml");
        int metricsPort  = Integer.parseInt(System.getenv().getOrDefault("METRICS_PORT", "9116"));

        log.info("Loading config from {}", configPath);
        AppConfig config = AppConfig.load(configPath);

        log.info("Starting metrics server on :{}", metricsPort);
        HTTPServer.Builder builder = new HTTPServer.Builder().withPort(metricsPort);
        HTTPServer server = builder.build(); // runs in daemon thread

        if (config.endpoints.isEmpty()) {
            log.warn("No endpoints configured — exporter will serve empty metrics");
        }

        int poolSize = Math.max(2, config.endpoints.size());
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(poolSize);
        Prober prober = new Prober();

        for (AppConfig.Endpoint ep : config.endpoints) {
            int interval = ep.interval();
            // First probe fires immediately (delay = 0), then repeats every `interval` seconds.
            // scheduleAtFixedRate is intentional: if a probe takes longer than the interval
            // the next one starts right after instead of stacking up.
            scheduler.scheduleAtFixedRate(
                    () -> prober.probe(ep),
                    0, interval, TimeUnit.SECONDS);
            log.info("Scheduled '{}' every {}s", ep.name(), interval);
        }

        log.info("Scheduler running. {} endpoint(s) configured.", config.endpoints.size());

        // Block main thread; shutdown hook handles graceful stop
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down...");
            scheduler.shutdown();
            server.close();
        }));

        Thread.currentThread().join();
    }
}
