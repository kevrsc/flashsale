# Reconciliation: PRD §6–§7 and the spec against the amended spine

Verdict: **source sentences still name the log**

The spine was not edited. The PRD was not edited. The spec was not edited.

- PRD: `prd-flash-sale-inventory/prd-flash-sale-inventory.md` (§6, §7)
- Spec: `spec-flash-sale-inventory/spec-flash-sale-inventory.md` (Constraints, Success signal)
- Spine: `architecture-flash-sale-inventory/architecture-flash-sale-inventory.md` (AD-8, AD-11 amended)

AD-8 and AD-11 are the authorized tally. `sale.attempts` and `sale.statements` on the single Micrometer registry are the sale counts. The application log does not print running request, success, query, or update totals. This review records where the sources still say otherwise. It does not recommend reversing that amendment.

## Sentences that still require the log as the tally

These sentences did not land. The counts they name did land, on the counters and on the Grafana sale dashboard.

| Source | Section | Sentence |
| --- | --- | --- |
| PRD | §6 MVP Scope | The hobby version is the full behavior in §4 and the logging in §7. |
| PRD | §6.1 In Scope | Logging that shows the total number of requests, and how many database queries and updates occurred. |
| PRD | §7 Success Metrics | Logging is how those counts are visible. |
| Spec | Constraints | Request totals and database query and update totals are visible in the service log. |
| Spec | Constraints | A metrics endpoint does not replace that log. |
| Spec | Success signal | The log shows the request total and the database query and update totals for that sale. |

What the spine does with the same counts:

- AD-8: the request total is the sum of `sale.attempts`. Query and update totals are `sale.statements` with `kind` `query` or `update`. The log does not print those running totals.
- AD-11: those counters are the sale tally on the OTLP metrics export. The provisioned Grafana dashboard includes `sale.attempts` by `result` and `sale.statements` by `kind`. The console log does not print those running totals. The HTTP server request timer is not that tally.

The constraint's second sentence still treats the log as the tally a metrics view must not replace. The spine still bars the narrower substitute: an HTTP or JDBC auto-metric, and a Grafana panel that uses the HTTP server request timer, standing in for `sale.attempts` and `sale.statements`. The authorized tally is those two counters.

## Quiet requirements the spine dropped

### 1. SM-C1's "not the goal" clause

PRD §7, counter-metrics (do not optimize): "Request volume and database activity are not the goal by themselves." The same bullet then says a sale is not a success when a deducted unit was not in the inventory pool, or when a customer's successful units exceed the customer limit.

What landed: AD-8 binds SM-C1. The counters do not make a sale successful. A deducted unit that was not in the pool, or a customer above the limit of 3, fails SM-C1. AD-3's conditional update is what keeps that failure from happening.

What the AD structure dropped: the clause that request volume and database activity are not the goal by themselves, and the section's "do not optimize." Nothing in AD-8 or AD-11 says the Grafana series for `sale.attempts` and `sale.statements` are not the thing to tune. A builder can still treat those panels as the sale's score.

### 2. The builder can see the totals when the export path has not delivered them

PRD §7: "The builder can see traffic and resource usage." Spec success signal and CAP-7 ask for those totals for the sale. CAP-7 does not name the log. The live path that landed is Grafana, after the OTLP metrics export, with the export step left at the Spring Boot default.

What the AD structure dropped: a way to see the totals that does not depend on that export. AD-8 keeps the counters in process memory and resets them when the process restarts, and it forbids the log lines that used to remain after they were written. AD-11 allows a slow or unavailable collector, and that failure does not change the shopper outcome. The spine does not require the counters to be exported before the process exits, and it does not name another reader. Until a scrape has landed in Prometheus, the builder has no surface the spine allows for SM-1 and SM-2.

This is the remainder of "the builder can see," after the log channel is set aside. It is not a reason to print the running totals.

## Coverage

### PRD §6

| Text | Result |
| --- | --- |
| The hobby version is the full behavior in §4 and the logging in §7. | Behavior landed (FR-1 through FR-6 are bound; the deferred list does not postpone a §4 behavior). The logging half is the first sentence in the table above. |
| Nothing in §4 is deferred. | Landed. Deferred items are the gate's concurrency number, order-id text format, starting pool quantity, exemplars, the form's HTTP path, process supervision, a hosted environment, Tempo/Loki, and restock. |
| §6.1 The behavior in §4, including recognition by user ID. | Landed (AD-6, AD-7). |
| §6.1 Logging that shows the request total and the query and update totals. | Sentence above. The counts landed on AD-8. |
| §6.2 The non-goals in §5 are out of scope. | Landed (AD-5, AD-7). No payment, fulfillment, account store, or order-service decision about the pool. |

