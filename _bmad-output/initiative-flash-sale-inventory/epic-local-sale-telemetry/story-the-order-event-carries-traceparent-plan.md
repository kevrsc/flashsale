---
title: 'The order event carries traceparent'
type: 'feature'
ticket: '2'
created: '2026-10-12'
status: 'built'
route: 'oneshot'
route_source: 'auto'
review: 'quick'
review_source: 'pinned'
lenses_ran: ['quick']
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/initiative-flash-sale-inventory/architecture-flash-sale-inventory/architecture-flash-sale-inventory.md'
baseline_revision: '1396e171b9d066b23bbc2f63b47a0e589335d3f5'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** The order event is sent on a later outbox poll, so the Kafka record does not carry the order request's trace. A downstream consumer cannot continue that trace.

**Approach:** Keep the request's W3C context with the outbox record and restore it on the worker, so the existing Kafka send writes `traceparent`. The JSON body and message key stay unchanged. A test reads that header and checks its trace id against the order request.

</frozen-after-approval>

## Implementation Notes

Oneshot because the change is one dependency, one Kafka observation property, and one test. That is under 100 lines and three files. The order path and the collector metrics pipeline stay as they are.

- Added `io.namastack:namastack-outbox-observability:1.7.3`. It stores the request `traceparent` on the outbox record. The completed row for the test carried that trace id.
- Set `spring.kafka.template.observation-enabled=true` so the existing KafkaTemplate writes the header from the restored span.
- `orderEventCarriesTheRequestTrace` posts a known `traceparent` and reads the header on `inventory.order-accepted`. The JSON body and key checks match the existing event assertion. The poll waits 20 seconds. The first run's send finished about 10 seconds after the request, with failure count 0, while another Boot process shared the database, so an 8 second poll missed the record.
- `./gradlew test` passed. The collector metrics pipeline is unchanged. Story 2.3 exports metrics through the collector, which is what answers the `/v1/metrics` 404.

## Plan Change Log

## Review Triage Log

## Verification

**Commands:**
- `./gradlew test` -- expected: the sale tests pass, and the new check reads `traceparent` on `inventory.order-accepted` with the order request's trace id while the JSON body and key stay unchanged
