package com.endpointchecker;

import org.apache.hc.client5.http.auth.*;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.auth.*;
import org.apache.hc.client5.http.impl.classic.*;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.*;
import org.apache.hc.core5.http.*;
import org.apache.hc.core5.http.config.RegistryBuilder;
import org.apache.hc.core5.http.io.entity.*;
import org.apache.hc.core5.ssl.*;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

public class Prober {

    private static final Logger log = LoggerFactory.getLogger(Prober.class);
    private static final String USER_AGENT = "endpoint-checker/1.0 (SOAP monitoring)";

    public void probe(AppConfig.Endpoint ep) {
        String name = ep.name();
        String url  = ep.url();
        long startNs = System.nanoTime();

        try (CloseableHttpClient client = buildClient(ep)) {
            HttpPost request = buildRequest(ep);

            try (CloseableHttpResponse response = client.execute(request)) {
                double elapsed = (System.nanoTime() - startNs) / 1e9;
                int code = response.getCode();
                String body = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
                if (body == null) body = "";

                Metrics.ENDPOINT_STATUS_CODE.labels(name, url).set(code);
                Metrics.ENDPOINT_RESPONSE_SECONDS.labels(name, url).set(elapsed);

                ValidationResult vr = validate(code, body, ep.expected());
                if (vr.ok()) {
                    Metrics.ENDPOINT_UP.labels(name, url).set(1);
                    Metrics.ENDPOINT_CHECKS_TOTAL.labels(name, url, "success").inc();
                    log.info("[OK]   {} — {}", name, String.format("%.3fs", elapsed));
                } else {
                    Metrics.ENDPOINT_UP.labels(name, url).set(0);
                    Metrics.ENDPOINT_CHECKS_TOTAL.labels(name, url, "failure").inc();
                    log.warn("[FAIL] {} — {}", name, vr.reason());
                }
            }
        } catch (Exception e) {
            Metrics.ENDPOINT_UP.labels(name, url).set(0);
            Metrics.ENDPOINT_RESPONSE_SECONDS.labels(name, url).set(0);
            Metrics.ENDPOINT_STATUS_CODE.labels(name, url).set(0);
            Metrics.ENDPOINT_CHECKS_TOTAL.labels(name, url, "failure").inc();
            log.error("[ERR]  {} — {}", name, e.getMessage());
        }
    }

    // -------------------------------------------------------------------------

    private CloseableHttpClient buildClient(AppConfig.Endpoint ep) throws Exception {
        var builder = HttpClients.custom();

        if (!ep.tlsVerify()) {
            var sslCtx = SSLContextBuilder.create()
                    .loadTrustMaterial(TrustAllStrategy.INSTANCE)
                    .build();
            var socketFactory = SSLConnectionSocketFactoryBuilder.create()
                    .setSslContext(sslCtx)
                    .setHostnameVerifier(NoopHostnameVerifier.INSTANCE)
                    .build();
            builder.setConnectionManager(
                    PoolingHttpClientConnectionManagerBuilder.create()
                            .setSSLSocketFactory(socketFactory)
                            .build());
        }

        switch (ep.auth().type().toLowerCase()) {
            case "basic" -> {
                var creds = new BasicCredentialsProvider();
                creds.setCredentials(
                        new AuthScope(null, -1),
                        new UsernamePasswordCredentials(
                                ep.auth().username(),
                                ep.auth().password().toCharArray()));
                builder.setDefaultCredentialsProvider(creds);
            }
            case "kerberos" -> {
                var subject = KerberosHelper.loginWithKeytab(
                        ep.auth().principal(), ep.auth().keytab());
                var gssCred = KerberosHelper.createGssCredential(
                        subject, ep.auth().principal());
                var creds = new BasicCredentialsProvider();
                creds.setCredentials(new AuthScope(null, -1), new KerberosCredentials(gssCred));
                builder.setDefaultAuthSchemeRegistry(
                        RegistryBuilder.<AuthSchemeFactory>create()
                                .register(StandardAuthScheme.SPNEGO, SPNegoSchemeFactory.DEFAULT)
                                .build()
                ).setDefaultCredentialsProvider(creds);
            }
            // "none" — no auth configuration needed
        }

        return builder.build();
    }

    private HttpPost buildRequest(AppConfig.Endpoint ep) {
        var request = new HttpPost(ep.url());
        request.setHeader(HttpHeaders.USER_AGENT, USER_AGENT);

        // Set body; content type will be overridden by explicit headers below
        if (ep.body() != null && !ep.body().isBlank()) {
            request.setEntity(new StringEntity(ep.body(), StandardCharsets.UTF_8));
        }

        // Apply all headers from config (overrides entity's default Content-Type)
        ep.headers().forEach(request::setHeader);

        // SOAPAction — only if not already set in headers
        if (ep.soapAction() != null && request.getFirstHeader("SOAPAction") == null) {
            request.setHeader("SOAPAction", "\"" + ep.soapAction() + "\"");
        }

        request.setConfig(RequestConfig.custom()
                .setConnectTimeout(Timeout.ofSeconds(ep.timeout()))
                .setConnectionRequestTimeout(Timeout.ofSeconds(ep.timeout()))
                .setResponseTimeout(Timeout.ofSeconds(ep.timeout()))
                .build());

        return request;
    }

    // -------------------------------------------------------------------------

    private record ValidationResult(boolean ok, String reason) {}

    private ValidationResult validate(int code, String body, AppConfig.Expected expected) {
        if (code != expected.statusCode()) {
            return new ValidationResult(false, "status " + code + " != " + expected.statusCode());
        }
        if (expected.bodyRegex() != null) {
            if (!Pattern.compile(expected.bodyRegex(), Pattern.DOTALL).matcher(body).find()) {
                return new ValidationResult(false, "body does not match regex: " + expected.bodyRegex());
            }
        }
        if (expected.bodyXpath() != null) {
            try {
                if (!evaluateXPath(body, expected.bodyXpath())) {
                    return new ValidationResult(false,
                            "XPath " + expected.bodyXpath() + " matched nothing");
                }
            } catch (Exception e) {
                return new ValidationResult(false, "XPath error: " + e.getMessage());
            }
        }
        return new ValidationResult(true, "ok");
    }

    private boolean evaluateXPath(String xml, String xpathExpr) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        // XXE prevention
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var doc = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        var nodes = (NodeList) XPathFactory.newInstance().newXPath()
                .evaluate(xpathExpr, doc, XPathConstants.NODESET);
        return nodes.getLength() > 0;
    }
}
