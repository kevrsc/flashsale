---
title: 'One order reduces the pool and sends the event'
type: 'feature'
ticket: '1'
created: '2026-09-29'
status: done
baseline_revision: 'b62ff7041176dedca80301445c3aca616e0153a9'
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

**Problem:** There is no local service yet. A shopper cannot place one successful order that deducts a unit the pool still holds and sends the matching order event.

**Approach:** Stand up one Boot application on the host, Compose for PostgreSQL and Kafka, and the seeded item. One order through the page runs an inventory transaction whose pool update matches that row only while it still holds the units, committed with the order-event row. Kafka is sent after that commit.

**Decisions:**
- The Flyway seed inserts item id `item-1` with a starting pool of 10.

## Boundaries & Constraints

**Always:**
- One Boot process on the host. Compose runs `postgres:18.6` and `apache/kafka:4.3.1`. PostgreSQL is `localhost:5432`, database `flashsale`, user `flashsale`, password `flashsale`. Kafka is `localhost:9092`. Compose creates topic `inventory.order-accepted` before publish.
- Modules are `inventory` and `page`. Page depends on inventory and only renders. Inventory does not depend on page. Page runs no SQL and does not start the transaction. Inventory opens and commits it.
- The pool changes only by a conditional update on the seeded item id, and only while that row still holds the units this order deducts. The Modulith event inside that transaction is the only outbox write. A zero-row update rolls the transaction back, including any customer write, and leaves no outbox row. Kafka runs after the commit and after the page has the outcome. A broker delay does not change that outcome.
- The event is UTF-8 JSON with exactly `orderId`, `userId`, `itemId`, and `unitsDeducted`. `unitsDeducted` is the number the update subtracted. `orderId` is created in the transaction, stored in that commit, unique, never reused, and is the Kafka key. Topic is `inventory.order-accepted`. Inventory is the only producer.
- Flyway creates the item and customer tables and inserts the one item. Namastack JDBC creates the outbox tables. Hibernate does not alter schema. The page does not embed an item id; inventory reads the seeded row. The shopper enters a user ID and a quantity. On this success path the page says the order is in process and does not replace that outcome.
- Boot parent `4.1.1`, not `4.1.1.RELEASE`. Java 17. Gradle Groovy. Do not use Initializr's Modulith or Kafka checkboxes in place of `spring-modulith-starter-namastack` and `spring-modulith-events-kafka`. Externalization mode is `outbox`.

**Never:**
- Stories 2–7: admission bound, jitter, sold-out hint, customer-row writes, the one-hour wait, the limit of 3, a short-pool subset, sold out, not-accepted, and log totals.
- Payment, fulfillment, accounts, a second item, a hosted environment, restock, Avro, Protobuf, or a schema registry.
- Application SQL against outbox tables, a second process, or Compose DNS names in application config.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| One successful order | New user ID, quantity 1, seeded pool is 10 | Page says the order is in process. Pool is 9. One JSON message on `inventory.order-accepted` has `itemId` `item-1`, `unitsDeducted` 1, and the four fields only. | No error expected |
| Update matches nothing | The conditional update changes zero rows | Pool unchanged. No order event. Page does not say the order is in process. | Transaction rolls back and no outbox row remains |

</frozen-after-approval>

## Code Map

- No application code exists. Do not treat `.agents/`, `_bmad/`, or `_bmad-output/` as product code, and do not change the spec, spine, or ticket files while implementing.
- Predicted product paths, none present yet: `compose.yaml`, `build.gradle`, `settings.gradle`, `src/main/java/com/flashsale/`, `src/main/resources/application.properties`, `src/main/resources/db/migration/`, `src/main/resources/templates/`, `src/test/java/com/flashsale/`.

## Tasks & Acceptance

