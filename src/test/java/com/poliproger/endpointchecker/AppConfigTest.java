package com.poliproger.endpointchecker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AppConfigTest {

    @TempDir
    Path tmp;

    private Path writeYaml(String yaml) throws IOException {
        Path p = tmp.resolve("endpoints.yml");
        Files.writeString(p, yaml);
        return p;
    }

    @Test
    void usesBuiltInDefaultsWhenSectionMissing() throws Exception {
        Path p = writeYaml("""
                endpoints: []
                """);

        AppConfig cfg = AppConfig.load(p.toString());

        assertEquals(60, cfg.defaults.interval());
        assertEquals(10, cfg.defaults.timeout());
        assertTrue(cfg.endpoints.isEmpty());
    }

    @Test
    void parsesExplicitDefaults() throws Exception {
        Path p = writeYaml("""
                defaults:
                  interval: 30
                  timeout: 5
                endpoints: []
                """);

        AppConfig cfg = AppConfig.load(p.toString());

        assertEquals(30, cfg.defaults.interval());
        assertEquals(5, cfg.defaults.timeout());
    }

    @Test
    void endpointInheritsDefaults() throws Exception {
        Path p = writeYaml("""
                defaults:
                  interval: 45
                  timeout: 7
                endpoints:
                  - name: svc
                    url: http://example.com/soap
                """);

        AppConfig cfg = AppConfig.load(p.toString());

        assertEquals(1, cfg.endpoints.size());
        AppConfig.Endpoint ep = cfg.endpoints.get(0);
        assertEquals("svc", ep.name());
        assertEquals("http://example.com/soap", ep.url());
        assertEquals(45, ep.interval());
        assertEquals(7, ep.timeout());
        // defaults applied where explicit values absent
        assertEquals(200, ep.expected().statusCode());
        assertTrue(ep.tlsVerify());
        assertEquals("none", ep.auth().type());
        assertEquals("", ep.body());
        assertNotNull(ep.headers());
        assertTrue(ep.headers().isEmpty());
    }

    @Test
    void endpointOverridesDefaults() throws Exception {
        Path p = writeYaml("""
                defaults:
                  interval: 60
                  timeout: 10
                endpoints:
                  - name: svc
                    url: http://example.com/soap
                    interval: 5
                    timeout: 2
                    tls_verify: false
                """);

        AppConfig.Endpoint ep = AppConfig.load(p.toString()).endpoints.get(0);

        assertEquals(5, ep.interval());
        assertEquals(2, ep.timeout());
        assertFalse(ep.tlsVerify());
    }

    @Test
    void parsesHeadersBodyAndSoapAction() throws Exception {
        Path p = writeYaml("""
                endpoints:
                  - name: svc
                    url: http://example.com/soap
                    soap_action: GetStatus
                    headers:
                      Content-Type: text/xml; charset=utf-8
                      X-Trace-Id: 12345
                    body: |
                      <Envelope/>
                """);

        AppConfig.Endpoint ep = AppConfig.load(p.toString()).endpoints.get(0);

        assertEquals("GetStatus", ep.soapAction());
        assertEquals("text/xml; charset=utf-8", ep.headers().get("Content-Type"));
        // numeric YAML scalars must be coerced to strings
        assertEquals("12345", ep.headers().get("X-Trace-Id"));
        assertTrue(ep.body().contains("<Envelope/>"));
    }

    @Test
    void parsesBasicAuth() throws Exception {
        Path p = writeYaml("""
                endpoints:
                  - name: svc
                    url: http://example.com/soap
                    auth:
                      type: basic
                      username: alice
                      password: s3cret
                """);

        AppConfig.Auth auth = AppConfig.load(p.toString()).endpoints.get(0).auth();

        assertEquals("basic", auth.type());
        assertEquals("alice", auth.username());
        assertEquals("s3cret", auth.password());
        assertNull(auth.keytab());
        assertNull(auth.principal());
    }

    @Test
    void parsesKerberosAuth() throws Exception {
        Path p = writeYaml("""
                endpoints:
                  - name: svc
                    url: http://example.com/soap
                    auth:
                      type: kerberos
                      keytab: /etc/svc.keytab
                      principal: monitor@EXAMPLE.COM
                """);

        AppConfig.Auth auth = AppConfig.load(p.toString()).endpoints.get(0).auth();

        assertEquals("kerberos", auth.type());
        assertEquals("/etc/svc.keytab", auth.keytab());
        assertEquals("monitor@EXAMPLE.COM", auth.principal());
    }

    @Test
    void parsesExpectedRegexAndXpath() throws Exception {
        Path p = writeYaml("""
                endpoints:
                  - name: svc
                    url: http://example.com/soap
                    expected:
                      status_code: 202
                      body_regex: "<ok/>"
                      body_xpath: "//ok"
                """);

        AppConfig.Expected exp = AppConfig.load(p.toString()).endpoints.get(0).expected();

        assertEquals(202, exp.statusCode());
        assertEquals("<ok/>", exp.bodyRegex());
        assertEquals("//ok", exp.bodyXpath());
    }

    @Test
    void emptyEndpointsListIsAllowed() throws Exception {
        Path p = writeYaml("""
                defaults:
                  interval: 60
                  timeout: 10
                """);

        AppConfig cfg = AppConfig.load(p.toString());

        assertNotNull(cfg.endpoints);
        assertTrue(cfg.endpoints.isEmpty());
    }

    @Test
    void multipleEndpointsParsedInOrder() throws Exception {
        Path p = writeYaml("""
                endpoints:
                  - name: a
                    url: http://a/
                  - name: b
                    url: http://b/
                  - name: c
                    url: http://c/
                """);

        AppConfig cfg = AppConfig.load(p.toString());

        assertEquals(3, cfg.endpoints.size());
        assertEquals("a", cfg.endpoints.get(0).name());
        assertEquals("b", cfg.endpoints.get(1).name());
        assertEquals("c", cfg.endpoints.get(2).name());
    }

    @Test
    void missingFileThrowsIOException() {
        assertThrows(IOException.class,
                () -> AppConfig.load(tmp.resolve("does-not-exist.yml").toString()));
    }
}
