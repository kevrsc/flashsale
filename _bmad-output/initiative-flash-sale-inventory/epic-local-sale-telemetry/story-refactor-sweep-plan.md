---
title: 'Refactor sweep'
type: 'refactor'
ticket: '5'
created: '2026-10-12'
status: 'built'
route: 'oneshot'
route_source: 'auto'
review: 'quick'
review_source: 'pinned'
lenses_ran: ['quick']
review_loop_iteration: 0
baseline_revision: '4036f10e1e9ebb262d5feb4b88f975e7066d1a6a'
context:
  - '{project-root}/_bmad-output/initiative-flash-sale-inventory/architecture-flash-sale-inventory/architecture-flash-sale-inventory.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** The telemetry stories left two stale spots. The README still says Compose runs only PostgreSQL and Kafka. The trace test builds a new log event and copies the message and MDC onto it, instead of laying out the event the capture already received.

**Approach:** Correct the README so it names the collector, Prometheus, and Grafana that Compose already runs, including their localhost ports. Make the trace test lay out the captured sale-totals event with the production console pattern, and keep the sale totals message text. Leave the appender version, the 20 second Kafka poll, the sale totals messages, and the trace, metric, and Kafka wiring as they are.

</frozen-after-approval>

## Implementation Notes

Oneshot because the change is a README sentence and a test that uses the captured log event. That is under 100 lines and does not change the order path or the export wiring. The appender's incubator transitive stays, because the pinned appender `2.26.0-alpha` declares it.

The README technology paragraph and the local run notes now name the collector, Prometheus, and Grafana with their localhost ports. `TotalsLogCapture` keeps each logging event. `orderEventCarriesTheRequestTrace` lays that event out with the production pattern. The sale totals message list, the 20 second Kafka poll, and the export wiring are unchanged. `./gradlew test` passed.

## Plan Change Log

## Review Triage Log

Quick review, one pass. No findings.

## Verification

**Commands:**
- `./gradlew test` -- expected: the sale tests pass, the Kafka `traceparent` check still matches the request trace id, and the captured sale-totals event still renders `trace_id` and `span_id`
