---
title: 'Sale tally on Micrometer counters'
type: 'feature'
ticket: ''
created: '2026-10-12'
status: 'built'
route: 'full'
route_source: 'auto'
review: 'quick'
review_source: 'pinned'
lenses_ran: ['quick']
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/initiative-flash-sale-inventory/architecture-flash-sale-inventory/architecture-flash-sale-inventory.md'
baseline_revision: 'a967bfb1ef2693f7d6a3e1e8c427e800eab11836'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Requests, successes, queries, and updates are still running totals in the application log. The spec, the PRD, and the tests treat that log as the tally, while AD-8 now requires two Micrometer counters.

**Approach:** Record the tally on `sale.attempts` and `sale.statements` in the existing MeterRegistry, and stop printing those counter values. Align the spec and the PRD with that. Grafana's sale panels read the Prometheus series for those counters.

## Boundaries & Constraints

**Always:** Inventory alone registers the two counters on the Boot `MeterRegistry`. One `admit` increments `sale.attempts` once. `result` is `in_process` only after `place` returns that outcome and before `admit` returns, `wait`, `refused`, `sold_out` (including the hint), `not_accepted` for a blank user id or a non-positive quantity, `turned_away` for the gate, and `unmatched` for the null-message miss. `sale.statements` uses `kind=query` for `SELECT` and `kind=update` for every other inventory or outbox statement, including zero rows. Opening the page does not count. Counters reset on process restart. The Kafka `traceparent` header and the console pattern's `trace_id` and `span_id` stay.

**Never:** A second registry, an OpenTelemetry Meter, user id or order id tags, the HTTP timer renamed or used as the sale tally, a log line of these counter values, a log fallback when the collector is down, and a custom export step. Do not edit finished story plans.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Accepted order | One order commits in process, then the outbox sends | `sale.attempts` `in_process` +1. `sale.statements` rises for the order SQL and again for the publisher SQL | No error expected |
| Not a success | Blank user id, wait, refusal, sold-out hint, or turn-away | Attempts +1 on that result. Hint, blank id, and turn-away do not raise statements. Wait, refusal, and a locked sold-out read do | No error expected |
| Page open | `GET /` | Neither counter changes | No error expected |
| Miss | `place` returns the null-message unmatched outcome | Attempts +1 with `unmatched`. Not `in_process` | No error expected |

</frozen-after-approval>

## Code Map

- `src/main/java/com/flashsale/inventory/SaleTotals.java` — owns the log lines and `logInterval`. Replace with the two counters. Keep `keyword` for the SELECT split. Drop the logger, the lock, and the longs.
- `src/main/java/com/flashsale/inventory/Admission.java:21-45` — `request(boolean)` only knows success. Pass the result token for every return, including early sold-out, not-accepted, and turn-away. `in_process` only after `place` returns.
- `src/main/java/com/flashsale/inventory/CountingNamedParameterJdbcTemplate.java:20-24` and `CountingJdbcClient.java:41-42` — already call `inventoryStatement` and `publisherStatement`. Both become statement counts. Publisher must not log.
- `src/main/java/com/flashsale/inventory/Orders.java:100` — `unmatched()` is the null-message miss. Do not change that outcome.
- `src/main/java/com/flashsale/inventory/SaleTotalsSchedule.java` — only scheduling user. Delete it with `logInterval`.
- `src/main/resources/application.properties:9` — `flashsale.sale-totals.interval-ms`. Remove it. Leave the OTLP metrics URL and the export step unset.
- `src/test/java/com/flashsale/OrderPoolEventTest.java` — parses `requests=`, `queries=`, `updates=`, `successes=` via `TotalsLogCapture`. Read `MeterRegistry` instead. `orderEventCarriesTheRequestTrace` must keep the Kafka header check and stop waiting on a SaleTotals line.
- `src/test/resources/logback-test.xml:7-10` and `src/test/java/com/flashsale/TotalsLogCapture.java` — drop the SaleTotals appender when nothing logs there. Keep `logback-spring.xml` pattern.
- `grafana/dashboards/orders.json:49` — only `sum(http_server_requests_milliseconds_count)`. Add sale panels for `sum(sale_attempts_total)` and `sum by (kind) (sale_statements_total)`. Leave the HTTP panel under its own title.
- Spec `spec-flash-sale-inventory.md:61` and `:75`, PRD `prd-flash-sale-inventory.md:141`, `:146`, `:154`. Say the counters are how the totals are visible.
- `README.md:128-130` and `:159` — stop telling the operator to read `requests=N queries=N updates=N`.
- AD-8 rule — add `unmatched` to the closed `result` set. Do not reopen the rest of the spine.

