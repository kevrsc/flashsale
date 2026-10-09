---
type: epic
title: "Local sale telemetry"
parent: initiative-flash-sale-inventory
covers: []
after: ["epic-local-flash-sale-inventory"]
assignee: ""
risk: medium
---

# Local sale telemetry

## Description

The local service already takes orders and publishes an order event. This epic makes one order observable. Micrometer sends metrics and traces as OTLP to a local collector. An OpenTelemetry log exporter sends logs there too. The service log prints `trace_id` and `span_id`. The order event keeps its JSON body. W3C trace-context text headers on the Kafka record let a later consumer continue the same trace.

## Outcome

A person running the local service follows one successful order from the collector's trace, to the service log line with that trace's ids, to the text headers on the order event, and sees that run's metrics in Grafana.

## Requirements

- TEL-1: Docker Compose starts an OpenTelemetry Collector, Prometheus, and Grafana beside the existing Postgres and Kafka. The Boot process stays on the host and exports OTLP to that collector. (initiative-flash-sale-inventory.md, section Additional scope)
- TEL-2: Micrometer exports metrics and traces in OTLP to the local collector. (initiative-flash-sale-inventory.md, section Additional scope)
- TEL-3: Spring Boot Micrometer Tracing puts `traceId` and `spanId` on the log MDC. The `logback-spring.xml` pattern prints those values as `trace_id` and `span_id`. The sale totals log still shows requests, queries, and updates. A metrics view does not replace that log. (initiative-flash-sale-inventory.md, section Additional scope; spec Constraints)
- TEL-4: An `otel-collector-config.yaml` receives OTLP metrics, traces, and logs. It exports metrics for Prometheus. A person can read that order's trace from the collector. Logs reach the collector through an OpenTelemetry log exporter, not through Micrometer. (initiative-flash-sale-inventory.md, section Additional scope)
- TEL-5: The order event on `inventory.order-accepted` keeps its JSON body and message key. The Kafka record also carries W3C trace-context text headers. Publication stays `spring-modulith-events-kafka`. (initiative-flash-sale-inventory.md, section Additional scope; architecture AD-5)
- TEL-6: Those headers are visible with `kafka-console-consumer` or a GUI, and a test asserts them. (initiative-flash-sale-inventory.md, section Additional scope)

## Done when

1. Compose starts the collector, Prometheus, and Grafana with Postgres and Kafka, and the host service sends OTLP to the collector.
2. After one successful order, Grafana shows a metric from that run, and a person can read that order's trace from the collector.
3. The service log for that order includes the same trace id and span id. The collector has received the log signal for that order. The sale totals lines for requests, queries, and updates are still present.
4. The order event still has the same JSON body and key. Its text headers carry the trace context. A person can read those headers with `kafka-console-consumer` or a GUI, and a test asserts them.
5. A person runs these checks on the local service.

## Boundaries

This service and the local Compose file. Not a hosted environment, not the order service, and not a change to inventory rules or the order-event JSON. Hosted Loki, OpenSearch, and Elastic are out of scope. The log pattern is what they would use to link a line to a request.

The order service remains the consumer of `inventory.order-accepted`. This epic adds trace headers to that record. It does not build the consumer.

## References

- parent — _bmad-output/initiative-flash-sale-inventory/initiative-flash-sale-inventory.md, section Additional scope
- constraint — _bmad-output/initiative-flash-sale-inventory/spec-flash-sale-inventory/spec-flash-sale-inventory.md, section Constraints
- constraint — _bmad-output/initiative-flash-sale-inventory/spec-flash-sale-inventory/spec-flash-sale-inventory.md, section Non-goals
- architecture — _bmad-output/initiative-flash-sale-inventory/architecture-flash-sale-inventory/architecture-flash-sale-inventory.md, section AD-5
- architecture — _bmad-output/initiative-flash-sale-inventory/architecture-flash-sale-inventory/architecture-flash-sale-inventory.md, section AD-8
- architecture — _bmad-output/initiative-flash-sale-inventory/architecture-flash-sale-inventory/architecture-flash-sale-inventory.md, section AD-10
- architecture — _bmad-output/initiative-flash-sale-inventory/architecture-flash-sale-inventory/architecture-flash-sale-inventory.md, section AD-11
- glossary — _bmad-output/initiative-flash-sale-inventory/spec-flash-sale-inventory/glossary.md

## Notes

- Risk is medium because Compose and the outbox publish path are shared with the finished sale.
- Waits on epic-local-flash-sale-inventory because: the local service must already publish `inventory.order-accepted` and write the sale totals log.
- Source conflict: TEL-1 — AD-10 limits Compose to Postgres and Kafka, vs this epic adding the collector, Prometheus, and Grafana. Settled by the decision below.
- Decision: TEL-1 adds the OpenTelemetry Collector, Prometheus, and Grafana to local Compose. This replaces the AD-10 limit of Postgres and Kafka only. The Boot process stays on the host (user, 2026-10-09).
- Source conflict: TEL-2, TEL-3 — AD-8 and the spec Constraints say a metrics endpoint does not replace the sale totals log, vs this epic adding Micrometer metrics. Settled by the decision below.
- Decision: Micrometer metrics are additive. The sale totals log remains the tally. Prometheus does not replace it (spec Constraints, 2026-10-09).
- Source conflict: TEL-5 — the request names KafkaTemplate or Spring Cloud Stream, vs AD-5 requiring `spring-modulith-events-kafka`. Settled by the decision below.
- Decision: TEL-5 keeps the publisher AD-5 already requires. Trace context is W3C text headers on that record, not new JSON properties. `namastack-outbox-observability` 1.7.3 stores the request trace on the outbox record and restores it on the worker. Micrometer observation on the single KafkaTemplate then writes `traceparent` (AD-5, 2026-10-09).
- Decision: TEL-3 prints Micrometer's MDC keys `traceId` and `spanId` under the labels `trace_id` and `span_id` (AD-11, 2026-10-09).
- Decision: this epic does not own CAP-1 through CAP-8 (user, 2026-10-09).
- Decision: entry 1 is the tracer bullet. Entry 2 follows it because the outbox trace header is the uncertain slice. The stories are one lane, in build order (2026-10-09).
- Decision: entry 5 is the refactor sweep. Entry 6 is the closing check a person runs across the collector, Grafana, the log, and the Kafka headers (2026-10-09).
- Decision: Tempo and Loki stay out of this epic. The collector debug exporter is the trace and log view. Grafana datasources and dashboards are files the build mounts. A person opens Grafana to look and does not create those connections by hand. The same file approach applies to Tempo and Loki when those stores are added (2026-10-09).
