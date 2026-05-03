# prometheus-soap-exporter

Prometheus exporter for active monitoring of SOAP endpoints with support for Basic Auth, SPNEGO/Kerberos, and response body validation via XPath / regex.

## Features

- SOAP POST requests with arbitrary headers and body
- Authentication: `none`, `basic`, `kerberos` (SPNEGO via JAAS Krb5LoginModule)
- Response validation: HTTP status code, regex over body, XPath over XML
- Per-endpoint `interval`, `timeout`, `tls_verify`
- Multi-arch image: `linux/amd64`, `linux/arm64`
- 4 Prometheus metrics out of the box

## Quick start

```bash
docker run -d --name soap-exporter \
  -p 9116:9116 \
  -v $(pwd)/endpoints.yml:/app/endpoints.yml:ro \
  ghcr.io/poliproger/prometheus-soap-exporter:latest
```

Metrics are exposed at `http://localhost:9116/metrics`.

## Configuration

Full `endpoints.yml` schema:

```yaml
defaults:
  interval: 60        # seconds between checks (default for all endpoints)
  timeout: 10         # request timeout in seconds (default)

endpoints:
  - name: "Endpoint name"           # required, used as the `name` label
    url: "https://..."               # required
    soap_action: "..."               # optional, SOAPAction header
    headers:                          # optional, arbitrary HTTP headers
      Content-Type: "text/xml; charset=utf-8"
    body: |                           # optional, SOAP request body
      <soapenv:Envelope>...</soapenv:Envelope>
    auth:                             # optional, default {type: none}
      type: none | basic | kerberos
      username: "..."                 # for basic
      password: "..."                 # for basic
      keytab: "/app/service.keytab"   # for kerberos
      principal: "user@DOMAIN.COM"    # for kerberos
    expected:                         # optional, default {status_code: 200}
      status_code: 200
      body_regex: "..."               # Pattern.DOTALL
      body_xpath: "..."               # XPath against the XML response
    interval: 60                      # overrides defaults.interval
    timeout: 10                       # overrides defaults.timeout
    tls_verify: true                  # default true; false disables TLS verification
```

See [examples/endpoints.example.yml](examples/endpoints.example.yml).

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

Requires `krb5.conf` and the keytab to be mounted into the container:

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

| Metric | Type | Labels | Description |
|---|---|---|---|
| `soap_endpoint_up` | Gauge | `name`, `url` | 1 if the last check succeeded, 0 otherwise |
| `soap_endpoint_response_seconds` | Gauge | `name`, `url` | Response time of the last check (seconds) |
| `soap_endpoint_status_code` | Gauge | `name`, `url` | HTTP status code of the last response |
| `soap_endpoint_checks_total` | Counter | `name`, `url`, `result` | Check counter (`result`: `success` \| `failure`) |

## Prometheus scrape config

```yaml
scrape_configs:
  - job_name: 'soap-exporter'
    static_configs:
      - targets: ['soap-exporter:9116']
    scrape_interval: 60s
```

## Environment variables

| Variable | Default | Description |
|---|---|---|
| `ENDPOINTS_CONFIG` | `/app/endpoints.yml` | Path to the YAML config inside the container |
| `METRICS_PORT` | `9116` | Metrics HTTP server port |

## Docker Compose

### For development (build from sources)

See [docker-compose.yml](docker-compose.yml) in this repo:

```bash
docker compose up --build
```

### For consumers (prebuilt image)

```yaml
services:
  soap-exporter:
    image: ghcr.io/poliproger/prometheus-soap-exporter:0.2.0
    container_name: soap-exporter
    volumes:
      - ./endpoints.yml:/app/endpoints.yml:ro
      # For Kerberos:
      # - /etc/krb5.conf:/etc/krb5.conf:ro
      # - ./secrets/service.keytab:/app/service.keytab:ro
    ports:
      - "9116:9116"
    restart: unless-stopped
```

## Building locally

```bash
./gradlew clean shadowJar           # fat-jar at build/libs/*-all.jar
docker build -t prometheus-soap-exporter:dev .
```

## Versioning & releases

Images are published to GitHub Container Registry: `ghcr.io/poliproger/prometheus-soap-exporter`.

Tags:
- `latest` — latest commit on `main`
- `sha-<short>` — every commit on `main` and every git tag
- `vX.Y.Z`, `X.Y`, `X` — on git tags matching `v*` (semver)

CI: see [.github/workflows/](.github/workflows/).

## License

[MIT](LICENSE)
