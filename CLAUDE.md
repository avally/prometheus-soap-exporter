# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & run

- `./gradlew clean shadowJar` — собирает fat-jar в `build/libs/*-all.jar` (плагин `com.github.johnrengelman.shadow`).
- `./gradlew run` — запускает `com.poliproger.endpointchecker.Main` локально. Требует `ENDPOINTS_CONFIG` (по умолчанию `/app/endpoints.yml`) и опционально `METRICS_PORT` (по умолчанию `9116`).
- `docker compose up --build` — собирает и запускает образ; `docker-compose.yml` монтирует `examples/endpoints.example.yml` как конфиг.
- `docker build -t prometheus-soap-exporter:dev .` — двухстадийная сборка (Gradle 8.7 / JDK 21 → Temurin 21 JRE + `krb5-user` для Kerberos runtime).

Тестов нет (нет `src/test/**`, нет JUnit-зависимости). Не выдумывай команды для них.

## Архитектура

Однопроцессное Java 21-приложение, проба SOAP-эндпоинтов по расписанию и публикация Prometheus-метрик. Пять файлов в `com.poliproger.endpointchecker`:

- **`Main`** — entrypoint. Читает `ENDPOINTS_CONFIG` и `METRICS_PORT` из env, поднимает `HTTPServer` (Prometheus `simpleclient_httpserver`) на `/metrics`, создаёт `ScheduledExecutorService` с пулом `max(2, endpoints.size())` и для каждого эндпоинта вызывает `scheduleAtFixedRate(..., 0, interval, SECONDS)`. Главный поток блокируется на `Thread.join()`; `shutdownHook` останавливает scheduler и HTTP-сервер.
- **`AppConfig`** — парсер `endpoints.yml` через SnakeYAML. Все типы — Java records (`Defaults`, `Auth`, `Expected`, `Endpoint`). `defaults.{interval,timeout}` применяются к каждому эндпоинту, если не переопределены. Структура YAML описана в README.md.
- **`Prober`** — на каждый probe собирает **новый** `CloseableHttpClient` (Apache HttpClient 5) и делает один POST. После probe и клиент, и response закрываются через try-with-resources. Это намеренно: Kerberos-логин и SSL-context зависят от per-endpoint настроек. Здесь же XPath/regex/status-code-валидация и обновление метрик.
- **`KerberosHelper`** — JAAS-логин по keytab (`com.sun.security.auth.module.Krb5LoginModule`) и создание `GSSCredential` (SPNEGO + Krb5 OIDs) для использования `SPNegoSchemeFactory` в HttpClient 5. Использует `Subject.callAs` (Java 21 API, не deprecated `Subject.doAs`).
- **`Metrics`** — четыре статических Prometheus-метрики, регистрируемые в default registry на старте класса (`soap_endpoint_up`, `soap_endpoint_response_seconds`, `soap_endpoint_status_code`, `soap_endpoint_checks_total`). Лейблы метрик — `name`, `url` (+ `result` для счётчика).

### Важные нюансы

- `scheduleAtFixedRate` выбран намеренно: если probe длится дольше `interval`, следующий запускается сразу после, без накопления. См. комментарий в `Main.java:36-38`.
- В `Prober.evaluateXPath` включён `disallow-doctype-decl=true` для защиты от XXE — не убирай.
- При `tls_verify: false` создаётся отдельный `PoolingHttpClientConnectionManager` с `TrustAllStrategy` + `NoopHostnameVerifier`. Это нужно только для probe-клиента, не глобально.
- `SOAPAction` оборачивается в дополнительные кавычки (`"\"" + soapAction + "\""`), как требует SOAP 1.1, и не выставляется, если уже задан в `headers`.
- При любом исключении в `Prober.probe` метрики `up`, `response_seconds`, `status_code` обнуляются — иначе остались бы значения предыдущей успешной пробы.
- Kerberos в Docker требует, чтобы `/etc/krb5.conf` и keytab были смонтированы внутрь контейнера; runtime-образ ставит `krb5-user` для нативных libs.

## CI/CD

- `.github/workflows/build.yml` — на PR в `main`: только `docker buildx build` без push (валидация Dockerfile).
- `.github/workflows/release.yml` — на push в `main` и теги `v*`: multi-arch (`linux/amd64`, `linux/arm64`) push в `ghcr.io/${owner}/prometheus-soap-exporter` с тегами `latest` (только main), `sha-<short>`, и semver (`X.Y.Z`, `X.Y`, `X`) для git-тегов.
- В `build.gradle` версия зашита как `1.0.0`, но публикуемые теги образа берутся **только** из git-тегов через `docker/metadata-action`. README ссылается на `0.1.0` как реальный релизный тег.