---
title: Seckill hot-path
status: final
created: 2026-10-14
updated: 2026-10-14
---

# PRD: Seckill hot-path

## 0. Document Purpose

This PRD is for the builder of a learning model of a production seckill sale, and for the architecture that follows. It replaces the behavior of the current flash-sale service for this effort. The finalized PRD at `prd-flash-sale-inventory` stays as the record of that earlier service. Requirements use the glossary. Named technologies and the published seckill notes live in `addendum.md`.

## 1. Vision

A standard inventory service sets aside a limited quantity of one hot item. This service sells only that allotment. A crowd arrives together, on the order of 100,000 customers against a pool of 100 within minutes of the start. The earliest qualifications take the units. A unit taken for one customer is not given to another. Warehouse stock on hand stays with the inventory service.

The customer learns the result from the shared pool. The durable order, and the report back to the inventory service, follow without holding that crowd on a disk write. Attempts that cannot qualify are answered before they contend for the pool.

## 2. Target User

### 2.1 Jobs To Be Done

- The builder runs this learning model and checks that a burst awards only the allotted units.
- The inventory service loads one allotment, opens the sale, and later adjusts stock on hand from the qualification reports and the close report, without posting the same unit twice.
- A customer who is already authenticated and already authorized attempts to take the item and learns whether they qualified.

### 2.2 Non-Users (v1)

- Payment, fulfillment, account management, and the normal catalog stay with other services.
- A shopper page is not part of this service. Customers arrive through an edge in front of this service.

### 2.3 Key User Journeys

**UJ-1. Mina qualifies for the last units.**

Mina is already authenticated and already authorized to attempt the sale. The sale is open and the pool still has a unit. She attempts once. The attempt passes the filter, the pool loses one unit, and she is told she qualified. An order is recorded afterward, and the inventory service is told without her waiting on that report. **Edge case:** her attempt takes the last unit. She still qualifies. The next person does not.

**UJ-2. Omar arrives after the pool is gone.**

Omar is already authorized. The sale is open and the pool has no unit left, or the filter already knows the sale is sold out. He attempts. He is told the item is sold out. The pool does not change. No order is written for him.

**UJ-3. The inventory service runs the allotment.**

Before the rush, the inventory service loads an allotment of 100 for one item. A start signal opens the sale. During the sale it learns that units were qualified. When it closes the sale, it learns how many units remain. **Edge case:** a report is delayed. Those units are not qualified a second time.

## 3. Glossary

- **Inventory service** — The separate stock-on-hand system. It loads the allotment and receives reports. It is the ledger of warehouse stock.
- **Customer** — A person already authenticated and already authorized to attempt the sale. This service does not create or look up an account.
- **Allotment** — The quantity of one item the inventory service loads for one sale. The sale cannot create units beyond it.
- **Sale** — One allotment opened for attempts. It accepts qualifications only while it is open and the pool has a unit.
- **Pool** — The units of the allotment still available to qualify. One pool for the sale, shared by every running instance.
- **Start signal** — The inventory service's act that opens the sale. Loading the allotment and opening the sale are separate. Loading the allotment does not by itself open it.
- **Attempt** — A customer's request to take one unit.
- **Qualification** — An attempt that takes one unit from the pool. The customer is told they qualified. The unit is taken at qualification.
- **Qualification id** — The identifier assigned when a unit is taken. The order and the qualification report use it. One qualification id has one order.
- **Order** — The durable record of one qualification id. Another service continues from it. The customer response does not wait on the write.
- **Sold out** — The answer when the sale is open and the pool has no unit left. The pool does not change and no order is written.
- **Not open** — The answer before the start signal. The pool does not change and no order is written.
- **Closed** — The answer after close. The pool does not change and no order is written.
- **Unidentified** — The answer when the attempt has no customer identity. The pool does not change and no order is written.
- **Edge** — The filter in front of this service. It admits attempts to the qualification path. The named pieces are in `addendum.md`.
- **Qualification report** — Notice that one qualification took one unit. A repeat of that notice is the same fact. The attempt response does not wait on it.
- **Close report** — Notice of the units still in the pool when the sale closes. A repeat carries the same remaining count.
- **Close** — The inventory service ends the sale. Remaining pool units are included in the close report. This service has no sale clock of its own.

