---
id: SPEC-seckill-hot-path
companions:
  - ../architecture-seckill-hot-path/architecture-seckill-hot-path.md
sources:
  - ../prd-seckill-hot-path/prd-seckill-hot-path.md
  - ../prd-seckill-hot-path/addendum.md
---

> **Canonical contract.** This SPEC and the files in `companions:` are the complete, preservation-validated contract for what to build, test, and validate. Source documents listed in frontmatter are for traceability — consult them only if you need narrative rationale or prose color this contract intentionally omits.

# Seckill hot-path

## Why

This is a learning model of a production seckill. An inventory service sets aside a limited quantity of one hot item. On the order of 100,000 customers arrive within minutes against a pool of 100. The earliest qualifications take the units. A unit taken for one customer is not given to another. Warehouse stock on hand stays with the inventory service. The customer learns the result from the shared pool. The durable order and the report back to inventory follow without holding that crowd on a disk write.

## Capabilities

- **CAP-1**
  - **intent:** The inventory service can load one allotment for one item without opening the sale.
  - **success:** The last load before the start signal sets the allotment. A load while the sale is open does not change the pool. The service cannot qualify more units than that allotment.
- **CAP-2**
  - **intent:** The inventory service can open the loaded sale with a start signal that is separate from the load.
  - **success:** Before the start signal an attempt is answered not open, takes no unit, and writes no order. At the start signal the pool equals the loaded allotment.
- **CAP-3**
  - **intent:** The inventory service can close the open sale.
  - **success:** After close an attempt is answered closed, takes no unit, and writes no order. Close reports the units still in the pool. A second close reports that same count and does not qualify or restore units.
- **CAP-4**
  - **intent:** A customer can qualify for one unit while the sale is open and the pool has a unit.
  - **success:** The pool decreases by one, the customer is told they qualified, and that unit is not part of a second qualification. The same customer may qualify again on a later attempt while units remain. An attempt with no customer identity is answered unidentified and takes no unit. Any payment outcome leaves the unit taken. Across 100,000 attempts and an allotment of 100, qualifications equal 100.
- **CAP-5**
  - **intent:** An attempt the sale cannot accept receives the matching answer and leaves the pool unchanged.
  - **success:** Sold out is the answer when the sale is open and the pool is empty. Not open is the answer before the start signal. Closed is the answer after close. Each takes no unit and writes no order.
- **CAP-6**
  - **intent:** An attempt already known not to qualify is answered without a pool decrement.
  - **success:** Once the pool is known empty, or the sale is known not open or known closed, the attempt is answered with no decrement. An attempt that arrives past that filter still cannot take a unit the pool does not have.
- **CAP-7**
  - **intent:** A reader can learn whether the sale is open, whether it is sold out, or how many units remain without taking the qualification path.
  - **success:** The read does not decrement the pool and does not write an order. A burst of reads does not block qualifications behind the same lock as the read.
- **CAP-8**
  - **intent:** Every running instance qualifies against one shared pool.
  - **success:** Two instances cannot both qualify the last unit. The decision does not read orders to learn how many units remain. One instance and many instances award the same allotment.
- **CAP-9**
  - **intent:** A qualification produces one order that another service can continue from, after the customer has been answered.
  - **success:** The customer is told they qualified when the decrement succeeds, without waiting on the order write. The decrement assigns one qualification id. That id produces one order. A retry uses the same id and does not produce a second order. Until the order exists the unit stays assigned and is not granted again. A qualification id with no order fails the build.
- **CAP-10**
  - **intent:** The inventory service can learn during the sale that a qualification took one unit, and at close how many units remain.
  - **success:** Each qualification is followed during the sale by one qualification report for that id. The attempt response does not wait on the report. Close sends one close report of the units still in the pool. A repeated report is the same fact. A repeated close report carries the same remaining count. Warehouse posting of those facts does not remove the same unit twice, and that posting rule belongs to the inventory service.
- **CAP-11**
  - **intent:** A failure of the order write, the stock report, or telemetry export does not grant an extra unit or stall qualification.
  - **success:** Qualification still returns from a successful decrement when telemetry export is down. A failed report is retried and does not decrement again. When a downstream call is failing or shed, the qualification still decrements the pool and grants no extra unit.
- **CAP-12**
  - **intent:** The builder can see what the sale did and can prove the pool rules with automated tests.
  - **success:** Metrics, traces, and logs are exported. A log distinguishes qualified, sold out, not open, closed, and unidentified, and ties a qualification to its order. Tests cover CAP-1 through CAP-11. The build fails when a qualification id has no order and when those tests fail.

## Constraints

- The customer is already authenticated and already authorized. This service does not create or look up an account.
- One open sale, one item, one allotment. Each attempt takes one unit. There is no per-customer cap and no cooldown.
- The architecture spine is binding. AD-1 through AD-10 fix the pool, the edge, the order, the reports, the breaker, and the runtime. A build that contradicts an AD is out of contract.
- This effort replaces the current flash-sale service. The shopper page, the limit of 3, the one-hour wait, partial quantity, Modulith, and the SQL outbox are not carried forward. The spine's Java 17 root toolchain applies only while that `src/` tree is still in the repo.
- The OpenTelemetry collector, Prometheus, Grafana, meaningful logs, and the JaCoCo 95% line and 95% branch gate stay. A slow or unavailable collector does not change who qualified.

## Non-goals

- This service is not the stock-on-hand ledger.
- No account creation, login, payment, or fulfillment.
- No shopper page, no per-customer cap, no limit of 3, no cooldown, no one-hour wait, and no partial quantity.
- No sale clock inside this service. A taken unit is not returned when payment does not finish.
- No second sale beside the one open allotment.
- Catalog text, recommendations, and reviews are not this service's to serve or to turn off.
- Modulith is not a requirement.

## Success signal

- For the learning burst, 100 qualifications and 100 orders come from 100,000 attempts against an allotment of 100, and every qualification id has one order. A sold-out, not-open, closed, or unidentified attempt writes no order. Each qualification has one qualification report, and close has one close report of the units still in the pool. A fast rejection, a quiet store, or a low error rate is not success when more units were qualified than the allotment, when units remained and the allotment was not awarded, or when the inventory service never receives the reports.
