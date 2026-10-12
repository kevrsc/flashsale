# Rubric review — architecture spine

Verdict: **Revise**

The spine was not edited. This is a good-spine checklist review of the initiative spine after AD-8 and AD-11 moved the sale tally off the running-total log and onto two Micrometer counters.

- Spine: `architecture-flash-sale-inventory/architecture-flash-sale-inventory.md` (final, altitude initiative, updated 2026-10-12)
- Memlog: `architecture-flash-sale-inventory/.memlog.md`
- Driving sources: `prd-flash-sale-inventory/prd-flash-sale-inventory.md` (SM-1, SM-2, SM-C1); spec companion `spec-flash-sale-inventory/spec-flash-sale-inventory.md` (CAP-7 and the log constraint)
- Telemetry scope still on the old tally: `epic-local-sale-telemetry/epic-local-sale-telemetry.md` (TEL-3)
- Brownfield: `SaleTotals`, `Admission.admit`, `build.gradle`, `application.properties`

AD-8 and AD-11 agree with each other. The inventory module records `sale.attempts` (`result`) and `sale.statements` (`kind`) on the one `MeterRegistry`, the request total is the sum of attempts, and the log is no longer the tally. One scrape-name gap remains between that instrument and the Grafana panel. The spec, PRD, and telemetry epic still describe the log tally; that is source drift, not a reason to put the log back.

## Checklist

| Check | Result |
| --- | --- |
| Fixes the real divergence points one level down and misses none | **Gap.** The two counters, the closed `result` and `kind` sets, one increment per admit, success only after commit, and "the log does not print running totals" close the tally split. The Prometheus series the dashboard queries is not pinned, so a dashboard story and a collector story can still miss each other. |
| Every AD Rule is enforceable and actually prevents its stated divergence | **Gap.** AD-8 holds. AD-11 prevents a second registry, a custom totals-log interval, the HTTP timer as the tally, and a collector failure changing the shopper outcome. Its dashboard sentence names `sale.attempts`, which the default Prometheus export does not scrape. |
| Nothing under Deferred could let two units diverge | **Pass.** Exemplars are optional metadata on the same counters. The other items leave the shared behavior fixed and name when to revisit. The totals-log wording deferral is gone, which matches the new rule. |
| Named tech is verified-current | **Pass.** Rechecked 2026-10-12. The appender pin is the line that fits Boot's OpenTelemetry, not the newer line that requires API 1.66.0. |
| Brownfield: ratify, do not contradict the codebase | **Pass.** `SaleTotals` still logs running totals. AD-8 forbids those lines. That lag is expected and is not a contradiction to fix by restoring the log tally. The OTLP URLs, sampling, appender coordinate, and outbox observability module already match the spine. |
| Covers the spec capabilities | **Source drift.** CAP-1 through CAP-6 and CAP-8 are unchanged. CAP-7's "totals are visible" is met by the counters and the Grafana dashboard. The spec constraint, the spec success signal, PRD §6.1 and §7, and TEL-3 still say the log is the tally and that a metrics view must not replace it. Do not revert the spine to match them. |
| Parent spine inherited without a local AD weakening it | **Pass.** This is the initiative spine. No parent. The amendments do not loosen the outbox commit, the module direction, or the customer-limit guards. |
| Every dimension this altitude owns is decided, deferred, or an open question | **Pass.** Deployment, infra, and local observability stay decided. Process supervision, health, alerting, and a hosted environment stay deferred with a revisit condition. |

## Findings

### 1. Medium — The dashboard names the Micrometer instrument, and Prometheus will not scrape that name

AD-8 names the counters `sale.attempts` and `sale.statements`. AD-11 says Grafana's provisioned sale dashboard includes those names by `result` and `kind`, and that Grafana's datasource is Prometheus scraping the collector on `8889`.

The core image `otel/opentelemetry-collector:0.162.0` includes `prometheusexporter`. Its default translation is `UnderscoreEscapingWithSuffixes`. A dimensionless counter `sale.attempts` is scraped as `sale_attempts_total` with label `result`. `sale.statements` is scraped as `sale_statements_total` with label `kind`. Copying `sale.attempts` into the dashboard query returns no series.

The exporter also accepts `translation_strategy` values that omit `_total` or keep the dotted name (`UnderscoreEscapingWithoutSuffixes`, `NoUTF8EscapingWithSuffixes`, `NoTranslation`). Prometheus 3 can ingest a UTF-8 name. One story can leave the default and query `sale_attempts_total`. Another can turn translation off and query `sale.attempts`. Both can claim they followed AD-11. The HTTP-timer prevent does not stop that split.

SM-1 is a total since process start, which is the cumulative counter. A panel that only draws `rate()` is a different number. Disposition: **autofix**. Keep the Micrometer names. State that the collector leaves Prometheus translation at the default, and that the dashboard queries `sale_attempts_total` by `result` and `sale_statements_total` by `kind` as cumulative totals. Leave OTLP aggregation temporality at the Boot cumulative default so the scrape stays a counter.

