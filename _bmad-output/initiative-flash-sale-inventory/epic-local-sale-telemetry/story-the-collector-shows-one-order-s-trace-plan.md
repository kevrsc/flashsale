---
title: 'The collector shows one order''s trace'
type: 'feature'
ticket: '1'
created: '2026-10-09'
status: 'built'
route: 'oneshot'
route_source: 'auto'
review: 'quick'
review_source: 'pinned'
lenses_ran: ['quick']
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/initiative-flash-sale-inventory/architecture-flash-sale-inventory/architecture-flash-sale-inventory.md'
baseline_revision: 'ba775e9c14dcb5054feb700a251ab5d903a6df08'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** A successful order leaves no trace a person can read. Compose runs only PostgreSQL and Kafka, and the application does not export traces.

**Approach:** Add the local OpenTelemetry Collector and export this application's traces to it over OTLP HTTP. After one successful order, that order's trace id is in the collector log. Metrics, console log correlation, Grafana, and the Kafka trace header stay for later stories.

</frozen-after-approval>

## Implementation Notes

Oneshot because the change is the collector service, its config, one dependency, and the trace export properties. That is under 100 lines and does not change the order path.

- Added `otel-collector` on `otel/opentelemetry-collector:0.162.0`, publishing `4318` and mounting `otel-collector-config.yaml` at `/etc/otelcol/config.yaml`. The traces pipeline uses the debug exporter at detailed verbosity. Metrics and log pipelines are left for later stories.
- Added `spring-boot-starter-opentelemetry` and set the OTLP trace endpoint to `http://localhost:4318/v1/traces` with sampling probability `1`.
- `./gradlew test` passed. The collector log check is still a person's check; the collector was not started here.

## Plan Change Log

## Review Triage Log

## Verification

**Commands:**
- `./gradlew test` -- expected: the existing sale tests pass

**Manual checks (if no CLI):**
- Start Compose, place one successful order, and read the collector log. Detailed verbosity includes that order's trace id.
