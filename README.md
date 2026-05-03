# prometheus-soap-exporter

Prometheus exporter для активного мониторинга SOAP-эндпоинтов с поддержкой Basic Auth, SPNEGO/Kerberos и валидации тела ответа через XPath / regex.

## Features

- SOAP POST-запросы с произвольными заголовками и телом
- Аутентификация: `none`, `basic`, `kerberos` (SPNEGO через JAAS Krb5LoginModule)
- Валидация ответа: HTTP-код, regex по телу, XPath по XML
- Конфигурируемые `interval`, `timeout`, `tls_verify` на уровне эндпоинта
- Multi-arch образ: `linux/amd64`, `linux/arm64`
- 4 Prometheus-метрики из коробки

## Quick start

```bash
docker run -d --name soap-exporter \
  -p 9116:9116 \
  -v $(pwd)/endpoints.yml:/app/endpoints.yml:ro \
  ghcr.io/avally/prometheus-soap-exporter:latest
```

Метрики на `http://localhost:9116/metrics`.

## Configuration

Полная схема `endpoints.yml`:

```yaml
defaults:
  interval: 60        # секунды между проверками (default для всех эндпоинтов)
  timeout: 10         # таймаут запроса в секундах (default)

endpoints:
  - name: "Имя эндпоинта"           # обязательно, используется как label name
    url: "https://..."               # обязательно
    soap_action: "..."               # опционально, заголовок SOAPAction
    headers:                          # опционально, произвольные HTTP-заголовки
      Content-Type: "text/xml; charset=utf-8"
    body: |                           # опционально, тело SOAP-запроса
      <soapenv:Envelope>...</soapenv:Envelope>
    auth:                             # опционально, default {type: none}
      type: none | basic | kerberos
      username: "..."                 # для basic
      password: "..."                 # для basic
      keytab: "/app/service.keytab"   # для kerberos
      principal: "user@DOMAIN.COM"    # для kerberos
    expected:                         # опционально, default {status_code: 200}
      status_code: 200
      body_regex: "..."               # Pattern.DOTALL
      body_xpath: "..."               # XPath к XML-ответу
    interval: 60                      # переопределяет defaults.interval
    timeout: 10                       # переопределяет defaults.timeout
    tls_verify: true                  # default true; false отключает проверку TLS
```

См. [examples/endpoints.example.yml](examples/endpoints.example.yml).

## Authentication examples

### none

```yaml
auth:
  type: none
```

### basic

```yaml
auth:
  type: basic
  username: "monitor"
  password: "secret"
```

### kerberos

Требует mount `krb5.conf` и keytab внутрь контейнера:

```yaml
# docker-compose.yml
volumes:
  - /etc/krb5.conf:/etc/krb5.conf:ro
  - ./secrets/service.keytab:/app/service.keytab:ro
```

```yaml
# endpoints.yml
auth:
  type: kerberos
  keytab: "/app/service.keytab"
  principal: "monitor@DOMAIN.COM"
```

## Metrics

| Метрика | Тип | Labels | Описание |
|---|---|---|---|
| `soap_endpoint_up` | Gauge | `name`, `url` | 1 если последняя проверка успешна, иначе 0 |
| `soap_endpoint_response_seconds` | Gauge | `name`, `url` | Время ответа последней проверки (секунды) |
| `soap_endpoint_status_code` | Gauge | `name`, `url` | HTTP-код последнего ответа |
| `soap_endpoint_checks_total` | Counter | `name`, `url`, `result` | Счётчик проверок (`result`: `success` \| `failure`) |

## Prometheus scrape config

```yaml
scrape_configs:
  - job_name: 'soap-exporter'
    static_configs:
      - targets: ['soap-exporter:9116']
    scrape_interval: 60s
```

## Environment variables

| Переменная | Default | Описание |
|---|---|---|
| `ENDPOINTS_CONFIG` | `/app/endpoints.yml` | Путь к YAML-конфигу внутри контейнера |
| `METRICS_PORT` | `9116` | Порт HTTP-сервера метрик |

## Docker Compose

### Для разработки (build из сорцов)

См. [docker-compose.yml](docker-compose.yml) в этом репо:

```bash
docker compose up --build
```

### Для потребителей (готовый образ)

```yaml
services:
  soap-exporter:
    image: ghcr.io/avally/prometheus-soap-exporter:0.1.0
    container_name: soap-exporter
    volumes:
      - ./endpoints.yml:/app/endpoints.yml:ro
      # Для Kerberos:
      # - /etc/krb5.conf:/etc/krb5.conf:ro
      # - ./secrets/service.keytab:/app/service.keytab:ro
    ports:
      - "9116:9116"
    restart: unless-stopped
```

## Building locally

```bash
./gradlew clean shadowJar           # fat-jar в build/libs/*-all.jar
docker build -t prometheus-soap-exporter:dev .
```

## Versioning & releases

Образы публикуются в GitHub Container Registry: `ghcr.io/avally/prometheus-soap-exporter`.

Теги:
- `latest` — последний коммит в `main`
- `sha-<short>` — на каждый коммит в `main` и каждый tag
- `vX.Y.Z`, `X.Y`, `X` — на git-теги вида `v*` (semver)

CI: см. [.github/workflows/](.github/workflows/).

## License

[MIT](LICENSE)
