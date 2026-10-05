---
title: 'A blank user ID or quantity is not an order'
type: 'feature'
ticket: '6'
created: '2026-10-05'
status: 'built'
baseline_revision: '3951ee6ce778bb61b1140af9d2d6462485f5798b'
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

**Problem:** A blank user ID still opens an order, and a quantity of 0 does not tell the shopper the order was not accepted.

**Approach:** Reject an empty user ID, and a quantity that is not a positive integer, before a permit and before `Orders.place`. The page says "The order was not accepted". Nothing is deducted, no customer row is inserted, and no event is sent.

## Boundaries & Constraints

**Always:**
- An empty user ID is blank. Do not trim and do not case-fold. A user ID of spaces is not blank.
- A quantity of 0 or less is not a positive integer. Both checks happen in admission, before the sold-out hint, before a permit, and before `place`. The page renders "The order was not accepted" and does not replace it. The retry loop does not treat this as a turn-away.
- The quantity stays unchanged. No customer row is inserted. No event is sent. The sold-out hint is not set and is not cleared.
- A positive quantity with a non-empty user ID still follows story 5, including sold out, the wait, refusal, and the smaller number.

**Never:**
- Not accepted shown for a turn-away, a wait, a refusal, or sold out. Log totals. Restock. A new page for a value that is not an integer; the form parameter stays an int.
- Editing the migrations, the spec, the spine, or `tickets.toml`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Blank user ID | User ID is empty, quantity is 1, quantity in stock | Page says "The order was not accepted". No customer row. Quantity unchanged. No event. The attempt opens no transaction. | No error expected |
| Quantity of 0 | A non-empty user ID, quantity 0, quantity in stock | Page says "The order was not accepted". No customer row. Quantity unchanged. No event. | No error expected |
| Negative quantity | A non-empty user ID, quantity is negative, quantity in stock | Page says "The order was not accepted". No customer row. Quantity unchanged. No event. | No error expected |
| Still an order | A non-empty user ID and a positive quantity that fits | The page says the order is in process and does not say the order was not accepted. | No error expected |

</frozen-after-approval>

## Code Map

- `src/main/java/com/flashsale/inventory/Admission.java` — `admit` checks the sold-out hint, then takes a permit, then calls `place`. Reject the empty user ID and the non-positive quantity before those.
- `src/main/java/com/flashsale/inventory/OrderOutcome.java` — add the not-accepted message. Do not reuse null, sold out, or the wait.
- `src/main/java/com/flashsale/inventory/Orders.java` — `place` inserts the customer row as its first statement. These rejects must not call it. Leave the deduction path alone.
- `src/main/java/com/flashsale/page/OrderPage.java` — prints `message()` and retries only a turn-away. No change if not accepted is a shopper outcome.
- `src/test/java/com/flashsale/OrderPoolEventTest.java` — posts `userId` and `quantity` through MockMvc. Add the matrix rows. The in-stock success test must not say the order was not accepted.

## Tasks & Acceptance

**Execution:**
- [ ] `src/main/java/com/flashsale/inventory/` -- Return not accepted from `admit` for an empty user ID or a non-positive quantity, before the hint, the permit, and `place`. -- CAP-8. The transaction stays closed.
- [ ] `src/test/java/com/flashsale/OrderPoolEventTest.java` -- Cover the empty user ID, quantity 0, and a negative quantity. Hold the item row locked for the empty user ID and show that the answer does not wait on it. -- The verify check.

**Acceptance Criteria:**
- Given an empty user ID and a quantity of 1, when that order is posted, then the page says the order was not accepted, no customer row is inserted, the quantity is unchanged, and no event is sent.
- Given a non-empty user ID and a quantity of 0, when that order is posted, then the page says the order was not accepted, no customer row is inserted, the quantity is unchanged, and no event is sent.
- Given a non-empty user ID and a positive quantity the pool can cover, when that order is posted, then the page says the order is in process.

## Implementation Notes

## Plan Change Log

## Review Triage Log

Quick review, one pass. Verdicts: high 0, medium 0, low 0, false 0, maybe-false 0. No findings.

## Design Notes

The shopper sentence is "The order was not accepted". Empty means `userId.isEmpty()`. Spaces stay a real user ID because comparison does not trim.

The blank-user test holds `SELECT quantity FROM item WHERE id = 'item-1' FOR UPDATE` and expects the page to answer without waiting on that lock. Quantity 0 and a negative quantity assert the page, the customer table, the quantity, and the outbox.

## Verification

**Commands:**
- `./gradlew test` -- expected: the new rows pass, and the story 1 through story 5 tests still pass.

**Manual checks (if no CLI):**
- Post a quantity of 0 with a user ID. The page says the order was not accepted and does not say the order is in process.
