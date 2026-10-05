---
title: 'The log shows request and database totals'
type: 'feature'
ticket: '7'
created: '2026-10-05'
status: done
baseline_revision: '675d9458fefb956a82ceae542bf54055d8d5f534'
route: 'full'
route_source: 'auto'
review: 'quick'
review_source: 'pinned'
lenses_ran: [quick]
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/initiative-flash-sale-inventory/architecture-flash-sale-inventory/architecture-flash-sale-inventory.md'
  - '{project-root}/_bmad-output/initiative-flash-sale-inventory/spec-flash-sale-inventory/spec-flash-sale-inventory.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** A sale leaves no running record of how many order attempts arrived or how many database queries and updates ran.

**Approach:** The inventory module keeps those three totals in process memory and writes them to the application log. A request is an attempt that reaches inventory. A `SELECT` is a query. An `INSERT`, `UPDATE`, or `DELETE` is an update, including a statement that changes zero rows. Inventory JDBC and the outbox publisher both count. Opening the page does not.

## Boundaries & Constraints

**Always:**
- The page does not count and does not log the totals. The totals reset only when the process restarts. The log line is `requests=N queries=N updates=N`.
- Every call to `admit` counts one request, including success, the wait, refusal, sold out, not accepted, and a turn-away. `GET /` does not.
- Count a statement once. Test SQL and Flyway do not count. A zero-row change still counts.
- The totals do not change a shopper outcome. A deducted unit that was not available, or a customer above 3, is still a failed sale.

**Never:**
- A counter table, Micrometer, or a metrics endpoint. A second tally in the page. A product method that resets the totals.
- New shopper messages. Restock. Editing the migrations, the spec, the spine, or `tickets.toml`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| JDBC outcome | Success, wait, refusal, or sold out from the locked read | Request total rises by 1. Query and update totals both rise. The log shows all three. The shopper message is unchanged. | No error expected |
| No JDBC | Not accepted, a turn-away, or sold out from the hint | Request total rises by 1. Query and update totals stay. The log shows all three. | No error expected |
| Open the page | `GET /` | Request total stays. | No error expected |
| Publisher | A success whose event has been sent | The latest log line includes the publisher statements in the query and update totals. | No error expected |

</frozen-after-approval>

## Code Map

- `src/main/java/com/flashsale/inventory/Admission.java` — `admit` is the inventory entry for every attempt, including the early returns. Count the request here, once.
- `src/main/java/com/flashsale/inventory/Orders.java` — the only inventory JDBC, through `NamedParameterJdbcTemplate`. Those statements must count. Shopper branches stay.
- Namastack creates `outboxJdbcClient` from the shared datasource when that bean is missing. The publisher's SQL goes through that client, not through `Orders`.
- `src/main/java/com/flashsale/page/OrderPage.java` — `GET /` renders the form and does not call inventory. Leave it.
- `src/test/java/com/flashsale/OrderPoolEventTest.java` — one Spring context, so totals carry across tests. Assert deltas. The shopper assertions stay.

## Tasks & Acceptance

**Execution:**
- [ ] `src/main/java/com/flashsale/inventory/` -- Keep the three totals in memory, log them from inventory, count each `admit`, each inventory JDBC statement, and each statement on the outbox client. -- AD-8. The page stays out of it.
- [ ] `src/test/java/com/flashsale/OrderPoolEventTest.java` -- Cover the matrix with log deltas. A success waits until the event is sent before reading the publisher totals. `GET /` does not raise the request total. -- The verify check.

**Acceptance Criteria:**
- Given each of success, wait, refusal, sold out, not accepted, and a turn-away, when that attempt finishes, then the log shows the request, query, and update totals and the request total has risen by one.
- Given the page is opened and no order is posted, when the log is read, then the request total is unchanged.
- Given a successful order whose event has been sent, when the latest totals are read, then the publisher statements are included.

## Implementation Notes

## Plan Change Log

## Review Triage Log

Quick review, one pass. Verdicts: high 0, medium 0, low 0, false 1, maybe-false 0.

- false — `TotalsLogCapture` and `OrderPoolEventTest` — the totals tests cannot see a log line because the appender is not registered. Refuted: `src/test/resources/logback-test.xml` attaches `TotalsLogCapture` to `com.flashsale.inventory.SaleTotals`, and the suite that reads those lines passed with 24 tests and 0 failures.

## Design Notes

Provide the `outboxJdbcClient` bean so Namastack uses a client that counts, and leave the application's datasource unwrapped so test SQL and Flyway stay out. Inventory statements are counted on the template `Orders` uses. `SELECT` increments queries. `INSERT`, `UPDATE`, and `DELETE` increment updates.

Log `requests=N queries=N updates=N` after each request and when a publisher statement is counted. Tests read that line from the captured application log and compare the numbers with the line from before the action.

## Verification

**Commands:**
- `./gradlew test` -- expected: the log rows pass, and the story 1 through story 6 shopper tests still pass.

**Manual checks (if no CLI):**
- Place one order and read the application log. It contains a line with requests, queries, and updates.
