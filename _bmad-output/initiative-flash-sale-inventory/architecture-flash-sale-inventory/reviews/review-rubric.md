# Rubric review — architecture spine

Verdict: **Revise**

The spine was not edited. This is a good-spine checklist review of the updated initiative spine. The update amends AD-5, AD-8, and AD-10 and adds AD-11. CAP-1 through CAP-8 are unchanged, and the sale totals log is still the tally.

- Spine: `architecture-flash-sale-inventory/architecture-flash-sale-inventory.md` (final, altitude initiative, updated 2026-10-09)
- Memlog: `architecture-flash-sale-inventory/.memlog.md`
- Driving spec: `spec-flash-sale-inventory/spec-flash-sale-inventory.md` (CAP-1 through CAP-8)
- Telemetry scope: `epic-local-sale-telemetry/epic-local-sale-telemetry.md` (TEL-1 through TEL-6)
- Brownfield: the running service (`build.gradle`, `compose.yaml`, `application.properties`, `inventory` and `page`)
- Lint: `lint_spine.py` reported `ok: true`, no mechanical findings

The order JSON, the sale log, the single host process, and the three new Compose services are real contracts. Handoff waits on the high item: AD-5 requires the after-commit send to stay in the order's trace, and the only mechanism it allows does not do that on this outbox path.

## Checklist

| Check | Result |
| --- | --- |
| Fixes the real divergence points one level down and misses none | **Gap.** Module boundaries, the order payload, the sale log beside metrics, and the local collector topology are fixed. The request trace does not survive the outbox send under the rule as written. Export sampling and a down collector are still open. |
| Every AD Rule is enforceable and actually prevents its stated divergence | **Gap.** AD-8 and AD-10 hold. AD-5's same-trace prevent does not. AD-11's destinations are right; the Prevent line and the Boot switches that turn export on are not. |
| Nothing under Deferred could let two units diverge | **Pass.** Each item leaves the behavior fixed and names when to revisit. |
| Named tech is verified-current | **Pass.** Checked 2026-10-09 against the current releases, not only the memlog. |
| Brownfield: ratify, do not contradict the codebase | **Pass.** The update extends the existing Modulith outbox, the console totals, and the localhost Compose ports. |
| Covers the spec capabilities | **Pass.** CAP-1 through CAP-8 stay on AD-1 through AD-9. The sale totals log remains the CAP-7 tally. |
| Parent spine inherited without a local AD weakening it | **Pass.** This is the initiative spine. No parent. The amendments do not loosen the order JSON, the outbox commit, or the sale log. |
| Every dimension this altitude owns is decided, deferred, or an open question | **Pass.** Deployment, infra, and local observability are decided. Process supervision, health, alerting, and a hosted environment are deferred with a revisit condition. The sampling and export-failure gaps sit inside AD-11; they are not a silent dimension. |

## Findings

### 1. High — AD-5's KafkaTemplate switch does not keep the outbox send in the order's trace

AD-5 prevents a new root trace on the send. The rule says `spring.kafka.template.observation-enabled=true` makes the single `KafkaTemplate` write W3C `traceparent`, that application code must not add that header through a second producer, a producer interceptor, or `EventExternalizationConfiguration.headers`, and that the externalization thread propagates the request trace.

Spring Modulith 2.1.1's Namastack Kafka handler is `(payload, metadata) -> externalizer.externalizeBlocking(payload)`. The metadata map, which is where a stored `traceparent` would sit, is discarded. The transport then copies headers only from `EventExternalizationConfiguration.getHeadersFor(payload)`, the hook the rule forbids, and calls `KafkaOperations.send`. Observation writes `traceparent` from the span that is current on that call.

The brownfield send is not on the request thread. `Orders` publishes `OrderAccepted` inside the transaction. `spring.modulith.events.externalization.mode=outbox` records that event through Namastack, and a later poller sends it. `namastack-outbox-starter-jdbc` 1.7.3, which `spring-modulith-starter-namastack` 2.1.1 already pulls, does not depend on `namastack-outbox-observability`. That separate module, which exists at 1.7.3, is what stores the active W3C context on the outbox row and restores a child span around the handler. It is not in the stack and not on the classpath. Without it, the poller has no request span, and the mandated switch writes a new root `traceparent`.

Two telemetry stories can still diverge: one sets only `observation-enabled` and ships a new trace; another adds `namastack-outbox-observability` 1.7.3 so the handler runs inside the restored span and the same template writes that trace. The rule forbids both the new root and the header hooks that could copy a stored `traceparent`. Disposition: **discuss**. Continuing the trace means requiring the observability module so the existing `KafkaTemplate` send sees the restored span, or allowing a header taken from the stored context. Those are different mechanisms.

### 2. Medium — AD-11 names the URLs and leaves Boot's export switches off

AD-11 prevents a second exporter beside Micrometer, and a protocol other than OTLP. The rule says Micrometer exports metrics to `http://localhost:4318/v1/metrics` and traces to `http://localhost:4318/v1/traces`, and that `opentelemetry-logback-appender-1.0` `2.32.0-alpha` exports logs to `http://localhost:4318/v1/logs` after `OpenTelemetryAppender.install` on the Boot `OpenTelemetry` bean.

Spring Boot 4.1.1 splits those pipes:

