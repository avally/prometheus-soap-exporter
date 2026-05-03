package com.poliproger.endpointchecker;

import org.yaml.snakeyaml.Yaml;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.*;

public class AppConfig {

    public record Defaults(int interval, int timeout) {}

    public record Auth(String type, String username, String password,
                       String keytab, String principal) {}

    public record Expected(int statusCode, String bodyRegex, String bodyXpath) {}

    public record Endpoint(
            String name, String url, String soapAction,
            Map<String, String> headers, String body,
            Auth auth, Expected expected,
            int interval, int timeout, boolean tlsVerify
    ) {}

    public final Defaults defaults;
    public final List<Endpoint> endpoints;

    private AppConfig(Defaults defaults, List<Endpoint> endpoints) {
        this.defaults = defaults;
        this.endpoints = endpoints;
    }

    @SuppressWarnings("unchecked")
    public static AppConfig load(String path) throws IOException {
        try (var is = new FileInputStream(path)) {
            Map<String, Object> root = new Yaml().load(is);

            Map<String, Object> defMap = (Map<String, Object>) root.getOrDefault("defaults", Map.of());
            Defaults defaults = new Defaults(
                    toInt(defMap.getOrDefault("interval", 60)),
                    toInt(defMap.getOrDefault("timeout", 10))
            );

            List<Map<String, Object>> epList =
                    (List<Map<String, Object>>) root.getOrDefault("endpoints", List.of());
            List<Endpoint> endpoints = epList.stream()
                    .map(ep -> parseEndpoint(ep, defaults))
                    .toList();

            return new AppConfig(defaults, endpoints);
        }
    }

    @SuppressWarnings("unchecked")
    private static Endpoint parseEndpoint(Map<String, Object> ep, Defaults defaults) {
        String name = (String) ep.get("name");
        String url  = (String) ep.get("url");
        String soapAction = (String) ep.get("soap_action");
        String body = (String) ep.getOrDefault("body", "");
        int interval  = toInt(ep.getOrDefault("interval", defaults.interval()));
        int timeout   = toInt(ep.getOrDefault("timeout",  defaults.timeout()));
        boolean tlsVerify = (boolean) ep.getOrDefault("tls_verify", true);

        // Headers — keys and values are always strings in YAML
        Map<Object, Object> rawHeaders = (Map<Object, Object>) ep.getOrDefault("headers", Map.of());
        Map<String, String> headers = new LinkedHashMap<>();
        rawHeaders.forEach((k, v) -> headers.put(String.valueOf(k), String.valueOf(v)));

        // Auth
        Map<String, Object> authMap =
                (Map<String, Object>) ep.getOrDefault("auth", Map.of("type", "none"));
        Auth auth = new Auth(
                (String) authMap.getOrDefault("type", "none"),
                (String) authMap.get("username"),
                (String) authMap.get("password"),
                (String) authMap.get("keytab"),
                (String) authMap.get("principal")
        );

        // Expected
        Map<String, Object> expMap =
                (Map<String, Object>) ep.getOrDefault("expected", Map.of());
        Expected expected = new Expected(
                toInt(expMap.getOrDefault("status_code", 200)),
                (String) expMap.get("body_regex"),
                (String) expMap.get("body_xpath")
        );

        return new Endpoint(name, url, soapAction, headers, body,
                auth, expected, interval, timeout, tlsVerify);
    }

    private static int toInt(Object value) {
        return ((Number) value).intValue();
    }
}