**Execution:**
- [ ] `build.gradle` -- The human generates the Gradle project at the repo root with Spring Initializr: Java 17, Gradle Groovy, Boot 4.1.1, jar, group `com.flashsale`, artifact `flashsale`. Do not select the Modulith or Kafka checkboxes. After that, the build file uses parent `4.1.1` and adds `spring-boot-starter-webmvc`, `spring-boot-starter-jdbc`, `spring-boot-starter-thymeleaf`, `spring-boot-starter-flyway`, `flyway-database-postgresql` 12.4.0, the PostgreSQL driver, `spring-modulith-starter-namastack` 2.1.1, and `spring-modulith-events-kafka` 2.1.1. -- This story's human step is the starter. The checkboxes are not the outbox stack.
- [ ] `compose.yaml` -- Run `postgres:18.6` and `apache/kafka:4.3.1`, publish `localhost:5432` and `localhost:9092`, and create topic `inventory.order-accepted`. -- AD-10. The app on the host uses localhost, not Compose DNS.
- [ ] `src/main/resources/application.properties` -- Point JDBC at `localhost:5432/flashsale` with user and password `flashsale`, Kafka at `localhost:9092`, Modulith externalization mode `outbox`, and Flyway on. Disable Hibernate DDL. -- One environment, outbox mode.
- [ ] `src/main/resources/db/migration/V1__item_and_customer.sql` -- Create the item table and the customer table with a unique user id. Insert item id `item-1` with quantity 10. -- AD-9. This story does not write customer rows.
- [ ] `src/main/java/com/flashsale/inventory/` -- Open one transaction, read the seeded item id, and conditionally deduct the requested units only while that row still holds them. On a match, publish one Modulith event in that transaction with a new UUID `orderId` stored in the commit. On zero rows, roll back. -- AD-1 and the success path of AD-3. No wait, limit, subset, or sold-out branch.
- [ ] `src/main/java/com/flashsale/page/` and `src/main/resources/templates/order.html` -- Thymeleaf form on `/` collects user ID and quantity, submits no item id, calls inventory in-process, and renders the returned in-process outcome. -- AD-2 and AD-7 for this path only.
- [ ] `src/test/java/com/flashsale/` -- Cover both matrix rows: quantity 1 deducts and emits `unitsDeducted` 1; a zero-row update leaves the pool and emits nothing. -- The verify check.

**Acceptance Criteria:**
- Given that order committed, when the page has already been given the outcome, then the Kafka send happens after that commit.
- Given inventory returns in process, when the page renders it, then the page shows that outcome and does not replace it.

## Implementation Notes

## Plan Change Log

## Review Triage Log

Quick review, one pass. Verdicts: high 0, medium 1, low 3, false 0, maybe-false 0. Two rejected, two patched.

- medium, patch — `OrderPoolEventTest.oneOrderReducesThePoolAndSendsTheEvent` requires the topic to stay empty for 200ms and then drops whatever that poll read. The test result file shows this run passed, and the code does fail a real success if the event arrives inside that window.
- low, reject — `Orders.place` accepts a non-positive quantity: zero still publishes, and a negative quantity increases the pool. The frozen intent leaves not-accepted, including a non-positive quantity, out of this story.
- low, reject — Kafka is sent by the outbox poller, which can tick after the commit and before the HTTP response is written. The page module already holds the outcome once `place` returns, the verification run saw no message in the first 200ms, and holding the publisher until the view finishes is more than a direct correction.
- low, patch — `OrderPoolEventTest.poll` returns on the first record, so `zeroRowUpdateLeavesThePoolAndSendsNothing` can stop before a later message for this order. The 4.6s test time shows this run waited the full window; the early return is still what the helper does.

## Design Notes

Package `com.flashsale` matches the project name. The form path `/` is the page module's choice; the spine left the path unset. `orderId` is a UUID string. The customer table is created here and unused until story 3.

The conditional update names the seeded id and the units just computed, and it changes a row only when the pool still holds those units. Example shape, not a script to copy blindly:

```sql
UPDATE item
SET quantity = quantity - :units
WHERE id = :itemId AND quantity >= :units
```

Event value, no envelope:

```json
{"orderId":"<uuid>","userId":"<typed id>","itemId":"item-1","unitsDeducted":1}
```

## Verification

**Commands:**
- `docker compose up -d` -- expected: PostgreSQL on `localhost:5432` and Kafka on `localhost:9092`, topic `inventory.order-accepted` present.
- `./gradlew test` -- expected: both matrix rows pass.

**Manual checks (if no CLI):**
- Submit user ID and quantity 1 on `/`. The page says the order is in process, the item pool is 1 less, and one message on `inventory.order-accepted` has `unitsDeducted` 1.
