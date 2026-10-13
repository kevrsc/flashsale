---
title: 'Live sale checks'
type: 'feature'
ticket: ''
created: '2026-10-13'
status: 'in-review'
review: 'quick'
review_source: 'pinned'
baseline_revision: 'c1a2727cf1ddfafb4b29e500905680306e7e3ede'
route: 'full'
route_source: 'auto'
lenses_ran: []
review_loop_iteration: 0
context: []
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** The sale outcomes are proven only inside `OrderPoolEventTest`. Nothing exercises a running Boot process, PostgreSQL, and Kafka together from the outside.

**Approach:** Add one bash script that resets the live pool and customers, then walks the shopper-visible sale sequence over `POST /` and checks the page sentence, the item quantity, the customer units, and whether Kafka gained a record.

## Boundaries & Constraints

**Always:** Use the sentences and the order in `theSaleChecksRunEndToEnd`. Talk to the running service with HTTP, `psql`, and the Kafka console tools already in Compose. Reset `item.quantity` to 10 and delete `customer` rows at the start. Treat this script as the only client while it runs. If quantity is 10 and the first order says sold out, stop and say the process must be restarted so the in-memory hint clears.

**Never:** A second Java test, a change to admission, orders, the page, or the schema. Clearing the sold-out hint, holding the item lock, or asserting Micrometer. Deleting outbox, instance, or partition rows. A shopper sentence for a turn-away.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Service down | `GET /` fails | Script exits non-zero before reset | Print that Boot is not reachable |
| Hint still set | Quantity 10, first `POST` is sold out | Exit non-zero | Tell the operator to restart the process |
| Three units | One user, quantity 1, three times | In process; pool 9 then 8 then 7; units 3; one Kafka record each, `unitsDeducted` 1 | Fail on the first mismatch |
| Wait | Same user, quantity 1, still inside the hour | Wait sentence; pool and units unchanged; Kafka offset unchanged | Fail if a record appears |
| Refused | Move `next_allowed` into the past, same user, quantity 1 | Refused; pool and units unchanged; Kafka offset unchanged | Fail if a record appears |
| Other customer | New user, quantity 3 | In process; that user's units 3; pool down by 3; one Kafka record with `unitsDeducted` 3 | Fail on mismatch |
| Short pool | Set quantity to 1, new user, quantity 3 | In process; pool 0; units 1; Kafka `unitsDeducted` 1 | Fail on mismatch |
| Sold out | Same user, quantity 1, pool 0 | Sold out, and not the wait sentence; pool 0; units unchanged; Kafka offset unchanged | Fail if a record appears |
| Blank id | `userId` empty, quantity 1, after sold out | Not accepted; pool unchanged; Kafka offset unchanged | Fail if a record appears |
| Zero and negative | Fresh ids, quantity 0 then -1, before any deduction | Not accepted; pool stays 10; no customer row; Kafka offset unchanged | Fail closed |

</frozen-after-approval>

## Code Map

- `src/test/java/com/flashsale/OrderPoolEventTest.java` — `theSaleChecksRunEndToEnd` (about lines 772–850) is the sequence. `resetQuantity` restores quantity 10 and deletes customers. `elapseTheHour` sets `next_allowed` one second in the past. Reuse those facts. Do not copy the meter, lock, or hint assertions.
- `src/main/java/com/flashsale/page/OrderPage.java` — `POST /` with `userId` and `quantity`. The outcome sentence is in the HTML.
- `src/main/java/com/flashsale/inventory/OrderOutcome.java` — the five sentences. Blank and non-positive quantity are "The order was not accepted".
- `src/main/java/com/flashsale/inventory/Admission.java` — a blank id is decided before the sold-out hint, so the blank check still works after the pool hits zero.
- `src/main/java/com/flashsale/inventory/Orders.java` — `clearSoldOutHint` is not an HTTP route. The product never clears the hint. A script reset of the row cannot undo it.
- `compose.yaml` — Postgres service `postgres`, database and user `flashsale`. Kafka service `kafka`, broker `kafka:19092`, topic `inventory.order-accepted`.
- `README.md` — local run is `docker compose up -d` then `./gradlew bootRun`, then `http://localhost:8080/`. Do not edit it.

## Tasks & Acceptance

**Execution:**
- [ ] `scripts/live-sale-checks.sh` -- Add the script. Reset the pool and customers, refuse to continue when the hint is still set, then run the matrix in order. Read Kafka from the offset captured before each order. -- This is the only deliverable.

**Acceptance Criteria:**
- Given Compose and Boot are up and the hint is clear, when the script runs, then it exits 0 and leaves quantity 0, three customer rows, and no customer above 3 units.
- Given Boot is down, when the script runs, then it exits non-zero and does not change the pool.
- Given the process already has the hint set and quantity is 10, when the script runs, then it exits non-zero and names a restart.

## Design Notes

Capture the topic end offset before an order. After a success, read from that offset until the new user's record appears or 8 seconds pass. After a refusal, wait 5 seconds and require the same end offset. The publisher poll is about 2 seconds, so a shorter wait can miss a send that should fail the check.

User ids are `live-` plus a random suffix, new for each run. Match `userId`, `itemId` `item-1`, and `unitsDeducted` in the JSON. The Kafka key equals `orderId`.

## Verification

**Commands:**
- `bash scripts/live-sale-checks.sh` -- expected: exit 0 when Compose and Boot are up and the hint is clear. A sold-out hint left from an earlier run exits non-zero and names a restart.

## Implementation Notes

- Kafka commands inside Docker are prefixed with `MSYS_NO_PATHCONV=1`. Git Bash otherwise rewrites `/opt/kafka/...` into a Windows path and the offset read fails.
- The console consumer's `--property` flag prints a deprecation line on stdout. The script uses `--formatter-property` and keeps the line that contains the JSON.
- `next_allowed` is `timestamp` without time zone. Setting it one second behind `clock_timestamp()` in `psql` still looked like a future wait to the JVM session. The script stores `2000-01-01` so the hour is in the past for either session.
- Verified with Git Bash. A request to port 9 printed "Boot is not reachable" and left quantity 7. The full sequence exited 0. A second run, with the hint set, printed the restart sentence and exited 1. That second run resets the pool to 10 before it detects the hint.

## Plan Change Log

## Review Triage Log