### 2. Source drift — The spec, the PRD, and TEL-3 still say the log is the tally

Do not revert AD-8 or AD-11. The spine's own sentences agree: the counters are the tally, and the application log does not print running request, success, query, or update totals.

These sources still describe the old channel:

- Spec constraint: request totals and database query and update totals are visible in the service log, and a metrics endpoint does not replace that log.
- Spec success signal: the log shows the request total and the database query and update totals for that sale.
- PRD §6.1 and §7: logging is how the request total and the query and update counts are visible.
- TEL-3, and the epic decision under it: the sale totals log still shows requests, queries, and updates, and a metrics view does not replace that log.

CAP-7's intent, that the builder can see attempt counts and query and update counts, is what the counters and the dashboard carry. The log-channel sentences are the drift. Disposition: **source drift**. Update those sources in their own pass. The spine stays on the counters.

## Deferred

None of these let two units pick incompatible behavior. The shared rule is already in an AD. Only the number, the text, or the later environment is open.

- Admission-gate concurrency number. Turn-away stays a retry signal, not a shopper outcome.
- Order-id text format. The id stays unique, stored in the commit, and never reused.
- Starting pool quantity. It lives only on the seeded item row.
- Exemplars from a sale counter to a trace. The counters stay the tally either way. Revisit when Grafana shows traces.
- HTTP path of the form. It stays inside the page module.
- Process supervision, health checks, and alerting on an unpublished outbox row. Revisit when the process is no longer started by hand.
- A hosted environment. Revisit when a launch target exists.
- Tempo, Loki, and a hosted log aggregator. Until then the collector debug exporter is the trace and log view.
- Restock and clearing the sold-out hint. Revisit only if the pool can increase after zero.

## What holds

AD-8 is enforceable on the tally itself. Inventory alone increments the two counters on the single `MeterRegistry`. `result` is the closed set `in_process`, `wait`, `refused`, `sold_out`, `not_accepted`, `turned_away`. One `admit` increments `sale.attempts` once, a page open does not, and the request total is the sum. `in_process` is the success, and that increment is only after the order transaction commits, which matches `Orders.place` returning from its transaction before `Admission.admit` records the outcome. `sale.statements` counts inventory JDBC and outbox-publisher statements, including zero-row changes. `SELECT` is `query`. `INSERT`, `UPDATE`, and `DELETE` are `update`. The counters live in the one process, reset on restart, and do not make a sale successful. A user id or an order id is not a sale-meter attribute. The HTTP server timer and JDBC auto-metrics are not the tally.

The running-total log is forbidden in both AD-8 and AD-11, including the interval line. That removes the old deferral about totals-log wording. Trace correlation stays on the request span, the console `trace_id` and `span_id` labels, and the Kafka `traceparent` header.

`SaleTotals` still logs `requests={} queries={} updates={}` and `requests={} successes={}`, and `flashsale.sale-totals.interval-ms` is still set. That is the code lag behind the new rule. It is not a brownfield contradiction, and it is not fixed by putting the log tally back into the spine.

AD-1 through AD-7, AD-9, and AD-10 are unchanged in force. AD-5 names `namastack-outbox-observability` `1.7.3`, which is already on the classpath, as the way the after-commit send stays on the order's trace. AD-11 names the three OTLP HTTP endpoints, samples every trace in this local environment, and says a down collector does not change the shopper outcome or whether the order event is sent.

## Versions checked 2026-10-12

| Pin | Check |
| --- | --- |
| `opentelemetry-logback-appender-1.0` `2.26.0-alpha` | POM depends on OpenTelemetry API `1.60.1`. Matches the spine and `build.gradle`. |
| `opentelemetry-logback-appender-1.0` `2.32.0-alpha` | Current instrumentation line (2026-10-04). POM depends on OpenTelemetry API `1.66.0`. Correctly not used. Boot 4.1.1 manages `1.62.0`. |
| Spring Boot `4.1.1` OpenTelemetry | `spring-boot-dependencies` and `spring-boot-starter-opentelemetry` manage `io.opentelemetry` `1.62.0` and bring `micrometer-registry-otlp` `1.17.1`. |
| `otel/opentelemetry-collector:0.162.0` | Core distro manifest includes `prometheusexporter` and `debugexporter`. Install docs still pull this tag. Default Prometheus translation is `UnderscoreEscapingWithSuffixes`. |
| Prometheus `3.15.0` / `prom/prometheus:v3.15.0` | Latest release on prometheus.io, 2026-09-24. |
| Grafana `13.2.3` / `grafana/grafana:13.2.3` | Latest stable on the Grafana download page, 2026-09-29. |
| Existing pins (Boot 4.1.1, Modulith 2.1.1, Namastack 1.7.3, PostgreSQL 18.6, Kafka 4.3.1, Java 17, Flyway 12.4.0) | Still the brownfield coordinates. |
