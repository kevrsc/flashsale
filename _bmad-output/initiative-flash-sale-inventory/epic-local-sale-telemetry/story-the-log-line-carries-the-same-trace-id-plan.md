---
title: 'The log line carries the same trace id'
type: 'feature'
ticket: '4'
created: '2026-10-12'
status: 'built'
route: 'full'
route_source: 'auto'
review: 'quick'
review_source: 'pinned'
lenses_ran: ['quick']
review_loop_iteration: 0
baseline_revision: 'f2b7aa6b5125c38770e7e17eeee65d459c1a03e2'
context:
  - '{project-root}/_bmad-output/initiative-flash-sale-inventory/architecture-flash-sale-inventory/architecture-flash-sale-inventory.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** A successful order's console line does not show that order's trace id and span id, and the collector never receives the log. Traces and metrics already export. Logs do not.

**Approach:** Print Micrometer's `traceId` and `spanId` on the console under the labels `trace_id` and `span_id`, and export the same log to the collector through the OpenTelemetry logback appender. The sale totals log stays the tally.

## Boundaries & Constraints

**Always:** The Boot process stays on the host and posts logs to `http://localhost:4318/v1/logs`. The console pattern reads `%X{traceId}` and `%X{spanId}`. `OpenTelemetryAppender.install` runs on the Boot `OpenTelemetry` bean. The appender is `opentelemetry-logback-appender-1.0` `2.26.0-alpha`. The collector logs pipeline uses the existing debug exporter at detailed verbosity. A collector failure does not change the shopper outcome or the Kafka send. The sale totals lines stay.

**Never:** Loki, a log appender that replaces the console, a second logging pipeline, reading MDC keys named `trace_id` or `span_id`, appender `2.32.0-alpha`, a change to the sale totals message text, and host config that uses Compose DNS names.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| One successful order | Collector is up. One order is placed with a known trace id. | A log line for that order shows that trace id and span id under `trace_id` and `span_id`. The sale totals lines are still present. The collector has received that log. | No error expected |
| Collector logs path down | The order is placed while nothing accepts `/v1/logs`. | The shopper still gets the order outcome. The sale totals lines still log. | Log export logs a failure and does not fail the request |

</frozen-after-approval>

## Code Map

- `src/main/resources/application.properties:3` — trace and metrics export are already set. Add `management.opentelemetry.logging.export.otlp.endpoint=http://localhost:4318/v1/logs`. That property is what turns on Boot's `OtlpHttpLogRecordExporter`. Boot then puts the `SdkLoggerProvider` on the `OpenTelemetry` bean. It does not attach a logback appender.
- `build.gradle:24` — `spring-boot-starter-opentelemetry` stays and already brings the OTLP log exporter. Add `io.opentelemetry.instrumentation:opentelemetry-logback-appender-1.0:2.26.0-alpha` only. Do not add a second metrics registry. Let the Boot BOM keep `io.opentelemetry` at 1.62.0.
- `src/main/java/com/flashsale/FlashsaleApplication.java:6` — the Boot application. Add a small configuration beside it whose constructor takes `OpenTelemetry` and calls `OpenTelemetryAppender.install`. Do not build a second SDK.
- `src/main/resources/logback-spring.xml` — new file. There is no main logback file today. Keep a console appender. Its pattern prints `trace_id=%X{traceId:-}` and `span_id=%X{spanId:-}`. Add `OpenTelemetryAppender` on the root logger next to the console. Do not give `com.flashsale.inventory.SaleTotals` its own logger here. Boot already registers `Slf4JEventListener`, which writes MDC keys `traceId` and `spanId` when a span is in scope.
- `src/test/resources/logback-test.xml:4` — test console pattern and the `SaleTotals` logger that feeds `TotalsLogCapture`. Leave both. Tests must keep using this file so the sale totals assertions stay on the message text.
- `src/main/java/com/flashsale/inventory/SaleTotals.java:53` — `requests={} successes={}` and `requests={} queries={} updates={}`. Do not change those messages.
- `src/test/java/com/flashsale/TotalsLogCapture.java:14` — stores `getFormattedMessage()` only. Keep that list. Also keep the MDC `traceId` and `spanId` from each event so a test can read them without changing the message assertions.
- `src/test/java/com/flashsale/OrderPoolEventTest.java:108` — `orderEventCarriesTheRequestTrace` already posts trace id `4bf92f3577b34da6a3ce929d0e0e4736` and asserts the Kafka header. Assert a `SaleTotals` event from that request carries the same `traceId` and a non-blank `spanId`. Format that event with the production pattern and assert the line contains `trace_id=` plus that id and `span_id=`. Do not change `assertOneEvent` or the totals regexes.
- `otel-collector-config.yaml:15` — traces stay on debug detailed, metrics stay on the Prometheus exporter. Add a logs pipeline from `otlp` to `debug`. Do not publish a new port. The OTLP receiver on `4318` already accepts logs once the pipeline exists.

