---
title: 'One order is visible across the signals'
type: 'feature'
ticket: '6'
created: '2026-10-12'
status: 'built'
route: 'oneshot'
route_source: 'auto'
review: 'quick'
review_source: 'pinned'
lenses_ran: ['quick']
review_loop_iteration: 0
baseline_revision: '4af6b9e6affc7fb9c515e60fb81e5aa001c193a1'
context:
  - '{project-root}/_bmad-output/initiative-flash-sale-inventory/architecture-flash-sale-inventory/architecture-flash-sale-inventory.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** The collector, the console log, the Kafka header, and Grafana each already show a piece of an order, and a person has not followed one successful order across all four.

**Approach:** Write the follow-one-order steps into the README. One order's trace id is read from the collector log, from the console line, and from the Kafka `traceparent` header, and Grafana's provisioned HTTP request count includes that order. The sale totals lines stay. No new store, exporter, or change to the order JSON.

</frozen-after-approval>

## Implementation Notes

Oneshot because the change is the README steps for a check the signals already support. That is under 100 lines and does not change the order path. Inside the Kafka container the broker is `kafka:19092`. The console span id and the Kafka span id differ because the header is a child span. The trace id is the same. Grafana's count can lag about a minute and is not a per-order series.

The README section `Follow one order` records those steps. One order for user `follow-signals` returned "The order is in process". The console line was `trace_id=50fbea9edc8be08a8667240dbf1956ff span_id=c843f9fea7c6abc7` on `requests=1 queries=7 updates=9`. The collector log has that trace id on `http post /` and on a `SaleTotals` log record. The Kafka header was `traceparent:00-50fbea9edc8be08a8667240dbf1956ff-4ca5066c0c1cd647-03` with the same JSON fields. Prometheus then listed one successful `POST /`, and Grafana's Orders dashboard reads `sum(http_server_requests_milliseconds_count)` from Prometheus. An older process was already on port 8080, so this check used port 8081 and that process was stopped afterward. `./gradlew test` passed.

Quick review: the Kafka command was after the submit, and `--offset latest` only prints a record produced after the consumer starts. The collector command was a one-shot dump that can run before the batched span export. The README now starts the consumer first and waits a few seconds before reading the collector log.

## Plan Change Log

## Review Triage Log

- Kafka `--offset latest` after the only submit: medium. A person following the section in order would miss the order they just placed. The consumer command now comes before the submit.
- Collector log read immediately: medium. `docker compose logs` prints what it has and exits, and the order span is exported on a short batch delay. The section now says to wait a few seconds after the page returns.

## Verification

**Commands:**
- `./gradlew test` -- expected: the sale tests still pass
- Place one successful order with Compose and the host process up, then read the collector log, the console line, one Kafka record with headers, and the Grafana HTTP request count -- expected: the same trace id is in the collector, on the console line, and in `traceparent`, and the Grafana count includes that order
