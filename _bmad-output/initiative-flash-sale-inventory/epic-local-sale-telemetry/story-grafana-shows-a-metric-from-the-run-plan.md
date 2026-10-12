---
title: 'Grafana shows a metric from the run'
type: 'feature'
ticket: '3'
created: '2026-10-12'
status: 'built'
route: 'full'
route_source: 'auto'
review: 'quick'
review_source: 'pinned'
lenses_ran: ['quick']
review_loop_iteration: 0
baseline_revision: '01fe8b8f0521bea520d8d64cd1e90b777e75ecb7'
context:
  - '{project-root}/_bmad-output/initiative-flash-sale-inventory/architecture-flash-sale-inventory/architecture-flash-sale-inventory.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** A successful order does not show up as a metric. The collector accepts traces only, so Micrometer's metrics post to `/v1/metrics` gets 404, and Grafana and Prometheus are not running.

**Approach:** Export this service's Micrometer metrics through the collector to Prometheus, and mount a Grafana datasource and dashboard so opening Grafana shows a metric from that order. The sale totals log stays the tally.

## Boundaries & Constraints

**Always:** The Boot process stays on the host and uses localhost. Compose services call each other by service name. Prometheus scrapes `otel-collector:8889`. Grafana's datasource is `http://prometheus:9090`. The traces pipeline stays on the debug exporter at detailed verbosity. A collector failure does not change the shopper outcome or the Kafka send. Images stay `prom/prometheus:v3.15.0` and `grafana/grafana:13.2.3`.

**Never:** Tempo, Loki, a second metrics registry, a metrics view that replaces the sale totals log, a custom sale counter that duplicates that log, host config that uses Compose DNS names, and a Grafana connection the person has to click together.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| One successful order | Collector, Prometheus, and Grafana are up. One order is placed. | Grafana's provisioned dashboard shows an HTTP server request count that includes that order. The series comes from Prometheus. | No error expected |
| Collector metrics path down | The order is placed while nothing accepts `/v1/metrics`. | The shopper still gets the order outcome. The sale totals lines still log. | Metrics publish logs a failure and does not fail the request |

</frozen-after-approval>

## Code Map

- `compose.yaml:54` — `otel-collector` already publishes `4318` and mounts `otel-collector-config.yaml`. Add `prometheus` and `grafana` beside it. Do not change Postgres, Kafka advertised listeners, or topic creation.
- `otel-collector-config.yaml:11` — traces pipeline to the debug exporter. Add a metrics pipeline from the OTLP receiver to a Prometheus exporter on `0.0.0.0:8889`. Leave the collector's own telemetry off that port.
- `src/main/resources/application.properties:3` — trace export is already set. Set `management.otlp.metrics.export.url` to `http://localhost:4318/v1/metrics`. `spring-boot-starter-opentelemetry` already brings `micrometer-registry-otlp`. No new application meter: `SaleTotals` remains a log.
- `prometheus.yml` — new file. Scrape `otel-collector:8889`.
- `grafana/provisioning/datasources/prometheus.yml` — new file. Datasource URL `http://prometheus:9090`.
- `grafana/provisioning/dashboards/dashboards.yml` — new file. Loads the dashboard file.
- `grafana/dashboards/orders.json` — new file. One panel for the HTTP server request count Prometheus exports after the collector receives Micrometer's `http.server.requests` timer. Confirm the series name from Prometheus and use that count query.

## Tasks & Acceptance

**Execution:**
- [ ] `otel-collector-config.yaml` -- add the Prometheus exporter on `0.0.0.0:8889` and a metrics pipeline from `otlp` to it. Keep the traces pipeline. -- This is the path that stops the `/v1/metrics` 404.
- [ ] `prometheus.yml` -- scrape `otel-collector:8889`. -- Prometheus is the store Grafana reads.
- [ ] `grafana/provisioning/datasources/prometheus.yml` -- provision the Prometheus datasource at `http://prometheus:9090`. -- A person does not create the connection.
- [ ] `grafana/provisioning/dashboards/dashboards.yml` and `grafana/dashboards/orders.json` -- provision one dashboard whose panel is the HTTP server request count. -- Opening Grafana shows the metric.
- [ ] `compose.yaml` -- run `prom/prometheus:v3.15.0` on host port `9090` and `grafana/grafana:13.2.3` on host port `3000`, mount the files above, and let anonymous viewing open the dashboard. Do not publish `8889` to the host. -- Compose is how the person reaches Grafana.
- [ ] `src/main/resources/application.properties` -- set the Micrometer OTLP metrics URL to `http://localhost:4318/v1/metrics`. -- The host process exports metrics to the collector.

**Acceptance Criteria:**
- Given Compose is up and one successful order has been placed, when Grafana is opened, then the provisioned dashboard shows an HTTP request count that includes that order, and Prometheus is the source of that series.
- Given the collector is not accepting metrics, when an order is placed, then the shopper outcome is unchanged and `./gradlew test` still passes.

## Implementation Notes

Prometheus lists Micrometer's `http.server.requests` timer as `http_server_requests_milliseconds_count`. The dashboard panel uses `sum(http_server_requests_milliseconds_count)`. After one successful order that count was 1; a second verification order raised it to 2. Metrics can lag about a minute because the Micrometer export step stays at its default. With the collector stopped, the shopper still got "The order is in process", the sale totals log kept writing, and Micrometer logged `Failed to publish metrics to OTLP receiver`. The collector was started again after that check. No browser tool was available; Grafana's API showed anonymous access and the Orders dashboard, and Prometheus was the panel's datasource. `./gradlew test` passed. Quick review found nothing to change.

## Plan Change Log

## Review Triage Log

## Design Notes

The metric is the HTTP server request timer Micrometer already records. The sale totals log remains the tally, so this story does not add a second order counter. Grafana anonymous viewing is so the person opens the dashboard and does not create a user or a datasource. The panel query is the count series Prometheus actually lists for that timer after one order.

## Verification

**Commands:**
- `./gradlew test` -- expected: the sale tests pass with the metrics URL set
- After `docker compose up`, place one successful order, then query Prometheus for the HTTP server request count and open the provisioned Grafana dashboard -- expected: the count includes that order and Grafana reads it from Prometheus

**Manual checks (if no CLI):**
- Open Grafana at `localhost:3000` and read the provisioned dashboard. No datasource is created by hand.