## 4. Features

### 4.1 Open and close the sale

**Description:** The inventory service loads one allotment and then opens the sale. While the sale is closed, attempts take no units. Closing the sale stops new qualifications and reports the units still in the pool. Realizes UJ-3.

**Functional Requirements:**

#### FR-1: Load one allotment

The inventory service can load an allotment for one item. Realizes UJ-3.

**Consequences (testable):**
- Before the start signal, the answer is not open. The attempt takes no unit and writes no order.
- The last load before the start signal sets the allotment. The pool at the start signal equals that allotment.
- One open sale covers one item and one allotment. A load while the sale is open does not change the pool.
- The service cannot qualify more units than the allotment.

#### FR-2: Close the sale

The inventory service can close an open sale. Realizes UJ-3.

**Consequences (testable):**
- After close, the answer is closed. The attempt takes no unit and writes no order.
- Close sends the close report: the units still in the pool.
- A second close sends the same close report and does not qualify or restore units on its own.

### 4.2 Qualify an attempt

**Description:** An authorized customer attempts to take one unit. The earliest attempts that find a unit take it. A later attempt by the same customer may qualify again while units remain. An attempt the sale cannot accept is answered without a pool decrement. Every instance shares the pool. Realizes UJ-1 and UJ-2.

**Functional Requirements:**

#### FR-3: Qualify one unit

A customer can qualify for one unit while the sale is open and the pool has a unit. Realizes UJ-1.

**Consequences (testable):**
- The pool decreases by one.
- The customer is told they qualified.
- The same unit is not part of a second qualification.
- Across 100,000 attempts and an allotment of 100, the number of qualifications is 100.
- A customer may qualify again on a later attempt while units remain. There is no per-customer cap and no cooldown.
- An attempt with no customer identity is answered unidentified. The attempt takes no unit and writes no order.
- Any payment outcome leaves that unit taken.

#### FR-4: Answer attempts that cannot qualify

An attempt against a sale that is not open, or against a pool with no unit left, receives the matching answer and leaves the pool unchanged. Realizes UJ-2.

**Consequences (testable):**
- Sold out is the answer when the sale is open and the pool has no unit left.
- Not open is the answer before the start signal. Closed is the answer after close.
- Each of those answers takes no unit and writes no order.

#### FR-5: Keep the crowd off the pool

Attempts that cannot qualify are answered before they decrement the pool. Realizes UJ-2.

**Consequences (testable):**
- Once the pool is known empty, or the sale is known not open or is known closed, an attempt is answered with no pool decrement.
- An attempt that arrives past the edge still cannot take a unit the pool does not have.

#### FR-8: Separate sale-state reads from qualification

A read of whether the sale is open, whether it is sold out, or how many units remain does not take the qualification write path. Realizes UJ-2.

**Consequences (testable):**
- A read does not decrement the pool and does not write an order.
- A burst of reads does not block qualifications behind the same lock as the read.

#### FR-6: Share one pool across instances

Every running instance qualifies against the same pool. The qualification decision uses that shared count. Realizes UJ-1.

**Consequences (testable):**
- Two instances cannot both qualify the last unit.
- The decision does not read the orders to learn how many units remain.
- One instance and many instances award the same allotment.

### 4.3 Record the order and report stock

**Description:** The customer is answered from the pool. The order is written afterward. The inventory service learns of qualifications during the sale and of remaining units at close. Realizes UJ-1 and UJ-3.

**Functional Requirements:**

#### FR-7: Write the order after qualification

A qualification has one order, written so another service can continue. Realizes UJ-1.

