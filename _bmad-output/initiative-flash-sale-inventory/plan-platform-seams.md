---
title: 'Platform seams'
type: 'feature'
ticket: ''
created: '2026-10-13'
status: 'built'
baseline_revision: 'a57a81656e85b4deeac21330857bc0486a64c0ed'
route: 'full'
route_source: 'auto'
review: 'quick'
review_source: 'pinned'
lenses_ran: ['quick']
review_loop_iteration: 0
context: []
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** A neighboring inventory system cannot open this pool or read what is left, a declined payment cannot give units back, and a storefront cannot place an order except by submitting the Thymeleaf form. After the pool hits zero the in-memory hint stays sold out until the process restarts.

**Approach:** Keep the form, the deduction, and `inventory.order-accepted`. Add a command that sets the single pool, a command that returns units for one accepted order, a storefront command that runs the same order decision, and a read of the item id and quantity left. Document that place in the platform in `README.md`, including the platform diagram.

## Boundaries & Constraints

**Always:** One item row. The order event's `itemId` is that row's id. The limit of 3, the one-hour wait, the admission bound, the conditional deduction, the outbox, and the five shopper sentences stay. `GET /` and `POST /` stay the local demo and still retry a turn-away. The storefront command calls `Admission.admit` and retries a turn-away inside the request. It takes a user id and a quantity, not an item id. Opening the pool, returning units, and the read do not increment `sale.attempts` and do not publish. A command that changes the pool locks the item row. When it also changes a customer, it locks that customer row first. Returning units does not clear `next_allowed`. The sold-out hint is cleared only when a command leaves the pool above zero. Setting the pool to 0 sets the hint. `README.md` keeps the local run steps and gains the platform section.

**Never:** Payment, accounts, catalog, pricing, fulfillment, or a second item row. A new Kafka topic. New meter names or a new shopper sentence. Editing the architecture spine, the spec, or the PRD. Changing `scripts/live-sale-checks.sh`. Sharing this database with another service.

## Decisions

- Release is `POST /orders/{orderId}/release`. The deduction transaction stores `orderId`, `userId`, `itemId`, and `unitsDeducted`. The first release adds those units and subtracts them from that customer. The same order id again changes nothing. A release whose item is not the open row changes nothing, including after a later open replaced that item.
- Opening the same item id keeps customer rows. A different item id deletes them, so the limit of 3 and the wait apply to the new item. Acceptance rows stay, so a later release of the previous item can be recognized and refused.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Storefront success | Pool above 0, hint clear, new user, quantity 1 | 200, sentence "The order is in process", `result` `in_process`. Pool down 1. One acceptance row and one `inventory.order-accepted` record for the open item | Fail if the form sentence or the event fields differ |
| Storefront refusal, wait, sold out, not accepted | Same inputs that produce those four sentences on `POST /` | 200 and that same sentence, with `result` `refused`, `wait`, `sold_out`, or `not_accepted`. Pool unchanged. No acceptance row and no new Kafka record | A turn-away is retried, not returned |
| Open above zero | Item `sku-9`, quantity 4, hint already set | The single row is `sku-9` with quantity 4. Hint clear. The next successful order event says `sku-9` | Blank item id or quantity below 0: row and hint unchanged, 400 |
| Open at zero | Quantity 0 | Pool 0, hint set. The next order is sold out and does not publish | Negative quantity: no change, 400 |
| Open same item | Item id unchanged, customers already hold units and a wait | Those customer rows stay. Quantity becomes the given number | A later release of an order for this item still adds its units on top |
| Open other item | Item id changes, customers exist, an unreleased order exists for the old item | Customer rows are deleted. Acceptance rows stay. Releasing the old order changes nothing | The new pool stays at the quantity just set |
| Read | Pool 4, item `sku-9` | 200 with that item id and quantity 4. No deduction, no event, `sale.attempts` unchanged | Empty body is a failure |
| Release once | Accepted order for the open item, pool 4, customer units 1 | 200, `released` true, pool 5, customer units 0, `next_allowed` unchanged. Hint clear because the pool is above zero | Customer units do not go below zero: if they are already short, change nothing |
| Release again | Same order id | 200, `released` false. Pool, customer, and hint unchanged | Unknown order id is the same: 200, `released` false, no change |

</frozen-after-approval>

## Code Map

