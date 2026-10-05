---
title: 'The sale checks run end to end'
type: 'feature'
ticket: '9'
created: '2026-10-05'
status: 'built'
baseline_revision: '0bc7bc42f4f260a63baaef7cc5dbca3421d33028'
route: 'full'
route_source: 'auto'
review: 'quick'
review_source: 'pinned'
lenses_ran: ['quick']
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/initiative-flash-sale-inventory/architecture-flash-sale-inventory/architecture-flash-sale-inventory.md'
  - '{project-root}/_bmad-output/initiative-flash-sale-inventory/spec-flash-sale-inventory/spec-flash-sale-inventory.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Each sale rule already has its own test, and each test starts from a fresh pool. Nothing runs the epic's Done when checks as one sale on the local service.

**Approach:** Add one closing test that walks those checks in a single sale through the page. Leave the product and the existing tests as they are.

## Boundaries & Constraints

**Always:**
- The test posts through the page on `/` against the local PostgreSQL and Kafka already used by `OrderPoolEventTest`. Inventory applies the existing rules. The page sentences stay "The order is in process", "Wait one hour before ordering again", "The order is refused", "The item has sold out", and "The order was not accepted".
- The sale starts from the seeded item `item-1` at quantity 10, with no customer rows and the sold-out hint clear. Later steps continue on that same sale. The hour elapses by moving `next_allowed` into the past. A short or empty pool is arranged the way the current tests arrange it, with a direct quantity update.
- One order of 3 meets the limit. Two successful orders by another customer also add up to 3, and a further order by that customer is refused. A request the pool could cover but that would pass the limit succeeds only for the units left. A pool of at least 1 that is smaller than the request succeeds for that smaller number, and the event carries it. An empty pool says sold out during the wait, with no deduction and no event. A blank user ID changes nothing. The log shows `requests=N queries=N updates=N` for the sale, and no customer ends above 3 units.

**Never:**
- A new shopper sentence, a product change, a restock, or clearing the sold-out hint from the product.
- Editing a migration, the spec, the spine, or `tickets.toml`.
- Holding the Kafka publisher until the HTTP response is written.
- Replacing or weakening the existing tests. Sleeping for an hour. A second item, payment, or accounts.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| One sale, pool above zero | A new customer orders 1 while the quantity is 10 | The page says the order is in process. The quantity is 9. One event carries `unitsDeducted` 1. | No error expected |
| Wait while the pool is above zero | That same customer orders again before the hour has elapsed | The page says to wait one hour. The quantity stays 9. No event is sent. | No error expected |
| Limit across orders, then a cap, then a refusal | After the hour, that customer orders enough to reach 3 across two orders, then orders again | The reaching order is in process and its event carries the units deducted. The stored total is 3. The next order is refused, the quantity is unchanged, and no event is sent. | No error expected |
| One order meets the limit | Another customer orders 3 while the quantity can cover it | The page says the order is in process. The event carries `unitsDeducted` 3. That customer's total is 3. | No error expected |
| Short pool | The quantity is 1 and a new customer orders 3 | The page says the order is in process. The event carries `unitsDeducted` 1. The quantity is 0. | No error expected |
| Empty pool during the wait | That customer orders again while the quantity is 0 and the hour has not elapsed | The page says the item has sold out and does not say to wait. The quantity stays 0. No event is sent. | No error expected |
| Blank attempt | User ID is empty and the quantity is 1 | The page says the order was not accepted. The item quantity is unchanged and no event is sent. | No error expected |
| Totals for the sale | The log after this sale | A line reads `requests=N queries=N updates=N`, the request total is higher than before the sale, and no customer has more than 3 units. | No error expected |

</frozen-after-approval>

## Code Map

- `src/test/java/com/flashsale/OrderPoolEventTest.java` — the existing suite. `@BeforeEach` sets quantity 10, clears the hint, and deletes customers. Reuse `order`, `elapseTheHour`, `assertOneEvent`, `assertNothingPublished`, `quantity`, and `unitsOrdered`. Add the closing sale here. The log helpers compare a `requests=N queries=N updates=N` line with the preceding one because the publisher keeps polling.
- `src/main/java/com/flashsale/inventory/Orders.java` — deduction, wait, limit, subset, and sold out. Do not change it.
- `src/main/java/com/flashsale/inventory/Admission.java` — not accepted is decided before the hint and the permit. A blank attempt still returns not accepted after the hint is set. Do not change it.
- `src/main/java/com/flashsale/page/OrderPage.java` — posts reach inventory through `admit`. Do not change it.
- Do not edit the migrations, the spec, the spine, or `tickets.toml`.

## Tasks & Acceptance

**Execution:**
- [x] `src/test/java/com/flashsale/OrderPoolEventTest.java` -- Add one test that walks the matrix as a single sale through the page. Keep the existing tests. -- The epic Done when checks, with no new shopper behavior.

**Acceptance Criteria:**
- Given the local service and the seeded item, when this sale finishes, then each matrix row's page sentence, quantity, and event hold, and no customer has more than 3 units.
- Given the existing story tests, when the suite runs, then they still pass.

## Implementation Notes

`theSaleChecksRunEndToEnd` walks the matrix in order through the page. `./gradlew test` passed 25 tests and 0 failures at 2026-10-05T18:27:56.540Z. No product file changed.

## Plan Change Log

## Review Triage Log

Quick review, one pass. Verdicts: high 0, medium 0, low 0, false 0, maybe-false 0. No findings. Edge-case and verification-gap lenses were skipped because this review is quick.

## Design Notes

Run the rows in matrix order so the wait, the limit, and the empty pool stay on the same sale. Alex's first order is 1. After the hour, Alex orders 5 and keeps 2, which brings the stored total to 3. Alex's next order is the refusal. A second customer then orders 3 while the quantity still covers it. Set the quantity to 1 before the short-pool customer orders 3. That success starts their wait, and their next order is the empty-pool case. The blank user ID comes after that. Read the totals from the captured log, as the logging tests do, and do not assert an absolute count.

Elapse the hour with `elapseTheHour`. Do not sleep. The Kafka consumer is the one this class already builds. Seek to the end before the first order.

## Verification

**Commands:**
- `./gradlew test` -- expected: the new sale passes, and the existing tests still pass.

**Manual checks (if no CLI):**
- The suite is the person's run of the epic Done when checks. It uses MockMvc against the local PostgreSQL and Kafka, not a browser.