### PRD §7

| Text | Result |
| --- | --- |
| The builder can see traffic and resource usage. | Landed for a run whose OTLP export has reached Grafana. Finding 2 is the path before that export. |
| Logging is how those counts are visible. | Sentence above. |
| SM-1 Total number of requests during a sale. Validates the high-volume behavior on Place an order. | Landed on `sale.attempts`. One `admit` increments once, including `in_process`, `wait`, `refused`, `sold_out`, `not_accepted`, and `turned_away`. Opening the page without ordering does not. The request total is the sum. The counters are process totals and reset on restart. One local process is the sale. |
| SM-2 Number of database queries and number of database updates during a sale. Validates resource usage for that same load. | Landed on `sale.statements` by `kind`. Every inventory JDBC statement and every outbox-publisher statement counts, including statements that change zero rows. `SELECT` is `query`. `INSERT`, `UPDATE`, and `DELETE` are `update`. The page runs no statements (AD-2), so that is the service's database total. |
| SM-C1 failure clauses (unit not in the pool; customer past the limit). | Landed (AD-8, enforced by AD-3). |
| SM-C1 "not the goal by themselves" / do not optimize. | Finding 1. |

### Spec constraints

| Constraint | Result |
| --- | --- |
| The deduction and the order event are one outcome. | Landed (AD-1). |
| The landing page collects the user ID and the quantity and does not decide the inventory pool. | Landed (AD-2, AD-7). |
| The customer is recognized only by a user ID this service does not create, manage, or authenticate. | Landed (AD-6, AD-7). The customer row is not an account. |
| There is one item. Its inventory pool is the only count of units still available. | Landed (AD-4, AD-9). |
| Units go to the earliest successful orders. | Held by AD-3's conditional update: the matching update takes the units, and the same unit is not published twice. AD-4 does not bind arrival order across the admission gate. That is the existing admission rule. It is not a drop from the tally amendment. |
| Order processing continues from the order event and does not decide the pool. | Landed (AD-2, AD-5). |
| The service handles a high volume of order attempts, including contention for the shared pool. | Landed (AD-4). |
| Request totals and database query and update totals are visible in the service log. | Sentence above. The totals are visible on the counters and in Grafana. |
| A metrics endpoint does not replace that log. | Sentence above. The HTTP server request timer and an HTTP or JDBC auto-metric still cannot stand in for the sale meters. |
| Consistency of the page, the inventory decision, publication, and the local runtime is governed by this spine. | Landed. Telemetry is AD-10 and AD-11. |

### Success signal

| Sentence | Result |
| --- | --- |
| On the local service, a sale shows orders succeeding only while the inventory pool is above zero, no unit deducted for more than one customer, and no customer past the customer limit. | Landed (AD-3, AD-6, AD-10). |
| The shopper sees in process, wait one hour, sold out, refused, or not accepted according to CAP-1 through CAP-8. | Landed (AD-7). Turn-away is a retry inside the page, not a sixth shopper outcome. |
| Each order event carries the units deducted. | Landed (AD-5, `unitsDeducted`). |
| The log shows the request total and the database query and update totals for that sale. | Sentence above. |

CAP-7's success line ("Those totals are visible for the sale") does not name the log. It lands on AD-8 and the Grafana dashboard, with finding 2 as the export gap. The counter-metric half of CAP-7 matches the SM-C1 failure clauses.

## Considered and not counted as gaps

- A separate success total. The sources ask for requests, queries, and updates. AD-8's `result=in_process`, incremented only after the order transaction commits, is the successful-order slice of `sale.attempts`. Forbidding that slice on the log does not drop a source sentence.
- Process totals that reset on restart. SM-1 and SM-2 are totals during a sale. One host process is that sale. The sources do not require the counters to survive a restart. Finding 2 is about visibility before an export, not about keeping memory across a restart.
- `not_accepted` and `turned_away` inside the request total. The sources do not exclude an attempt that reached inventory. A page open is still not a request.
- In-memory counters rather than a counter table. The sources do not require a durable tally table.
- The collector debug exporter and the console `trace_id` / `span_id` pattern. Those are trace correlation. They are not the sale tally.