## Tasks & Acceptance

**Execution:**
- [ ] `build.gradle` -- add `opentelemetry-logback-appender-1.0` `2.26.0-alpha`. -- This is the appender AD-11 names. A newer alpha wants a newer OpenTelemetry API than Boot manages.
- [ ] `src/main/resources/application.properties` -- set the OTLP logs endpoint to `http://localhost:4318/v1/logs`. -- Boot creates the log exporter only when this is set.
- [ ] `src/main/resources/logback-spring.xml` -- console pattern with `trace_id` and `span_id`, plus `OpenTelemetryAppender` on the root logger. -- The console line and the export share one logging setup.
- [ ] A configuration class beside `FlashsaleApplication` -- call `OpenTelemetryAppender.install` with the Boot `OpenTelemetry` bean. -- The appender has no SDK until this runs.
- [ ] `otel-collector-config.yaml` -- add the logs pipeline to the debug exporter. Keep traces and metrics. -- This is how the collector shows the log.
- [ ] `TotalsLogCapture` and `orderEventCarriesTheRequestTrace` -- record MDC on the captured event and assert the trace id, the span id, and the production pattern labels. Leave the sale totals message tests alone. -- This covers the console half of the matrix. The collector half is the verification command, because `./gradlew test` must pass when the collector is down.

**Acceptance Criteria:**
- Given one successful order with a known trace id, when its log line is read, then that line shows the same trace id and a span id, and the sale totals lines are still present.
- Given the collector is up, when that order is placed, then the collector log contains the log signal for that trace id.
- Given the collector is not accepting logs, when an order is placed, then the shopper outcome is unchanged and `./gradlew test` still passes.

## Implementation Notes

The request's sale totals line carries that order's trace. The production pattern is `trace_id=%X{traceId:-} span_id=%X{spanId:-}`. `./gradlew test` passed. With the collector up, an order using trace `c0ffee00c0ffee00c0ffee00c0ffee00` printed that id and span `3f5a780fe28c4724` on `requests=1 queries=12 updates=138`, and the collector debug log had that log record. With the collector stopped, the shopper still got "The order is in process", the sale totals line still logged, and the app logged `Failed to export logs`. The appender also brings `opentelemetry-api-incubator` `1.60.1-alpha` beside API 1.62.0. The live export succeeded with that mix. Quick review found nothing to change.

## Plan Change Log

## Review Triage Log

Quick review, one pass. Verdicts: high 0, medium 0, low 0, false 0, maybe-false 0. No findings.

## Design Notes

Boot's `Slf4JEventListener` already writes `traceId` and `spanId` while the request span is current. `SaleTotals.write` runs on that request, so its event is the line that carries the order's ids. The scheduled interval line can belong to another span; do not use it as the order's line. The test logger stays isolated so `requests=`, `queries=`, and `updates=` keep matching. The production pattern is applied to the captured event in the test, which is how the labels are checked without starting the collector.

## Verification

**Commands:**
- `./gradlew test` -- expected: the sale tests pass, including the trace id and span id on the order's log event, with the logs URL set
- After `docker compose up`, place one successful order, then read `docker compose logs otel-collector` -- expected: a log record carries that order's trace id, and the console line shows `trace_id` and `span_id`

**Manual checks (if no CLI):**
- Read the application console for one order. The line shows `trace_id` and `span_id`. The sale totals lines are still there.
