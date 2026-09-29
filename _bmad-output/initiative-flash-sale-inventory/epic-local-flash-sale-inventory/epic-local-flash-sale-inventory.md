---
type: epic
title: "Local flash-sale inventory"
parent: initiative-flash-sale-inventory
covers: [CAP-1, CAP-2, CAP-3, CAP-4, CAP-5, CAP-6, CAP-7, CAP-8]
after: []
assignee: ""
risk: high
---

# Local flash-sale inventory

## Description

One local Spring Boot application takes orders for one item. The earliest successful orders deduct from that item's inventory pool and send an order event. The spec owns the rules. This epic is the whole initiative.

## Outcome

The builder demonstrates the spec's success signal on the local service.

## Requirements

- CAP-1: A customer orders units the inventory pool and the customer limit can cover, sees that the order is in process, and those units are deducted. (spec CAP-1)
- CAP-2: An order against an empty pool sees sold out, including during the one-hour wait, with no deduction and no order event. (spec CAP-2)
- CAP-3: After a successful order, that customer waits one hour before ordering again while the pool is above zero. (spec CAP-3)
- CAP-4: A customer's successful units total at most 3, in one order or across two or three orders. (spec CAP-4)
- CAP-5: When the pool could cover the request and the customer has some units left under the limit, the order succeeds for the units left. None left is refused. (spec CAP-5)
- CAP-6: When the pool holds at least one unit and the request exceeds it, the order succeeds for the smaller of the request, the pool, and the units left. (spec CAP-6)
- CAP-7: The builder can see order-attempt, query, and update totals. Those totals are not success if a deducted unit was unavailable or a customer exceeds 3. (spec CAP-7)
- CAP-8: A blank user ID or a non-positive quantity is not an order. The customer sees that the order was not accepted. (spec CAP-8)

## Done when

1. CAP-1 works on the local service: an order while the pool is above zero shows that the order is in process, and those units are gone.
2. CAP-3 and CAP-4 work: the one-hour wait is enforced, and an order that would pass 3 is capped or refused.
3. CAP-2 works: an empty pool shows sold out with no deduction and no order event, including during the wait.
4. CAP-5 and CAP-6 work: the units deducted are the smaller of the request, the pool, and the units left, and the order event carries that number.
5. CAP-7 and CAP-8 work: the log shows the three totals, and a blank attempt changes nothing.
6. A person runs these checks on the local service.

## Boundaries

The landing page and the inventory module in one application, plus local PostgreSQL and Kafka. Not payment, fulfillment, accounts, a second item, a hosted environment, or restock. See the spec's non-goals.

## References

- parent — _bmad-output/initiative-flash-sale-inventory/initiative-flash-sale-inventory.md, section Done when
- spec — _bmad-output/initiative-flash-sale-inventory/spec-flash-sale-inventory/spec-flash-sale-inventory.md, section Capabilities
- constraint — _bmad-output/initiative-flash-sale-inventory/spec-flash-sale-inventory/spec-flash-sale-inventory.md, section Constraints
- architecture — _bmad-output/initiative-flash-sale-inventory/architecture-flash-sale-inventory/architecture-flash-sale-inventory.md
- glossary — _bmad-output/initiative-flash-sale-inventory/spec-flash-sale-inventory/glossary.md

## Notes

- Decision: this is the only epic. Scaffold, Compose, and the database seed are its first story, not a separate epic (user, 2026-09-29).
- Decision: the order service and accounts stay touch points. This epic publishes the order event and collects the user ID (user, 2026-09-29).
- Decision: entry 1 is the tracer bullet. The stories are one lane, in build order. Entry 2, the admission bound, follows the tracer because it is the uncertain slice (user approved the set, 2026-09-29).
- Decision: entry 5 includes the sold-out hint. Entry 9 is the closing end-to-end suite, after the refactor sweep (user, 2026-09-29).
- Decision: the numeric admission bound stays a setting on entry 2. It is not chosen in this breakdown (2026-09-29).