**Consequences (testable):**
- The customer is told they qualified when the pool decrement succeeds, without waiting on the order write.
- That decrement assigns one qualification id.
- That qualification id produces one order. A retry uses the same qualification id and does not produce a second order.
- Until the order exists, the unit stays assigned to that qualification id and is not granted again.
- A qualification id with no order fails the build.

#### FR-9: Report stock changes to the inventory service

The inventory service can learn how many units were qualified and how many remain. Realizes UJ-3.

**Consequences (testable):**
- A qualification is followed, during the sale, by one qualification report for that qualification id. The attempt response does not wait on it.
- Close sends one close report.
- A repeated qualification report does not qualify another unit and does not describe a second unit. A repeated close report carries the same remaining count.
- Applying those facts to warehouse stock is the inventory service's rule. Posting both facts does not remove the same unit twice.

### 4.4 Keep the core path up

**Description:** Work that is not qualification yields when it fails. The builder can see what the sale did. Realizes UJ-1.

**Functional Requirements:**

#### FR-10: Shed non-essential work

A failure of the order write, the report, or telemetry export does not grant an extra unit. Realizes UJ-1.

**Consequences (testable):**
- Qualification still returns from a successful pool decrement when telemetry export is down.
- A failed report is retried and does not decrement the pool again.
- When a downstream call is failing or shed, the qualification still decrements the pool and grants no extra unit.
- Work that is not the pool decision yields rather than stalling qualifications.

#### FR-11: Observe and test the sale

The builder can see qualifications, sold-out answers, and reports, and can prove the pool rules with automated tests.

**Consequences (testable):**
- Metrics, traces, and logs are exported.
- A log lets the builder tell a qualification from sold out, not open, closed, and unidentified, and tie a qualification to its order.
- Automated tests cover FR-1 through FR-10.
- The build fails when a qualification id has no order.
- The build fails when those tests fail.

## 5. Cross-Cutting NFRs

- The sale absorbs 100,000 attempts against an allotment of 100 within minutes. Qualifications equal the allotment. Rejected attempts do not each become an order write.
- The pool count used for FR-6 is a shared in-memory count. The named store and the queue, edge, and circuit-breaker choices are in `addendum.md`.
- A slow or unavailable collector does not change who qualified.

## 6. Integration

- The inventory service loads the allotment, sends the start signal, closes the sale, and consumes reports. It does not share this service's pool store.
- Customer attempts arrive through an edge that filters and rate-limits before the pool. This service still enforces FR-5 when an attempt arrives.
- Orders leave through a queue to a durable record. Payment and fulfillment consume that record elsewhere.
- Catalog text, recommendations, and reviews are not this service's to serve or to turn off.

## 7. Non-Goals

- This service is not the stock-on-hand ledger.
- No account creation, login, payment, or fulfillment.
- No shopper page, no per-customer cap, no limit of 3, no cooldown, no one-hour wait, and no partial quantity. Those belonged to the earlier service.
- No sale clock inside this service. A taken unit is not returned when payment does not finish.
- Modulith is not a requirement. A current library stays only when the architecture chooses it for the behavior in this PRD.
- No second sale beside the one open allotment.

## 8. Success Metrics

**Primary**

- **SM-1**: Qualifications equal the allotment, every qualification took a unit the pool held, and every qualification id has one order. For the learning burst, 100 qualifications and 100 orders from 100,000 attempts against an allotment of 100. Validates FR-3, FR-4, FR-6, FR-7.

**Secondary**

- **SM-2**: A sold-out, not-open, closed, or unidentified attempt writes no order. Each qualification has one qualification report, and close has one close report of the units still in the pool. Validates FR-4, FR-5, FR-9.

**Counter-metrics (do not optimize)**

- **SM-C1**: A fast rejection, a quiet durable store, or a low error rate is not success when more units were qualified than the allotment, when attempts still remained and the allotment was not awarded, or when the inventory service never receives the reports. Counterbalances SM-1 and SM-2.