- `micrometer-registry-otlp`, already inside `spring-boot-starter-opentelemetry`, exports metrics. The URL property is `management.otlp.metrics.export.url`. The registry does not export traces.
- Traces leave through the starter's `OtlpHttpSpanExporter` only after `management.opentelemetry.tracing.export.otlp.endpoint` is set. HTTP is the default transport.
- The logback appender does not open HTTP. It writes into the Boot `OpenTelemetry` bean. Boot does not configure log export until `management.opentelemetry.logging.export.otlp.endpoint` is set. `OpenTelemetryAppender.install` matches the Boot 4.1 logger docs, and `2.32.0-alpha` is the current Maven Central line (there is no stable line).

The sampler default is `parent-based-trace-id-ratio` with `management.tracing.sampling.probability=0.1`, and unsampled spans are not exported. One story can leave that default. Another can set probability `1` or `always-on`. The collector then sometimes has this order's trace and sometimes does not. TEL-4's "a person can read that order's trace" follows the second choice.

The Prevent line also fights the Rule. Logs are required to go through the OpenTelemetry appender, which is the second exporter TEL-4 asks for, and traces go through the OTel span exporter, not the Micrometer metrics registry. A reader who obeys the Prevent drops the log export. Disposition: **autofix**. Name the metrics URL, the tracing endpoint, and the logging endpoint, all OTLP over HTTP. For this one local environment, sample every trace. Rewrite the Prevent so it keeps one metrics registry, one trace exporter, and one log appender, and still bars a second protocol, an agent, and a metrics view in place of the sale log.

### 3. Medium — A down collector can still change the shopper outcome

AD-1 already says the Kafka send is after the outcome returns, and a broker delay does not change that outcome. AD-11 puts metrics, traces, and logs on the same host process and does not say where export sits relative to that transaction.

A telemetry story can export inside the AD-1 transaction, block the page on the collector, or fail the order when `localhost:4318` is down. The finished sale and this epic share the process. The sale totals lines can also disappear if a story treats a failed export as a failed request. Disposition: **autofix**. State that OTLP export runs outside the AD-1 transaction, and that collector delay or failure leaves the shopper outcome and the AD-8 console lines unchanged.

## Deferred

None of these let two units pick incompatible behavior. The shared rule is already in an AD; only the number, the text, or the later environment is open.

- Admission-gate concurrency number. Turn-away stays a page retry, not a shopper outcome.
- Order-id character format. The id stays a unique opaque string, stored in the commit, and never reused.
- Starting pool quantity. It lives only on the seeded item row.
- Wording of the totals log line. The inventory module still owns the three totals, and AD-11 already fixes the `trace_id` and `span_id` labels.
- HTTP path of the form. It stays inside the page module.
- Process supervision, health checks, and alerting on an unpublished outbox row. Revisit when the process is no longer started by hand.
- A hosted environment. Revisit when a launch target exists.
- Tempo, Loki, and a hosted log aggregator. Until then the collector debug exporter is the trace and log view.
- Restock and clearing the sold-out hint. Revisit only if the pool can increase after zero.

## What holds

AD-8 keeps the inventory log as the sale tally. Micrometer and the OpenTelemetry log export may sit beside `requests`, `queries`, and `updates`. They do not replace those lines. That matches the spec constraint, CAP-7, and `SaleTotals`, which already writes those totals from the inventory module only.

AD-10 adds `otel/opentelemetry-collector:0.162.0`, `prom/prometheus:v3.15.0`, and `grafana/grafana:13.2.3` to the existing host-plus-Compose layout. The Boot process stays on the host and uses localhost. Compose services call each other by service name. The current `compose.yaml` already publishes PostgreSQL `localhost:5432` (`flashsale` / `flashsale` / `flashsale`) and Kafka `localhost:9092`, and creates `inventory.order-accepted`. The core collector image at 0.162.0 includes the Prometheus exporter, so port 8889 is a real scrape target, and the debug exporter is in that same image.

AD-11's shape is right where it is specific: three OTLP HTTP paths, the console appender kept, Micrometer MDC keys `traceId` and `spanId` printed as `trace_id` and `span_id`, Grafana reading Prometheus only, and Tempo and Loki left out. The order event's four JSON properties and message key are unchanged. `traceparent` is a Kafka header, not a JSON property.

CAP-1 through CAP-8 remain covered by AD-1 through AD-9. There is no parent spine. The new sentences do not weaken the outbox commit, the module direction, or the customer-limit guards.

## Versions checked 2026-10-09

| Pin | Check |
| --- | --- |
| `otel/opentelemetry-collector:0.162.0` | Current stable collector image. `0.163` is nightly. The core manifest includes the Prometheus exporter and the debug exporter. |
| Prometheus `3.15.0` / `prom/prometheus:v3.15.0` | Latest release, 2026-09-24. |
| Grafana `13.2.3` / `grafana/grafana:13.2.3` | Stable release, 2026-09-29. |
| `opentelemetry-logback-appender-1.0` `2.32.0-alpha` | Current Maven Central version. No non-alpha line. |
| `spring-boot-starter-opentelemetry` on Boot 4.1.1 | Published. It brings `micrometer-registry-otlp` and the OTLP span exporter. |
| Existing pins (Boot 4.1.1, Modulith 2.1.1, Namastack JDBC 1.7.3, PostgreSQL 18.6, Kafka 4.3.1, Java 17) | Still the brownfield coordinates. `namastack-outbox-observability` 1.7.3 is on Maven Central and is not pulled by the JDBC starter. |
