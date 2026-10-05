---
title: 'A short pool or a short limit keeps a subset'
type: 'feature'
ticket: '4'
created: '2026-10-05'
status: 'built'
baseline_revision: '064760e4da3dae872642d89590c902e5721b509d'
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

**Problem:** A request larger than the quantity, or larger than the units still left under 3, is dropped even when at least one unit can be taken. The customer loses that place.

**Approach:** On the locked transaction, deduct the smaller of the request, the quantity, and the units left, when that number is at least one. The page says the order is in process, the customer row and the order event record that smaller number, and the wait is stored again. No units left, after the hour, with the quantity above zero, stays refused.

## Boundaries & Constraints

**Always:**
- Keep the story 3 order: customer lock, then item lock, then the wait, then refused when no units are left. The smaller number is chosen only after those, and only when the quantity is above zero and at least one unit is left.
- The conditional update still matches `item-1` only while the row holds the units being deducted. Those units are the smaller number, not the request. On a match, add that number to `units_ordered`, set `next_allowed` from PostgreSQL's clock plus one hour, and publish `unitsDeducted` as that number.
- If that update changes no row while the quantity is still above zero, recompute the smaller number under the locks already held and retry. That miss is not sold out.
- A partial success is a successful order. The page renders the message inventory returns and does not replace it.

**Never:**
- The sold-out outcome, a sold-out hint, or a success when the quantity is not above zero. An empty quantity stays the current rollback, with no event and no sold-out words.
- Not-accepted, log totals, a second stock count, or an application clock.
- Deducting the full request when it does not fit. Letting `units_ordered` pass 3. Editing `V1` or `V2`, the spec, the spine, or `tickets.toml`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Short quantity | Quantity is at least 1 and less than the request, and the units left cover the quantity | Page says the order is in process. Quantity becomes 0. units_ordered grows by the old quantity. Event unitsDeducted is that quantity. | No error expected |
| Short limit | Quantity could cover the request, the hour has elapsed, and the units left are at least 1 and less than the request | Page says the order is in process. units_ordered becomes 3. Quantity drops by the units left. Event unitsDeducted is the units left. | No error expected |
| Both short | The request is larger than both the quantity and the units left, and both are at least 1 | Page says the order is in process. Deduction, units_ordered, and unitsDeducted are the smaller of those two. | No error expected |
| None left | units_ordered is 3, next-allowed is in the past, quantity is above zero | Page says "The order is refused". Nothing deducted. No event. | This attempt rolls back |
| Empty quantity | Quantity is 0 | Nothing deducted. No event. Page does not say in process, wait, refused, or sold out. | Left for story 5 |

</frozen-after-approval>

## Code Map

- `src/main/java/com/flashsale/inventory/Orders.java` — after the wait and the zero-units-left checks, a request larger than `quantity` or the units left returns `unmatched()` and rolls back. Replace that hold with the smaller number. The conditional update and the customer write already sit in this transaction.
- `src/main/java/com/flashsale/inventory/OrderOutcome.java` — `inProcess()` and `refused()` already exist. Do not add a message for the subset.
- `src/test/java/com/flashsale/OrderPoolEventTest.java` — `requestLargerThanUnitsLeftDeductsNothing`, `requestLargerThanUnitsLeftAfterASuccessDeductsNothing`, and `requestLargerThanThePoolDeductsNothing` encode the hold this story replaces. `noneLeftAfterTheHourIsRefused` and `zeroRowUpdateLeavesThePoolAndSendsNothing` stay.
- Do not edit the admission gate, the page, or the migrations.

## Tasks & Acceptance

**Execution:**
- [x] `src/main/java/com/flashsale/inventory/Orders.java` -- Deduct the smaller of the request, the quantity, and the units left when that number is at least 1. Retry the conditional update under the held locks when it misses while the quantity is still above zero. -- CAP-5 and CAP-6. The wait and the refuse stay ahead of it.
- [x] `src/test/java/com/flashsale/OrderPoolEventTest.java` -- Replace the three hold tests with the short-quantity, short-limit, and both-short rows. Keep the refuse and empty-quantity tests. -- The verify check.

**Acceptance Criteria:**
- Given the quantity is at least 1 and smaller than the request, when the units left cover that quantity, then the page says the order is in process and unitsDeducted equals that quantity.
- Given the quantity could cover the request and the units left are at least 1 and smaller than the request, when the hour has elapsed, then the page says the order is in process and unitsDeducted equals the units left.
- Given no units are left, the hour has elapsed, and the quantity is above zero, when that user orders, then the order is refused and no event is sent.

## Implementation Notes

## Plan Change Log

## Review Triage Log

Quick review, one pass. Verdicts: high 0, medium 0, low 0, false 0, maybe-false 0. No findings.

## Design Notes

The three story 3 tests that expect a blank page for an oversized request are the hold this story removes. They become successes. The smaller number is `min(request, quantity, 3 - units_ordered)`. Write that number on the customer row and in the event. A result of 0 is not a success: no units left is still refused, and a quantity that is not above zero stays `unmatched()`.

The retry re-reads `quantity` on the item lock already held, recomputes that minimum, and tries the conditional update again. It stops when the update matches, the recomputed number is 0, or the quantity is no longer above zero. It does not say sold out.

## Verification

**Commands:**
- `./gradlew test` -- expected: the three subset rows and the existing refuse and empty-quantity tests pass, and the story 1 through story 3 tests still pass.

**Manual checks (if no CLI):**
- With 2 units in the quantity, order 3 as a new user. The page says the order is in process.