## Tasks & Acceptance

**Execution:**
- [ ] `architecture-flash-sale-inventory.md` AD-8 -- add `unmatched` for the null-message miss. -- The closed set otherwise has no token for `Orders.unmatched()`.
- [ ] `SaleTotals.java`, `Admission.java`, delete `SaleTotalsSchedule.java`, `application.properties` -- register the counters and remove the log tally and its scheduler. -- This is the tally.
- [ ] `OrderPoolEventTest.java`, `logback-test.xml`, `TotalsLogCapture.java` -- assert the matrix through `MeterRegistry`. Keep the Kafka trace check. -- The log lines will be gone.
- [ ] `orders.json` -- add the two sale panels. -- Grafana must not treat the HTTP timer as the sale tally.
- [ ] Spec, PRD, and `README.md` -- name the counters as the visible totals. -- Those sentences still require the log.

**Acceptance Criteria:**
- Given the suite, when `./gradlew test` runs, then the matrix above passes and no test reads `requests=`, `queries=`, `updates=`, or `successes=` from a log line.
- Given the spec and the PRD, when their tally sentences are read, then they name `sale.attempts` and `sale.statements` and do not require those totals in the log.

## Implementation Notes

- `SaleTotals` registers `sale.attempts` and `sale.statements` on the Boot `MeterRegistry`. `Admission` maps each return to one result token. `in_process` is set only after `place` returns.
- `unmatched` was added to AD-8 for the null-message miss.
- The log tally, `SaleTotalsSchedule`, `TotalsLogCapture`, and `flashsale.sale-totals.interval-ms` are gone. The Kafka `traceparent` check remains. The console pattern is unchanged.
- An `admit` that throws before a result token does not increment `sale.attempts`. The closed set has no error token.
- The no-statement checks read `SaleTotals.inventoryStatementCount`, which the publisher does not increment. `sale.statements` still counts publisher SQL.
- Prometheus was not queried live in the implementation pass.

## Plan Change Log

## Review Triage Log

- medium — `OrderPoolEventTest.quietStatements` and the equality checks after a hint, a blank id, a turn-away, `GET /`, and the blank-id block of the end-to-end sale. `sale.statements` is process-wide and the outbox publisher increments it on its poll. The helper only needs 100ms of stillness, then the action can overlap the next poll and fail even though that path runs no inventory SQL. The suite passed once; the race remains. Patch.
- low — `reviews/review-reconcile-sources.md` and `reviews/review-rubric.md` still describe the pre-implementation log tally. Rejected: those files are the architecture gate's notes from before this code, and rewriting that history is not a correction to the sale. The spine, spec, and PRD are the current contract.
- low — `reviews/review-adversary.md` and the rubric's "What holds" section describe the AD-8 text from before `unmatched` and the commit timing were written in. Rejected: same gate notes, same reason. `Admission.resultOf` already implements the current AD-8 mapping.

## Design Notes

`unmatched` stays a shopper outcome with a null message. It is a seventh `result` because every `admit` still counts once. Micrometer counters are thread-safe, so the old lock goes away with the paired log line. Prometheus names follow the HTTP timer this app already exports: `sale_attempts_total` and `sale_statements_total`.

## Verification

**Commands:**
- `./gradlew test` -- expected: the suite passes, including attempt results, statement deltas, and the Kafka trace header

**Manual checks (if no CLI):**
- With Compose up, place one in-process order and query Prometheus for `sale_attempts_total` and `sale_statements_total`. The attempt series includes that order. The HTTP panel is still present and is not the only panel.
