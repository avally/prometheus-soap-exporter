package com.poliproger.endpointchecker;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ProberTest {

    private HttpServer server;
    private int port;
    private final AtomicReference<CapturedRequest> captured = new AtomicReference<>();

    private record CapturedRequest(String method, Map<String, String> headers, String body) {}

    @BeforeEach
    void setUp() throws IOException {
        // Avoid leakage between tests — labels accumulate state in the default registry.
        Metrics.ENDPOINT_UP.clear();
        Metrics.ENDPOINT_RESPONSE_SECONDS.clear();
        Metrics.ENDPOINT_STATUS_CODE.clear();
        Metrics.ENDPOINT_CHECKS_TOTAL.clear();
        Metrics.ENDPOINT_PROBE_DURATION.clear();
        Metrics.ENDPOINT_INFO.clear();

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        captured.set(null);
    }

    private static void runProbe(AppConfig.Endpoint ep) {
        try (Prober p = new Prober(ep)) {
            p.probe();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    private void handle(int status, String contentType, String body) {
        handle(status, contentType, body, null, null);
    }

    private void handle(int status, String contentType, String body,
                        String basicUser, String basicPass) {
        HttpHandler handler = new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                Map<String, String> headers = new LinkedHashMap<>();
                ex.getRequestHeaders().forEach((k, v) -> headers.put(k, String.join(",", v)));
                String reqBody = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                captured.set(new CapturedRequest(ex.getRequestMethod(), headers, reqBody));

                if (basicUser != null) {
                    String auth = headers.get("Authorization");
                    String expected = "Basic " + java.util.Base64.getEncoder().encodeToString(
                            (basicUser + ":" + basicPass).getBytes(StandardCharsets.UTF_8));
                    if (auth == null || !auth.equals(expected)) {
                        // HttpClient 5 only sends Basic credentials after a 401 challenge,
                        // so the server must advertise the scheme via WWW-Authenticate.
                        ex.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"test\"");
                        ex.sendResponseHeaders(401, -1);
                        ex.close();
                        return;
                    }
                }

                byte[] payload = body.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", contentType);
                ex.sendResponseHeaders(status, payload.length);
                ex.getResponseBody().write(payload);
                ex.close();
            }
        };
        server.createContext("/", handler);
        server.start();
    }

    private AppConfig.Endpoint endpoint(String name, AppConfig.Expected expected) {
        return endpoint(name, expected, new AppConfig.Auth("none", null, null, null, null),
                Map.of(), "<req/>", null);
    }

    private AppConfig.Endpoint endpoint(String name,
                                        AppConfig.Expected expected,
                                        AppConfig.Auth auth,
                                        Map<String, String> headers,
                                        String body,
                                        String soapAction) {
        return new AppConfig.Endpoint(
                name,
                "http://127.0.0.1:" + port + "/",
                soapAction,
                headers,
                body,
                auth,
                expected,
                60,
                5,
                true);
    }

    private static AppConfig.Expected expect(int status, String regex, String xpath) {
        return new AppConfig.Expected(status, regex, xpath);
    }

    // -------------------------------------------------------------------------
    // Success / failure paths
    // -------------------------------------------------------------------------

    @Test
    void successMatchesStatusOnly() {
        handle(200, "text/xml", "<ok/>");
        AppConfig.Endpoint ep = endpoint("ok-svc", expect(200, null, null));

        runProbe(ep);

        assertEquals(1.0, Metrics.ENDPOINT_UP.labels("ok-svc", ep.url()).get());
        assertEquals(200.0, Metrics.ENDPOINT_STATUS_CODE.labels("ok-svc", ep.url()).get());
        assertTrue(Metrics.ENDPOINT_RESPONSE_SECONDS.labels("ok-svc", ep.url()).get() >= 0);
        assertEquals(1.0, Metrics.ENDPOINT_CHECKS_TOTAL.labels("ok-svc", ep.url(), "success").get());
        assertEquals(0.0, Metrics.ENDPOINT_CHECKS_TOTAL.labels("ok-svc", ep.url(), "failure").get());
    }

    @Test
    void failsWhenStatusCodeMismatches() {
        handle(500, "text/xml", "<oops/>");
        AppConfig.Endpoint ep = endpoint("bad", expect(200, null, null));

        runProbe(ep);

        assertEquals(0.0, Metrics.ENDPOINT_UP.labels("bad", ep.url()).get());
        assertEquals(500.0, Metrics.ENDPOINT_STATUS_CODE.labels("bad", ep.url()).get());
        assertEquals(1.0, Metrics.ENDPOINT_CHECKS_TOTAL.labels("bad", ep.url(), "failure").get());
    }

    @Test
    void successWhenRegexMatchesBody() {
        handle(200, "text/xml", "<Envelope><Status>OK</Status></Envelope>");
        AppConfig.Endpoint ep = endpoint("regex-ok",
                expect(200, "<Status>OK</Status>", null));

        runProbe(ep);

        assertEquals(1.0, Metrics.ENDPOINT_UP.labels("regex-ok", ep.url()).get());
    }

    @Test
    void failsWhenRegexDoesNotMatch() {
        handle(200, "text/xml", "<Envelope><Status>BAD</Status></Envelope>");
        AppConfig.Endpoint ep = endpoint("regex-fail",
                expect(200, "<Status>OK</Status>", null));

        runProbe(ep);

        assertEquals(0.0, Metrics.ENDPOINT_UP.labels("regex-fail", ep.url()).get());
        assertEquals(1.0, Metrics.ENDPOINT_CHECKS_TOTAL.labels("regex-fail", ep.url(), "failure").get());
    }

    @Test
    void regexUsesDotAllAcrossNewlines() {
        handle(200, "text/xml", "<a>\n<b/>\n</a>");
        AppConfig.Endpoint ep = endpoint("dotall",
                expect(200, "<a>.*<b/>.*</a>", null));

        runProbe(ep);

        assertEquals(1.0, Metrics.ENDPOINT_UP.labels("dotall", ep.url()).get());
    }

    @Test
    void successWhenXpathMatches() {
        handle(200, "text/xml",
                "<resp xmlns=\"http://x\"><PingResult>true</PingResult></resp>");
        AppConfig.Endpoint ep = endpoint("xpath-ok",
                expect(200, null, "//*[local-name()='PingResult' and text()='true']"));

        runProbe(ep);

        assertEquals(1.0, Metrics.ENDPOINT_UP.labels("xpath-ok", ep.url()).get());
    }

    @Test
    void failsWhenXpathMatchesNothing() {
        handle(200, "text/xml", "<resp><PingResult>false</PingResult></resp>");
        AppConfig.Endpoint ep = endpoint("xpath-miss",
                expect(200, null, "//PingResult[text()='true']"));

        runProbe(ep);

        assertEquals(0.0, Metrics.ENDPOINT_UP.labels("xpath-miss", ep.url()).get());
    }

    @Test
    void xpathOnInvalidXmlIsTreatedAsFailure() {
        handle(200, "text/xml", "this is not xml at all");
        AppConfig.Endpoint ep = endpoint("xpath-bad",
                expect(200, null, "//something"));

        runProbe(ep);

        assertEquals(0.0, Metrics.ENDPOINT_UP.labels("xpath-bad", ep.url()).get());
        assertEquals(1.0, Metrics.ENDPOINT_CHECKS_TOTAL.labels("xpath-bad", ep.url(), "failure").get());
    }

    @Test
    void xpathRejectsDoctypeForXxe() {
        // disallow-doctype-decl is enabled — payloads with DOCTYPE must fail validation
        handle(200, "text/xml",
                "<!DOCTYPE foo [<!ENTITY x \"y\">]><resp><ok/></resp>");
        AppConfig.Endpoint ep = endpoint("xxe",
                expect(200, null, "//ok"));

        runProbe(ep);

        assertEquals(0.0, Metrics.ENDPOINT_UP.labels("xxe", ep.url()).get());
    }

    @Test
    void allValidatorsMustPassTogether() {
        handle(200, "text/xml", "<r><PingResult>true</PingResult></r>");
        AppConfig.Endpoint ep = endpoint("combined",
                expect(200, "PingResult", "//PingResult[text()='true']"));

        runProbe(ep);

        assertEquals(1.0, Metrics.ENDPOINT_UP.labels("combined", ep.url()).get());
    }

    // -------------------------------------------------------------------------
    // Request construction
    // -------------------------------------------------------------------------

    @Test
    void usesPostMethod() {
        handle(200, "text/xml", "<ok/>");
        AppConfig.Endpoint ep = endpoint("post", expect(200, null, null));

        runProbe(ep);

        assertNotNull(captured.get());
        assertEquals("POST", captured.get().method());
    }

    @Test
    void sendsBodyAsRequestPayload() {
        handle(200, "text/xml", "<ok/>");
        AppConfig.Endpoint ep = endpoint(
                "body",
                expect(200, null, null),
                new AppConfig.Auth("none", null, null, null, null),
                Map.of("Content-Type", "text/xml; charset=utf-8"),
                "<Envelope><Ping/></Envelope>",
                null);

        runProbe(ep);

        assertEquals("<Envelope><Ping/></Envelope>", captured.get().body());
    }

    @Test
    void appliesCustomHeaders() {
        handle(200, "text/xml", "<ok/>");
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "text/xml; charset=utf-8");
        headers.put("X-Custom", "trace-42");
        AppConfig.Endpoint ep = endpoint(
                "headers",
                expect(200, null, null),
                new AppConfig.Auth("none", null, null, null, null),
                headers,
                "<r/>",
                null);

        runProbe(ep);

        Map<String, String> got = captured.get().headers();
        // Header names are case-insensitive but Sun's HttpServer canonicalizes to "X-Custom"
        assertEquals("trace-42", got.get("X-custom"));
        assertTrue(got.getOrDefault("Content-type", "").startsWith("text/xml"));
    }

    @Test
    void wrapsSoapActionInQuotes() {
        handle(200, "text/xml", "<ok/>");
        AppConfig.Endpoint ep = endpoint(
                "soap",
                expect(200, null, null),
                new AppConfig.Auth("none", null, null, null, null),
                Map.of("Content-Type", "text/xml; charset=utf-8"),
                "<r/>",
                "GetStatus");

        runProbe(ep);

        // SOAP 1.1 requires SOAPAction value to be a quoted string.
        assertEquals("\"GetStatus\"", captured.get().headers().get("Soapaction"));
    }

    @Test
    void doesNotOverrideSoapActionWhenSetInHeaders() {
        handle(200, "text/xml", "<ok/>");
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "text/xml; charset=utf-8");
        headers.put("SOAPAction", "Preset");
        AppConfig.Endpoint ep = endpoint(
                "soap-preset",
                expect(200, null, null),
                new AppConfig.Auth("none", null, null, null, null),
                headers,
                "<r/>",
                "Other");

        runProbe(ep);

        assertEquals("Preset", captured.get().headers().get("Soapaction"));
    }

    @Test
    void omitsSoapActionWhenNotSet() {
        handle(200, "text/xml", "<ok/>");
        AppConfig.Endpoint ep = endpoint(
                "no-soap",
                expect(200, null, null),
                new AppConfig.Auth("none", null, null, null, null),
                Map.of("Content-Type", "text/xml; charset=utf-8"),
                "<r/>",
                null);

        runProbe(ep);

        assertNull(captured.get().headers().get("Soapaction"));
    }

    @Test
    void sendsUserAgent() {
        handle(200, "text/xml", "<ok/>");
        AppConfig.Endpoint ep = endpoint("ua", expect(200, null, null));

        runProbe(ep);

        String ua = captured.get().headers().get("User-agent");
        assertNotNull(ua);
        assertTrue(ua.startsWith("endpoint-checker/"));
    }

    // -------------------------------------------------------------------------
    // Auth
    // -------------------------------------------------------------------------

    @Test
    void basicAuthSendsAuthorizationHeader() {
        handle(200, "text/xml", "<ok/>", "alice", "s3cret");
        AppConfig.Endpoint ep = endpoint(
                "basic-ok",
                expect(200, null, null),
                new AppConfig.Auth("basic", "alice", "s3cret", null, null),
                Map.of("Content-Type", "text/xml"),
                "<r/>",
                null);

        runProbe(ep);

        assertEquals(1.0, Metrics.ENDPOINT_UP.labels("basic-ok", ep.url()).get());
    }

    @Test
    void basicAuthFailsWhenServerRejectsCredentials() {
        handle(200, "text/xml", "<ok/>", "alice", "s3cret");
        AppConfig.Endpoint ep = endpoint(
                "basic-bad",
                expect(200, null, null),
                new AppConfig.Auth("basic", "alice", "wrong", null, null),
                Map.of("Content-Type", "text/xml"),
                "<r/>",
                null);

        runProbe(ep);

        assertEquals(0.0, Metrics.ENDPOINT_UP.labels("basic-bad", ep.url()).get());
        assertEquals(401.0, Metrics.ENDPOINT_STATUS_CODE.labels("basic-bad", ep.url()).get());
    }

    // -------------------------------------------------------------------------
    // Error handling
    // -------------------------------------------------------------------------

    @Test
    void connectionFailureZeroesOutNumericMetricsAndCountsFailure() throws IOException {
        // Pre-seed metrics with non-zero values to verify they are reset on failure.
        Metrics.ENDPOINT_UP.labels("dead", "http://127.0.0.1:1/").set(1);
        Metrics.ENDPOINT_RESPONSE_SECONDS.labels("dead", "http://127.0.0.1:1/").set(99);
        Metrics.ENDPOINT_STATUS_CODE.labels("dead", "http://127.0.0.1:1/").set(200);

        AppConfig.Endpoint ep = new AppConfig.Endpoint(
                "dead",
                "http://127.0.0.1:1/", // port 1 — guaranteed connection refused
                null,
                Map.of(),
                "<r/>",
                new AppConfig.Auth("none", null, null, null, null),
                expect(200, null, null),
                60, 2, true);

        runProbe(ep);

        assertEquals(0.0, Metrics.ENDPOINT_UP.labels("dead", ep.url()).get());
        assertEquals(0.0, Metrics.ENDPOINT_RESPONSE_SECONDS.labels("dead", ep.url()).get());
        assertEquals(0.0, Metrics.ENDPOINT_STATUS_CODE.labels("dead", ep.url()).get());
        assertEquals(1.0, Metrics.ENDPOINT_CHECKS_TOTAL.labels("dead", ep.url(), "failure").get());
    }

    @Test
    void multipleSuccessesIncrementCounter() {
        handle(200, "text/xml", "<ok/>");
        AppConfig.Endpoint ep = endpoint("counter", expect(200, null, null));

        try (Prober prober = new Prober(ep)) {
            prober.probe();
            prober.probe();
            prober.probe();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        assertEquals(3.0, Metrics.ENDPOINT_CHECKS_TOTAL.labels("counter", ep.url(), "success").get());
    }
}
