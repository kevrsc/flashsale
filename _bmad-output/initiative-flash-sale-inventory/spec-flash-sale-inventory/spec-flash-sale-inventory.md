---
id: SPEC-flash-sale-inventory
companions:
  - glossary.md
  - ../architecture-flash-sale-inventory/architecture-flash-sale-inventory.md
sources:
  - ../prd-flash-sale-inventory/prd-flash-sale-inventory.md
---

> **Canonical contract.** This SPEC and the files in `companions:` are the complete, preservation-validated contract for what to build, test, and validate. Source documents listed in frontmatter are for traceability — consult them only if you need narrative rationale or prose color this contract intentionally omits.

# Flash-sale inventory

## Why

A limited item draws more people than there are units, often enough to need one standard process. This spec realizes that process for the builder who has to see it work: the earliest successful orders deduct from that item's inventory pool so those customers can purchase, and a unit taken for one customer is not given to another. Payment, fulfillment, and accounts stay outside this service.

## Capabilities

- **CAP-1**
  - **intent:** A customer can order one or more units of the item when the inventory pool holds at least those units, the order would not pass the customer limit, and the customer is not in the one-hour wait.
  - **success:** Those units are deducted, an order event is sent for them, the customer sees that the order is in process, and the units are not available to another customer.

- **CAP-2**
  - **intent:** A customer who orders when the inventory pool is not above zero sees that the item has sold out.
  - **success:** Nothing is deducted, no order event is sent, and sold out is shown even when that customer is also in the one-hour wait.

- **CAP-3**
  - **intent:** After a successful order, that customer must wait one hour before ordering again while the inventory pool is above zero.
  - **success:** An order during the one-hour wait is not a successful order. The customer is told to wait one hour. Nothing is deducted and no order event is sent.

- **CAP-4**
  - **intent:** The units in a customer's successful orders total at most 3, in one order or across two or three orders.
  - **success:** No successful order leaves that customer with more than 3 units. One order of 3 can meet the customer limit. Two or three successful orders can meet it when their units add up to 3, with the one-hour wait after each.

- **CAP-5**
  - **intent:** When the inventory pool could cover the full order and the order asks for more units than the customer has left under the customer limit, the order succeeds for the units left, as long as that number is at least one.
  - **success:** Those units are deducted, the order event is for the units deducted, the customer sees that the order is in process, and the customer's successful units do not exceed 3. When no units are left, the order is refused: nothing is deducted, no order event is sent, and the customer sees that the order is refused.

- **CAP-6**
  - **intent:** When the inventory pool holds at least one unit and the order asks for more than the pool holds, the order succeeds for a subset and the customer keeps that place in the request order.
  - **success:** The units deducted are the smaller of the units asked for, the units the pool holds, and the units left under the customer limit. The order event is for the units deducted. The customer sees that the order is in process. When no units are left under the customer limit, the order is refused as in CAP-5.

- **CAP-7**
  - **intent:** The builder can see how many order attempts arrived and how many database queries and updates ran during a sale.
  - **success:** Those totals are visible for the sale. The sale is not a success when a deducted unit was not in the inventory pool, or when a customer's successful units exceed the customer limit.

- **CAP-8**
  - **intent:** A blank user ID or a non-positive quantity is not an order.
  - **success:** Nothing is deducted, no order event is sent, and the customer sees that the order was not accepted.

## Constraints

- The deduction and the order event are one outcome. An event is not sent for units that were not deducted, and a deduction does not stand without the event.
- The landing page is part of this service. It collects the user ID and the quantity. It does not decide the inventory pool.
- The customer is recognized only by the user ID of an account this service does not create, manage, or authenticate.
- There is one item. Its inventory pool is the only count of units still available.
- Units go to the earliest successful orders.
- Order processing continues from the order event and does not decide whether the inventory pool allows another order.
- The service handles a high volume of order attempts, including contention for the shared pool.
- Request totals and database query and update totals are visible in the service log. A metrics endpoint does not replace that log.
- Consistency of the page, the inventory decision, publication, and the local runtime is governed by the architecture spine.

## Non-goals

- This service does not take payment, fulfill the item, or otherwise complete the purchase.
- This service does not create or manage accounts.
- This service does not sell a second item.
- This service does not run in a hosted environment.
- This service does not restock the inventory pool after it hits zero.
- The order event is not Avro or Protobuf, and it has no schema registry.

## Success signal

On the local service, a sale shows orders succeeding only while the inventory pool is above zero, no unit deducted for more than one customer, and no customer past the customer limit. The shopper sees in process, wait one hour, sold out, refused, or not accepted according to CAP-1 through CAP-8. Each order event carries the units deducted. The log shows the request total and the database query and update totals for that sale.
