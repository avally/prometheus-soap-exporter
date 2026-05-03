# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & run

- `./gradlew clean shadowJar` — собирает fat-jar в `build/libs/*-all.jar` (плагин `com.github.johnrengelman.shadow`).
- `./gradlew run` — запускает `com.poliproger.endpointchecker.Main` локально. Требует `ENDPOINTS_CONFIG` (по умолчанию `/app/endpoints.yml`) и опционально `METRICS_PORT` (по умолчанию `9116`).
- `./gradlew test` — гоняет JUnit 5 тесты (`AppConfigTest`, `MetricsTest`, `ProberTest`). После теста автоматически запускается `jacocoTestReport` (HTML/XML в `build/reports/jacoco/test/`). `Main` и `KerberosHelper*` исключены из coverage в `build.gradle`.
- `docker compose up --build` — собирает и запускает образ; `docker-compose.yml` монтирует `examples/endpoints.example.yml` как конфиг.
- `docker build -t prometheus-soap-exporter:dev .` — двухстадийная сборка (Gradle 8.7 / JDK 21 → Temurin 21 JRE + `krb5-user` для Kerberos runtime).

Тесты — JUnit Jupiter (`junit-bom:5.10.2`). `ProberTest` поднимает локальный `com.sun.net.httpserver.HttpServer` на эфемерном порту и ходит в него реальным HC5-клиентом, проверяя метрики, headers, Basic-auth, XPath/regex и обработку ошибок. Тесты используют статические `Metrics.*` — каждый тест начинает со `clear()` всех гейджей и каунтера.

## Архитектура

Однопроцессное Java 21-приложение, проба SOAP-эндпоинтов по расписанию и публикация Prometheus-метрик. Пять файлов в `com.poliproger.endpointchecker`:

- **`Main`** — entrypoint. Читает `ENDPOINTS_CONFIG` и `METRICS_PORT` из env, поднимает `HTTPServer` (Prometheus `simpleclient_httpserver`) на `/metrics`, создаёт `ScheduledExecutorService` с пулом `max(2, endpoints.size())` и для каждого эндпоинта инстанцирует `Prober` (один на endpoint) и вызывает `scheduleAtFixedRate(prober::probe, 0, interval, SECONDS)`. Также регистрирует `soap_endpoint_info` со статическими метаданными. Главный поток блокируется на `Thread.join()`; `shutdownHook` гасит scheduler, закрывает каждого `Prober` (а с ним — переиспользуемый `CloseableHttpClient`) и HTTP-сервер.
- **`AppConfig`** — парсер `endpoints.yml` через SnakeYAML. Все типы — Java records (`Defaults`, `Auth`, `Expected`, `Endpoint`). `defaults.{interval,timeout}` применяются к каждому эндпоинту, если не переопределены. Валидирует обязательные поля (`name`, `url`) и required-поля для `auth.type` (`basic`: username+password, `kerberos`: principal+keytab); неизвестный `auth.type` отвергается. Структура YAML описана в README.md.
- **`Prober`** — `AutoCloseable`, по одному на endpoint. В конструкторе один раз строит `CloseableHttpClient` (Apache HttpClient 5) с per-endpoint TLS/SSL-context'ом и при необходимости JAAS-логином по keytab; метод `probe()` использует **тот же** клиент на каждом запуске. Это снимает накладные расходы на TLS-handshake и Kerberos-login на каждый интервал. Здесь же XPath/regex/status-code-валидация и обновление метрик.
- **`KerberosHelper`** — JAAS-логин по keytab (`com.sun.security.auth.module.Krb5LoginModule`) и создание `GSSCredential` (SPNEGO + Krb5 OIDs) для использования `SPNegoSchemeFactory` в HttpClient 5. Использует `Subject.callAs` (Java 21 API, не deprecated `Subject.doAs`). Вызывается из конструктора `Prober` ровно один раз на endpoint.
- **`Metrics`** — статические Prometheus-метрики, регистрируемые в default registry на старте класса:
  - `soap_endpoint_up` (Gauge, `name`,`url`)
  - `soap_endpoint_response_seconds` (Gauge, `name`,`url`) — длительность последнего probe
  - `soap_endpoint_status_code` (Gauge, `name`,`url`)
  - `soap_endpoint_checks_total` (Counter, `name`,`url`,`result`)
  - `soap_endpoint_probe_duration_seconds` (Histogram, `name`,`url`) — обновляется только при успешном HTTP-ответе; для квантилей через `histogram_quantile`
  - `soap_endpoint_info` (Gauge, `name`,`url`,`auth_type`,`soap_action`,`expected_status_code`,`tls_verify`) — всегда `1`, регистрируется в `Main` при старте

### Важные нюансы

- `scheduleAtFixedRate` выбран намеренно: если probe длится дольше `interval`, следующий запускается сразу после, без накопления. См. комментарий в `Main.java`.
- В `Prober.evaluateXPath` включён `disallow-doctype-decl=true` для защиты от XXE — не убирай.
- При `tls_verify: false` создаётся отдельный `PoolingHttpClientConnectionManager` с `TrustAllStrategy` + `NoopHostnameVerifier`. Это нужно только для probe-клиента, не глобально.
- `SOAPAction` оборачивается в дополнительные кавычки (`"\"" + soapAction + "\""`), как требует SOAP 1.1, и не выставляется, если уже задан в `headers`.
- При любом исключении в `Prober.probe` метрики `up`, `response_seconds`, `status_code` обнуляются — иначе остались бы значения предыдущей успешной пробы.
- Kerberos в Docker требует, чтобы `/etc/krb5.conf` и keytab были смонтированы внутрь контейнера; runtime-образ ставит `krb5-user` для нативных libs.

## CI/CD

- `.github/workflows/build.yml` — на PR в `main`: только `docker buildx build` без push (валидация Dockerfile).
- `.github/workflows/release.yml` — на push в `main` и теги `v*`: multi-arch (`linux/amd64`, `linux/arm64`) push в `ghcr.io/${owner}/prometheus-soap-exporter` с тегами `latest` (только main), `sha-<short>`, и semver (`X.Y.Z`, `X.Y`, `X`) для git-тегов.
- В `build.gradle` версия зашита как `0.2.0`. Публикуемые теги Docker-образа берутся **только** из git-тегов через `docker/metadata-action`, поэтому версия в gradle нужна лишь для имени fat-jar (`build/libs/*-0.2.0-all.jar`); чтобы образ получил тег `0.2.0`, нужно создать git-тег `v0.2.0`.