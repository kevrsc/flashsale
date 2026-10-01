---
title: 'Extra attempts wait and are not sold out'
type: 'feature'
ticket: '2'
created: '2026-10-01'
status: 'built'
baseline_revision: '9836dca46b7b3a966f21e41be951efbae63dcb14'
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

**Problem:** Every order attempt opens a database transaction on the one item row. Past a bound, that is how the service falls over, and a shopper who is still waiting must not be told the item is sold out while units remain.

**Approach:** A small admission bound sits in front of the existing conditional update. Attempts past the bound open no transaction. The page does not show that turn-away. It retries with jitter until inventory returns a shopper outcome. While the quantity is above zero, that outcome is not sold out.

## Boundaries & Constraints

**Always:**
- The bound is a setting. This story uses 2. It does not choose the production number.
- Only attempts that get a permit call the existing transactional update. The rest are turned away with no transaction.
- The page does not render a turn-away. Jitter applies only to a retry, not the first attempt.
- A shopper who is still waiting is not told sold out while `item.quantity` is above zero. A retry can still receive "The order is in process".
- The column is `quantity`. The conditional update from story 1 stays: subtract the requested units only while the row still holds them, and publish the event in that same transaction.

**Never:**
- A sold-out hint, a sold-out shopper message, customer-row writes, the one-hour wait, the limit of 3, a short-quantity subset, not-accepted, or log totals.
- Jitter on the first attempt. A second stock count. A cache in front of `quantity`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Past the bound | Quantity above zero, more attempts in flight than 2 | An attempt that does not get a permit opens no transaction and the page does not say sold out. A retry can still say the order is in process. | Turn-away is not a shopper message |
| Under the bound | One attempt, quantity holds the units | Same as story 1: the page says the order is in process, quantity drops by the units, one event is sent. | No error expected |

</frozen-after-approval>

## Code Map

- `src/main/java/com/flashsale/inventory/Orders.java` — `place` is `@Transactional` and updates `quantity`. The permit check has to happen before this method runs, or the transaction is already open.
- `src/main/java/com/flashsale/page/OrderPage.java` — `order` calls `place` once and prints `message()`. A turn-away must not be that message. Retry belongs here.
- `src/main/java/com/flashsale/inventory/OrderOutcome.java` — shopper messages today are in process, or null for a zero-row update. Do not reuse null for turn-away.
- `src/main/resources/application.properties` — add the bound setting. Do not change the localhost datasource or Kafka settings.
- `src/test/java/com/flashsale/OrderPoolEventTest.java` — the two story 1 checks. Keep them passing. A single attempt must still get a permit when fewer than 2 are in flight.
- Do not edit the spec, the spine, or `tickets.toml`.

## Tasks & Acceptance

**Execution:**
- [ ] `src/main/resources/application.properties` -- Add a setting for the admission bound with value 2. -- The production number stays unpinned.
- [ ] `src/main/java/com/flashsale/inventory/` -- Before `Orders.place` opens its transaction, admit at most that many attempts. A refusal runs no JDBC and returns a turn-away that is not a shopper message. Release the permit when the admitted attempt finishes. -- AD-4. The conditional update stays inside the transaction.
- [ ] `src/main/java/com/flashsale/page/OrderPage.java` -- On a turn-away, retry with jitter until inventory returns a shopper outcome, and render only that outcome. The first attempt is not delayed. -- The page must not show sold out for a wait.
- [ ] `src/test/java/com/flashsale/` -- Cover both matrix rows. Hold the bound full so one more attempt opens no transaction and is not shown sold out, then free a permit and show that a retry can say the order is in process. -- The verify check.

**Acceptance Criteria:**
- Given the quantity is above zero and two attempts already hold the bound, when another attempt arrives, then that attempt runs no database statement and the page does not say sold out.
- Given a permit frees while the quantity still holds the units, when the page retries, then the shopper sees that the order is in process.

## Implementation Notes

## Plan Change Log

## Review Triage Log

Quick review, one pass. Verdicts: high 0, medium 0, low 0, false 0, maybe-false 0. No findings.

## Design Notes

Turn-away is a distinct result, not `unmatched()` and not a sentence on the page. The page loops on that result only. Jitter is a short random pause, on the order of tens of milliseconds, and only after a turn-away.

The bound of 2 is the local setting so a test can fill it. Changing the production number later is a configuration change, not a new rule.

## Verification

**Commands:**
- `./gradlew test` -- expected: the story 1 tests still pass, and both new matrix rows pass.

**Manual checks (if no CLI):**
- With the quantity above zero, submit an order on `/`. The page says the order is in process and does not say sold out.
