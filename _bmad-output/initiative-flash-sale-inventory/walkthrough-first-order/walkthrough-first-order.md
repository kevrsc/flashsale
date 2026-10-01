# First successful order

Current block: 1. Intent

## Blocks

- [ ] **1. Intent** — in progress
- [ ] **2. Broad strokes** — unvisited
- [ ] **3. The page takes the order** — unvisited
- [ ] **4. The deduction and the event** — unvisited
- [ ] **5. Where it runs** — unvisited
- [ ] **6. Periphery** — unvisited

## 1. Intent

**Problem:** There is no local service yet. A shopper cannot place one successful order that deducts a unit the pool still holds and sends the matching order event.

**Approach:** Stand up one Boot application on the host, Compose for PostgreSQL and Kafka, and the seeded item. One order through the page runs an inventory transaction whose pool update matches that row only while it still holds the units, committed with the order-event row. Kafka is sent after that commit.

**Decisions:**
- The Flyway seed inserts item id `item-1` with a starting pool of 10.

Taken verbatim from the Intent section of the story plan you approved before this commit.

## 2. Broad strokes

One process on the host takes the form, and inventory updates the seeded row only while it still holds the units. The order event is written in that same database commit. Kafka is sent after.

Open these in order:

- [order.html](../../../src/main/resources/templates/order.html) — the form the shopper fills in
- [OrderPage.java](../../../src/main/java/com/flashsale/page/OrderPage.java) — sends that form to inventory and shows whatever inventory returns
- [Orders.java](../../../src/main/java/com/flashsale/inventory/Orders.java) — deducts the units and publishes the event in one transaction
- [V1__item_and_customer.sql](../../../src/main/resources/db/migration/V1__item_and_customer.sql) — inserts `item-1` with quantity 10
- [compose.yaml](../../../compose.yaml) — PostgreSQL and Kafka, reached at localhost

## 3. The page takes the order

The shopper types a user ID and a quantity. The form does not carry an item id. The page calls inventory in-process and prints the message inventory returns. It does not decide the pool.

- [order.html](../../../src/main/resources/templates/order.html) — posts `userId` and `quantity` to `/`
- [OrderPage.java](../../../src/main/java/com/flashsale/page/OrderPage.java:26) — `order` passes those two fields to `Orders.place` and puts the returned message on the page

## 4. The deduction and the event

Inventory reads the one seeded item id, then updates that row only when the pool still holds the requested units. On a match it publishes one event in the same transaction, with a new UUID as the order id. If the update changes nothing, the transaction rolls back and no event is written.

- [Orders.java](../../../src/main/java/com/flashsale/inventory/Orders.java:24) — `place` runs the conditional update and, only on a match, publishes `OrderAccepted`
- [OrderAccepted.java](../../../src/main/java/com/flashsale/inventory/OrderAccepted.java:4) — the event goes to `inventory.order-accepted`, keyed by `orderId`
- [V1__item_and_customer.sql](../../../src/main/resources/db/migration/V1__item_and_customer.sql:10) — the row the update has to match

## 5. Where it runs

PostgreSQL and Kafka run in Compose. The Boot process stays on the host and uses localhost, not Compose service names. Compose creates the topic before the application publishes.

- [compose.yaml](../../../compose.yaml:2) — `postgres:18.6` on port 5432 and `apache/kafka:4.3.1` on port 9092
- [compose.yaml](../../../compose.yaml:39) — `kafka-init` creates `inventory.order-accepted`
- [application.properties](../../../src/main/resources/application.properties:3) — JDBC and Kafka point at localhost, and externalization mode is `outbox`

## 6. Periphery

- [build.gradle](../../../build.gradle:3) — Boot 4.1.1, Java 17, and the Namastack and Kafka event libraries
- [application.properties](../../../src/main/resources/application.properties:11) — turns off the classic Modulith publication registry so the outbox stack can start
- [inventory/package-info.java](../../../src/main/java/com/flashsale/inventory/package-info.java:1) — inventory depends on no other module
- [page/package-info.java](../../../src/main/java/com/flashsale/page/package-info.java:1) — the page may depend on inventory
- [OrderPoolEventTest.java](../../../src/test/java/com/flashsale/OrderPoolEventTest.java:50) — one order takes the quantity from 10 to 9 and sends one event; a zero-row update sends none