- `src/main/java/com/flashsale/inventory/Orders.java` — `place` locks `item-1` (about lines 47–52 and 118–123), publishes `OrderAccepted` in that transaction (line 86), and sets the hint only in `soldOut` (lines 112–115). `clearSoldOutHint` (lines 107–109) is what tests call. Reuse the lock order and the conditional update. Insert the acceptance row in that same transaction. Do not change the limit, the wait, or the event fields.
- `src/main/java/com/flashsale/inventory/Admission.java` — `admit` is the decision the storefront command must call. A full gate returns `Attempt.TurnedAway` once (lines 32–34). Do not loop inside `admit`, and do not add a result tag.
- `src/main/java/com/flashsale/page/OrderPage.java` — `shopperOutcome` (lines 52–59) is the retry to copy. `GET /` and `POST /` stay.
- `src/main/java/com/flashsale/page/package-info.java` — page may depend on inventory. A new `api` package needs the same allowance. Inventory's `package-info.java` stays empty.
- `src/main/resources/db/migration/V1__item_and_customer.sql` — one `item` row, seeded `item-1` / 10. `V2__customer_units_and_next_allowed.sql` adds `units_ordered` and `next_allowed`. Add `V3` for the acceptance row. Do not let Flyway create outbox tables.
- `src/test/java/com/flashsale/OrderPoolEventTest.java` — `@BeforeEach` sets quantity 10 only `WHERE id = 'item-1'` and deletes customers. Extend that reset so the single row is `item-1` again and acceptance rows are gone. Do not weaken the hint tests. Put the new cases in a new test class.
- `scripts/live-sale-checks.sh` — resets `WHERE id = 'item-1'` (line 195) and then requires quantity 10. Leave the script alone. It still assumes the row id is `item-1`.
- `README.md` — lines 126 and 134 say the product never clears the hint and does not restock. Those sentences have to name the new commands. The module diagram near the top stays the local picture.

## Tasks & Acceptance

**Execution:**
- [x] `src/main/resources/db/migration/V3__accepted_order.sql` -- Create the acceptance table: order id, user id, item id, units deducted, and a released flag. -- The release command needs a row committed with the deduction.
- [x] `src/main/java/com/flashsale/inventory/Orders.java` -- `place` locks the single item row and inserts the acceptance row beside the event. Add open, read, and release. -- Open sets id and quantity. Release is idempotent and refuses a different item.
- [x] `src/main/java/com/flashsale/api/` -- Add the module and `POST /orders`, `POST /sale`, `GET /sale`, and `POST /orders/{orderId}/release`. -- Inventory stays free of the page and of HTTP.
- [x] `src/test/java/com/flashsale/OrderPoolEventTest.java` -- Reset the single item row to `item-1` and delete acceptance rows before each test. -- A new test that changes the item id must not leak into the hint tests.
- [x] `src/test/java/com/flashsale/` -- Cover the matrix in a new test class, including release, the same-item open, and the other-item open. -- JaCoCo stays at or above 95% line and branch coverage.
- [x] `README.md` -- Add the platform section, the flowchart, and the four routes. Correct the hint and restock sentences. -- The local demo stays accurate.

**Acceptance Criteria:**
- Given the process is up, when a client opens a pool above zero and then `POST /orders` succeeds, then the event's `itemId` is the opened item, an acceptance row exists, and `GET /sale` shows the quantity left.
- Given a full admission gate, when `POST /orders` is waiting, then the response is a shopper sentence and `sale.attempts` has counted the turn-away and the final result.
- Given `./gradlew test`, when it finishes, then the build is green, including the existing hint tests and the 95% line and branch gate.

## Implementation Notes

- `./gradlew test` passed: OrderPoolEventTest 32, PlatformSeamTest 14, InventoryCoverageTest 3, SaleApiResultTest 1, none skipped or failed. JaCoCo verification is bound to that task and did not fail the build.
- The quick-lens subagent was cancelled. The staged diff was reviewed in this session instead. No findings.

## Plan Change Log

## Review Triage Log

- 2026-10-13 quick: high 0, medium 0, low 0, false 0, maybe-false 0. The lens subagent was cancelled before it reported. The same diff was reviewed here against `Orders.open`, `Orders.release`, `SaleApi`, the README platform section, and the passing `PlatformSeamTest` cases. No finding to route.

## Design Notes

The storefront body is `{"userId","quantity"}`. The response is `{"outcome","result"}` with the sentence and the existing result value (`in_process`, `wait`, `refused`, `sold_out`, `not_accepted`, `unmatched`). Shopper outcomes stay HTTP 200, including not accepted. A rejected open stays HTTP 400 with the row unchanged.

`POST /sale` takes `{"itemId","quantity"}` and updates the single row in place. `GET /sale` returns `{"itemId","quantity"}`. Release returns `{"itemId","quantity","released"}`.

Release locks the acceptance row, then the customer, then the item. It subtracts only when the customer still has at least the stored units. Open sets the quantity to the given number. It does not add to the current pool.

Copy the page retry. Do not fold it into `admit`, because tests still need one turn-away from a single call.

## Verification

**Commands:**
- `./gradlew test` -- expected: exit 0, JaCoCo line and branch coverage at least 95%, existing `OrderPoolEventTest` hint cases still pass.
