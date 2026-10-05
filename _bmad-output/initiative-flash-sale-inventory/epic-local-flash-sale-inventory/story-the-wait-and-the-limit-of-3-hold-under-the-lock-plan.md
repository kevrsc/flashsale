---
title: 'The wait and the limit of 3 hold under the lock'
type: 'feature'
ticket: '3'
created: '2026-10-01'
status: done
baseline_revision: 'fb6282a2fc5c7f781a3a4a4e9a8759c34bc02f1a'
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

**Problem:** A successful order does not yet remember the customer. The same person can order again at once, and nothing stops their successful units from passing 3.

**Approach:** On the admitted transaction, create the customer row when that user id has none, lock the customer and then the item, and write the successful units and the next-allowed time from PostgreSQL's clock plus one hour. During that hour the same customer is told to wait, with nothing deducted and no event. An order that lands on a total of 3 succeeds. Once the hour has elapsed and no units are left, with the quantity still above zero, the order is refused.

## Boundaries & Constraints

**Always:**
- The admission gate stays outside the transaction. Customer work runs inside `Orders.place`. Lock the customer row, then the item row. Insert the missing customer row before locking the item. A conflicting insert locks the existing row and does not insert a second one.
- Read successful units and next-allowed time under the customer lock, then read `quantity` under the item lock. When `quantity` is above zero and next-allowed is still in the future, the page says "Wait one hour before ordering again". When `quantity` is above zero, the customer is not in the wait, and no units are left under 3, the page says "The order is refused".
- A successful order writes `units_ordered` and `next_allowed` in that same transaction. `next_allowed` is PostgreSQL's clock plus one hour. The page only renders the message inventory returns. User ids compare exactly, with no trim and no case fold.
- The column is `quantity`. A full request that fits in both `quantity` and the units left still uses the story 1 conditional update and publishes only on that match. A zero-row update rolls back, including the customer insert from this attempt.
- The existing story 1 and story 1.2 tests still prove a deduction, one event, a turn-away with no transaction, and no sold-out wording. They may expect the customer row a success now writes.

**Never:**
- A short-quantity subset, or a partial when some units remain under the limit but the request is larger. Those are story 4. Do not deduct past 3, and do not say in process, wait, or refused for that hold.
- Sold out, a sold-out hint, not-accepted, or log totals. When `quantity` is not above zero, do not say wait or refused.
- An application clock for the deadline. A second customer row. The page computing the wait. Editing `V1__item_and_customer.sql`, the spec, the spine, or `tickets.toml`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Repeat during the wait | Same user orders again while next-allowed is in the future and quantity is above zero | Page says "Wait one hour before ordering again". Quantity and units_ordered stay. No event. | This attempt rolls back |
| One order meets 3 | New user, quantity above zero, request is 3 | Page says the order is in process. units_ordered is 3. Event unitsDeducted is 3. Quantity drops by 3. | No error expected |
| Later order meets 3 | After a success, next-allowed is in the past, and this request makes the sum 3 | Page says the order is in process. units_ordered is 3. One event for this order. The wait is stored again. | No error expected |
| None left after the hour | units_ordered is 3, next-allowed is in the past, quantity is above zero | Page says "The order is refused". Nothing deducted. No event. | This attempt rolls back |
| Other customer | A different user orders while the first is in the wait, and quantity holds the units | That user sees the order is in process. The first user's wait is unchanged. | No error expected |
| Request does not fit | Request is larger than the units left while at least one unit is left, or larger than quantity while quantity is at least 1 | Nothing deducted. No event. Page does not say in process, wait, or refused. | Left for story 4 |

</frozen-after-approval>

## Code Map

- `src/main/java/com/flashsale/inventory/Orders.java` — `place` is the transaction. It selects the seeded item and updates `quantity` with no customer lock. Add the insert, the customer-then-item locks, and the wait and refuse branches here.
- `src/main/java/com/flashsale/inventory/OrderOutcome.java` — `inProcess()` and `unmatched()` (null message). Add the wait and refused messages. Do not reuse null for them.
- `src/main/java/com/flashsale/inventory/Admission.java` — permit around `place`. Leave it. Do not open the transaction in the gate.
- `src/main/java/com/flashsale/page/OrderPage.java` — prints `message()` after retries. No change if the new messages pass through.
- `src/main/resources/db/migration/V1__item_and_customer.sql` — `customer` is only `user_id`. Do not edit. The live checksum matches this file.
- `src/test/java/com/flashsale/OrderPoolEventTest.java` — success and the bound test assert `customerRows() == 0`. A success will now leave a row, so those counts must follow this story. `itemUpdatesInFlight` matches only `UPDATE item`. A transaction blocked on the item lock must still count as in flight.

## Tasks & Acceptance

**Execution:**
- [ ] `src/main/resources/db/migration/` -- Add a new migration that stores successful units and the next-allowed time on `customer`. -- V1 has already been applied.
- [ ] `src/main/java/com/flashsale/inventory/Orders.java` -- Create or lock the customer, then lock the item, and apply the matrix in that order. On success, add the units and set next-allowed from PostgreSQL's clock plus one hour before publishing. -- AD-3 and AD-6. The conditional update stays.
- [ ] `src/main/java/com/flashsale/inventory/OrderOutcome.java` -- Add the wait and refused messages. -- The page already renders `message()`.
- [ ] `src/test/java/com/flashsale/OrderPoolEventTest.java` -- Cover the matrix. Move `next_allowed` into the past instead of sleeping an hour. Isolate customer rows per test. Count an item-row lock as in flight, and keep the turn-away proof. -- The verify check, without breaking story 1 or story 1.2.

**Acceptance Criteria:**
- Given a successful order, when that user orders again before the hour has elapsed and the quantity is above zero, then the page tells them to wait one hour, nothing is deducted, and no event is sent.
- Given an order whose units make that user's successful total 3, when it is admitted and the quantity holds those units, then the order is in process and the stored total is 3.
- Given that hour has elapsed, no units are left, and the quantity is above zero, when that user orders again, then the page says the order is refused and no event is sent.

## Implementation Notes

## Plan Change Log

## Review Triage Log

Quick review, one pass. Verdicts: high 0, medium 0, low 0, false 0, maybe-false 0. No findings.

## Design Notes

`customer.units_ordered` is an integer, default 0. `customer.next_allowed` is a timestamp and is null until a success. A new attempt inserts `user_id` only, with `ON CONFLICT DO NOTHING`, then `SELECT ... FOR UPDATE` on that user. The item lock is a `SELECT quantity ... FOR UPDATE` on `item-1` after that.

The wait is `next_allowed` later than PostgreSQL's `clock_timestamp()`. Success sets `next_allowed` to `clock_timestamp() + interval '1 hour'` and adds the requested units. Wait, refuse, a request that does not fit, and a zero-row update call `setRollbackOnly` and do not publish.

The tests delete customer rows in `@BeforeEach`, then assert the rows this scenario wrote. To elapse the hour they set `next_allowed` to the past. The in-flight check counts a session whose query locks or updates `item`, still expecting two, and the third `admit` stays `TurnedAway`.

## Verification

**Commands:**
- `./gradlew test` -- expected: the new matrix rows pass, and the story 1 and story 1.2 tests still pass.

**Manual checks (if no CLI):**
- Order once, then order again as the same user. The page says to wait one hour and does not say the order is in process.
