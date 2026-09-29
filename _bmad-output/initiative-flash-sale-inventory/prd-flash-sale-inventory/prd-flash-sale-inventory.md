---
title: Flash-sale inventory
status: final
created: 2026-09-28
updated: 2026-09-29
---

# PRD: Flash-sale inventory

## 0. Document Purpose

This PRD is for the builder of Flash-sale inventory, and for the architecture and implementation that follow. Requirements use the glossary. The Kafka client, the database write, and how contention for shared storage is handled are recorded in `addendum.md`.

## 1. Vision

A limited item draws more people than there are units, and that happens often enough to need a standard process. Flash-sale inventory is that process. A customer orders the item on a landing page, and the earliest successful orders deduct from that item's inventory pool so those customers can purchase. The outcome stays fair when thousands of people arrive together: units are deducted for as many interested customers as the quantity allows, and a unit deducted for one customer is not also given to someone else. Completing the purchase stays with other services.

Robust means two things. No order deducts a unit that was not actually available. The service's own resources handle a high volume of requests, so contention for shared storage is part of the load the service has to withstand.

## 2. Target User

### 2.1 Jobs To Be Done

- The builder supports this service and needs to know it works as intended.
- Orders for an item continue if and only if that item's inventory pool is greater than zero.
- A single customer can successfully order at most 3 units in total, in one order or across two or three orders.
- A customer comes to purchase. The landing page tells them the order is in process, that they must wait one hour before ordering again, that the item has sold out, or that the order is refused.

### 2.2 Non-Users (v1)

- Order processing does not decide whether the inventory pool allows another order. It continues from the order event after this service accepts one.

### 2.3 Key User Journeys

**UJ-1. Alex orders while the item is still available.**

Alex wants to purchase the item. Alex lands on the page while the inventory pool is still above zero and orders one or more units. The service finds those units still available, deducts them from the inventory pool, and sends an order event. Alex sees that the order is in process. Other services can continue the purchase. When Alex tries to order again, the page says to wait one hour before ordering again. The units from Alex's successful orders can reach the customer limit in that one order, or across two or three orders. **Edge case:** Alex asks for more units than the inventory pool holds, and at least one unit remains. The units deducted are the smaller of the units Alex asked for, the units the pool holds, and the units Alex has left under the customer limit. The service sends an order event for those units, and Alex sees that the order is in process. Alex keeps that place in the request order.

**UJ-2. Jordan orders after the pool is gone.**

Jordan lands after Alex's order has taken the last unit and tries to order. The inventory pool is not above zero, so nothing is deducted and no order event is sent. Jordan sees that the item has sold out.

## 3. Glossary

- **Customer** — A person with an account. The service recognizes the customer by that account's user ID from one order to the next.
- **User ID** — The identifier of a customer's account. The service uses it to tell one customer from another.
- **Item** — The limited product a customer orders.
- **Inventory pool** — The units of an item still available to order.
- **Order** — A customer's request, from the landing page, to purchase one or more units of an item.
- **Successful order** — An order that deducts units from the inventory pool and sends an order event. The customer sees that the order is in process.
- **Order event** — The notice this service sends, after a successful order, so other services can continue the purchase.
- **Sold out** — The response when the inventory pool for that item is not greater than zero. Nothing is deducted and no order event is sent. This response is shown even when the customer is also in the one-hour wait.
- **One-hour wait** — After a successful order, that customer must wait one hour before ordering again.
- **Customer limit** — 3. The total number of units in successful orders by a single customer. Those units may be placed in one order or across two or three orders.
- **Refused** — The response when the whole order is refused. Nothing is deducted and no order event is sent. Used when the customer has no units left under the customer limit, the inventory pool is greater than zero, and the customer is not in the one-hour wait.

## 4. Features

### 4.1 Place an order

**Description:** A customer orders one or more units of an item from the landing page. The service recognizes the customer by the user ID of their account. A successful order deducts units from the inventory pool and sends an order event, and the customer sees that the order is in process. Realizes UJ-1 and UJ-2. The units deducted are the smaller of the units asked for, the units the inventory pool holds, and the units the customer has left under the customer limit. The customer keeps their place in the request order when that number is at least one. The one-hour wait constrains a later order by the same customer. A customer with no units left under the customer limit is refused.

**Functional Requirements:**

#### FR-1: Accept an order the pool and the customer limit can cover

