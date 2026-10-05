---
title: 'An empty pool is sold out, even during the wait'
type: 'feature'
ticket: '5'
created: '2026-10-05'
status: 'built'
baseline_revision: '2cae00ac8e8e00450e350ddd5f9c29e194c475ed'
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

**Problem:** An order against an empty quantity still has no shopper message. A customer who is also in the one-hour wait is not told the item has sold out, and every later attempt still opens a transaction.

**Approach:** When a statement shows the quantity is not above zero, the page says "The item has sold out", the transaction rolls back, and no event is sent. That answer comes before the one-hour wait. After that statement, a later attempt can be answered from a sold-out hint with no transaction and no deduction.

## Boundaries & Constraints

**Always:**
- Sold out is decided before the wait, the refusal, and the smaller-of deduction. A customer in the wait whose quantity is not above zero sees "The item has sold out", not the wait.
- The hint is a boolean in the inventory module, not a count, and it is not used to deduct. It is set only after a statement has shown the quantity is not above zero. Admission checks it before taking a permit and before `Orders.place`, and returns that shopper outcome. The page renders the message and does not replace it.
- The first empty order still runs the locked read, rolls back, including any customer insert from that attempt, and sends no event. A conditional-update miss while the quantity is still above zero stays the story 4 retry. It does not set the hint and it does not say sold out.
- The existing success, wait, refuse, subset, and turn-away tests still pass. They may expect the sold-out words only when the quantity is not above zero.

**Never:**
- Not-accepted, log totals, restock, or clearing the hint because the quantity was raised again. A test may clear the hint so the next case starts from a quantity of 10. That clear is not a product behavior.
- Using the hint as a stock count. Showing sold out for a turn-away while the quantity is above zero. Deducting on a sold-out answer.
- Editing the migrations, the spec, the spine, or `tickets.toml`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Empty quantity | Quantity is 0, new user, request is at least 1 | Page says "The item has sold out". Quantity stays 0. No customer row. No event. | This attempt rolls back |
| Empty during the wait | That user already has a successful order, then the quantity is 0 | Page says "The item has sold out", not the wait. Nothing deducted. No event. | This attempt rolls back |
| Later attempt | The hint is set, quantity is still 0 | Page says "The item has sold out". The attempt opens no transaction and deducts nothing. | No error expected |
| Still in stock | Quantity is above zero and the request fits | Same as story 4. The page does not say sold out. | No error expected |

</frozen-after-approval>

## Code Map

- `src/main/java/com/flashsale/inventory/Orders.java` — after the item lock, a quantity that is not above zero falls through to `unmatched()`. Return sold out there, before the wait, and set the hint from that read. The retry's empty re-read is the same outcome. A miss while the quantity is still above zero must not set the hint.
- `src/main/java/com/flashsale/inventory/Admission.java` — `admit` takes a permit and then calls `place`, which opens the transaction. Check the hint before both.
- `src/main/java/com/flashsale/inventory/OrderOutcome.java` — add the sold-out message. Do not reuse null or the wait.
- `src/main/java/com/flashsale/page/OrderPage.java` — already prints `message()`. No change if sold out passes through.
- `src/test/java/com/flashsale/OrderPoolEventTest.java` — `zeroRowUpdateLeavesThePoolAndSendsNothing` asserts the page does not say sold out. That assertion is the hold this story removes. `@BeforeEach` restores quantity to 10, so it must also clear the hint or later tests will see sold out.

## Tasks & Acceptance

**Execution:**
- [x] `src/main/java/com/flashsale/inventory/` -- Add the sold-out outcome and a boolean hint. Set the hint only from a statement that shows the quantity is not above zero. Check it in `admit` before the permit and before `place`. -- AD-3 and AD-4. The wait stays behind sold out.
- [x] `src/test/java/com/flashsale/OrderPoolEventTest.java` -- Cover the empty quantity, the empty quantity during the wait, and a later attempt that does not block on an item-row lock. Clear the hint before each test. Keep the in-stock tests from saying sold out. -- The verify check.

**Acceptance Criteria:**
- Given the quantity is 0, when a customer orders, then the page says the item has sold out, nothing is deducted, and no event is sent.
- Given that customer is also in the one-hour wait and the quantity is 0, when they order, then the page says the item has sold out and does not say to wait.
- Given a statement has shown the quantity is not above zero, when a later attempt arrives, then it is answered from the hint with no transaction and no deduction.

## Implementation Notes

## Plan Change Log

## Review Triage Log

Quick review, one pass. Verdicts: high 0, medium 0, low 0, false 0, maybe-false 0. No findings.

## Design Notes

The shopper sentence is "The item has sold out". The hint is an `AtomicBoolean` in the inventory module. `Orders` sets it. `Admission` reads it. The later-attempt test holds `SELECT quantity FROM item WHERE id = 'item-1' FOR UPDATE` and expects the hinted order to return without waiting on that lock.

`@BeforeEach` clears the hint after setting the quantity back to 10. Nothing in the product clears it. Restock stays deferred.

## Verification

**Commands:**
- `./gradlew test` -- expected: the three sold-out rows pass, and the story 1 through story 4 tests still pass.

**Manual checks (if no CLI):**
- Set the quantity to 0 and order. The page says the item has sold out and does not say the order is in process.
