---
type: initiative
title: "Flash-sale inventory"
parent: none
covers: [CAP-1, CAP-2, CAP-3, CAP-4, CAP-5, CAP-6, CAP-7, CAP-8]
after: []
assignee: ""
risk: high
---

# Flash-sale inventory

## Description

The builder can run a flash sale on one local service and see that the earliest successful orders take units from one item's inventory pool. The spec owns the capabilities, constraints, and non-goals. This initiative delivers them.

## Outcome

The builder demonstrates the spec's success signal on the local service: orders succeed only while the inventory pool is above zero, no unit is taken twice, no customer passes the customer limit, and the log shows the request and database totals.

## Done when

1. A customer orders while the inventory pool is above zero and sees that the order is in process, and those units are gone from the pool.
2. That customer is then told to wait one hour, and an order that would pass the customer limit is capped or refused.
3. An order against an empty pool sees sold out, with nothing deducted and no order event, including during the one-hour wait.
4. A short pool, or a request over the units the customer has left, succeeds for the smaller number, and the order event carries the units deducted.
5. The log shows request, query, and update totals. Those totals are not a success if a deducted unit was not in the pool or a customer exceeds the customer limit.
6. A person runs these checks on the local service. That service is the delivery environment for this initiative.

## Boundaries

This service only: the landing page and the inventory decision in one application. Not payment, fulfillment, accounts, a second item, a hosted environment, or restock. See the spec's non-goals. Tracer path: one typed user ID orders one unit and sees the order is in process, and the JSON order event is on the topic.

- Touch point: order service — consumes `inventory.order-accepted` and is not built here; owner: epic-local-flash-sale-inventory
- Touch point: account — the page collects a user ID and this service does not create the account; owner: epic-local-flash-sale-inventory

## References

- spec — _bmad-output/initiative-flash-sale-inventory/spec-flash-sale-inventory/spec-flash-sale-inventory.md, section Capabilities
- constraint — _bmad-output/initiative-flash-sale-inventory/spec-flash-sale-inventory/spec-flash-sale-inventory.md, section Constraints
- architecture — _bmad-output/initiative-flash-sale-inventory/architecture-flash-sale-inventory/architecture-flash-sale-inventory.md

## Notes

- Decision: one epic, Local flash-sale inventory, owns CAP-1 through CAP-8. The platform baseline is that epic's first stories, not a separate epic (user, 2026-09-29).
- Decision: the order service and accounts are touch points with no epic (user, 2026-09-29).
