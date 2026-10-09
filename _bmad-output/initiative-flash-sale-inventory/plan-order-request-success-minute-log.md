---
title: 'Minute log of order requests and successes'
type: 'feature'
ticket: ''
created: '2026-10-08'
status: 'built'
route: 'oneshot'
route_source: 'auto'
review: 'quick'
review_source: 'pinned'
lenses_ran: ['quick']
review_loop_iteration: 0
context: []
baseline_revision: '24a452909c938fb0e0a99dc7a5fc41d398c4900e'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** A sale already logs a running request total, but nothing counts how many of those attempts succeeded, and neither figure is reported on a one-minute cadence.

**Approach:** In the inventory module, keep a running success total for orders that reach "in process", and write the running request and success totals to the application log once a minute. Leave the existing per-attempt line `requests=N queries=N updates=N` and its timing unchanged. Counts stay in process memory and reset only when the process restarts. Opening the page is not a request. A metrics endpoint does not replace the log.

</frozen-after-approval>

## Implementation Notes

Oneshot: about 90 lines across four files, extending the existing totals bean rather than a new pipeline.

- Reuse `src/main/java/com/flashsale/inventory/SaleTotals.java`. `request()` (lines 36–41) already increments `requests` and logs. `write()` (lines 52–54) is the per-attempt line and must stay byte-for-byte `requests={} queries={} updates={}`. Add `successes` and `success()` under the same lock. Add a scheduled read of `requests` and `successes` that logs a different line, `requests={} successes={}`, every 60 seconds, first line one minute after start, including when the numbers have not changed.
- Count a success only at the single `return OrderOutcome.inProcess()` in `src/main/java/com/flashsale/inventory/Orders.java` (line 87), via `SaleTotals`. That is the accepted deduction. Wait, refusal, sold out, not accepted, turn-away, and `GET /` do not increment it.
- Do not tally in `OrderPage`. `Admission.admit` already counts every request in `finally` (lines 40–42); do not add a second request tally.
- `@EnableScheduling` is absent (`FlashsaleApplication` lines 6–11). Enable it on the inventory side. No `logback.xml` under `src/main/resources`; the test appender is `src/test/resources/logback-test.xml` on logger `com.flashsale.inventory.SaleTotals`.
- `OrderPoolEventTest` parses only lines that are exactly `requests=(\\d+) queries=(\\d+) updates=(\\d+)` (`TOTALS`, line 57, `Matcher.matches`). The minute line must not match that pattern. `TotalsLogCapture` records `getFormattedMessage()`.
- Prove the success count and the minute line without waiting 60 seconds in `OrderPoolEventTest`: a slice test can override the interval. Do not change the existing totals assertions.

- `success()` only increments. It does not call `write()`, so the per-attempt line still appears once per `admit`.
- The minute line is `requests=N successes=N`, from `SaleTotals.logInterval()`, so `OrderPoolEventTest`'s `Matcher.matches()` on `requests=N queries=N updates=N` ignores it.
- `flashsale.sale-totals.interval-ms` defaults to 60000 in `application.properties` and in the annotation. `initialDelayString` uses the same value, so the first line is one minute after start.
- `@EnableScheduling` is `SaleTotalsSchedule` in the inventory package.
- `SaleTotalsIntervalTest` sets the interval to 200ms and checks that a blank user id raises requests only, an in-process order raises both, a following wait raises requests only, and `GET /` changes neither.
- Files: `SaleTotals.java`, `Orders.java`, `SaleTotalsSchedule.java`, `application.properties`, `SaleTotalsIntervalTest.java`.
- `./gradlew test --offline` passed. No surprise in the sale rules. Shutdown still logs the existing per-attempt line when the publisher runs.
- Review: a success is counted only after `Orders.place` returns, which is after the transaction commits. `Admission.admit` passes that result into `SaleTotals.request(boolean)`, and that method increments requests and successes under one lock before `write()`. A failed commit leaves successes unchanged and still counts the request. `Orders` no longer depends on `SaleTotals`.
- The minute-log check lives on `OrderPoolEventTest` with `flashsale.sale-totals.interval-ms=200`, so one application context owns the counters and the static log list. `parseInterval` fails if a minute line has more successes than requests.

## Plan Change Log

## Review Triage Log

- high — pre-commit success. `Orders.place` incremented `successes` before the `@Transactional` commit. A failed commit rolled the order back and still left the minute line high. Patched: count a success only when `place` has returned `inProcess`.
- medium — successes ahead of requests. `success()` and `request()` took the lock separately, and `request()` ran later in `Admission.admit`'s outer finally, so one tick could log `successes > requests`. Patched: both counters move in `SaleTotals.request(boolean)` before the per-attempt line. The interval test rejects a minute line where successes exceed requests.
- medium — shared log list across cached Spring contexts. `SaleTotalsIntervalTest` could treat `OrderPoolEventTest`'s minute line as its baseline. Patched: deleted that class and run the check in `OrderPoolEventTest`, whose context is the only one scheduling the line.

## Verification

**Commands:**
- `./gradlew test` -- expected: the suite passes, including the new success and minute-log assertions and the existing request, query, and update log assertions