A customer can place an order for one or more units of an item when the inventory pool holds at least those units, the order would not make that customer's successful units exceed the customer limit, and the customer is not in the one-hour wait. Realizes UJ-1.

**Consequences (testable):**
- The service deducts the ordered units from the inventory pool.
- The service sends an order event.
- The customer sees that the order is in process.
- The deducted units are not available to another customer.

**Out of Scope:**
- Payment, fulfillment, and the rest of order processing.

#### FR-2: Report sold out when the pool is empty

When the inventory pool for the item is not greater than zero, a customer who orders sees that the item has sold out. Realizes UJ-2. This remains the response when that customer is also in the one-hour wait.

**Consequences (testable):**
- Nothing is deducted from the inventory pool.
- No order event is sent.
- The customer sees that the item has sold out.

#### FR-3: Enforce the one-hour wait

After a successful order, that customer is in the one-hour wait and must wait one hour before ordering again.

**Consequences (testable):**
- An order from that customer during the one-hour wait is not a successful order.
- The customer is told to wait one hour before ordering again.
- Nothing is deducted and no order event is sent for that attempt.

#### FR-4: Enforce the customer limit

The units in a customer's successful orders total at most 3. Those units may be placed in one order or across two or three orders.

**Consequences (testable):**
- A successful order never leaves that customer with more than 3 units across their successful orders.
- One order of 3 units can meet the customer limit.
- Two or three successful orders can meet the customer limit when their units add up to 3, with the one-hour wait after each successful order.
- An order that would pass the customer limit is accepted only for the units the customer has left, when at least one unit is left. See FR-5. A customer with no units left is refused.

#### FR-5: Accept up to the customer limit

When the inventory pool could cover the full order, the customer is not in the one-hour wait, and the order asks for more units than the customer has left under the customer limit, the order is a successful order for the units the customer has left, as long as that number is at least one.

**Consequences (testable):**
- The service deducts the units the customer has left under the customer limit.
- The order event is for the units deducted.
- The customer sees that the order is in process.
- The customer's successful units do not exceed the customer limit.
- When the customer has no units left under the customer limit, the whole order is refused: nothing is deducted, no order event is sent, and the customer sees that the order is refused.

#### FR-6: Accept the remaining pool when the request exceeds it

When the customer is not in the one-hour wait, the inventory pool holds at least one unit, and the order asks for more units than the inventory pool holds, the order is a successful order for a subset. The customer keeps their place in the request order. Realizes the edge case on UJ-1. The customer limit still applies.

**Consequences (testable):**
- The units deducted are the smaller of the units asked for, the units the inventory pool holds, and the units the customer has left under the customer limit.
- The order event is for the units deducted.
- The customer sees that the order is in process.
- The service deducts no more units than the inventory pool holds.
- When the customer has no units left under the customer limit, the whole order is refused, as in FR-5.

**Feature-specific NFRs:**
- A successful order never deducts a unit that was not in the inventory pool.
- The service's own resources handle a high volume of orders. Contention for shared storage is part of that load. The approach is recorded in `addendum.md`.

## 5. Non-Goals (Explicit)

- This service does not take payment, fulfill the item, or otherwise complete the purchase. Other services continue from the order event.
- This service does not deduct more units than the inventory pool holds, and it does not let a customer's successful units exceed the customer limit.
- This service does not let order processing decide whether the inventory pool allows another order.
- This service does not create or manage accounts. A customer already has an account, and the service uses its user ID.

## 6. MVP Scope

The hobby version is the full behavior in §4 and the logging in §7. Nothing in §4 is deferred.

### 6.1 In Scope

- The behavior in §4, including recognition by user ID.
- Logging that shows the total number of requests, and how many database queries and updates occurred.

### 6.2 Out of Scope for MVP

- The non-goals in §5.

## 7. Success Metrics

The builder can see traffic and resource usage. Logging is how those counts are visible.

**Primary**
- **SM-1**: Total number of requests during a sale. Validates the high-volume behavior on Place an order.
- **SM-2**: Number of database queries and number of database updates during a sale. Validates resource usage for that same load.

**Counter-metrics (do not optimize)**
- **SM-C1**: Request volume and database activity are not the goal by themselves. A sale is not a success when a deducted unit was not in the inventory pool, or when a customer's successful units exceed the customer limit. Counterbalances SM-1 and SM-2.

## 8. Open Questions

None.

## 9. Assumptions Index

None.

