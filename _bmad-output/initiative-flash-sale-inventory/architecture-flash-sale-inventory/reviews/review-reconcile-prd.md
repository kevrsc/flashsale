# Reconciliation: PRD against the architecture spine

Verdict: **gaps**

The spine was not edited. The PRD was not edited.

- PRD: `prd-flash-sale-inventory/prd-flash-sale-inventory.md` (final)
- Addendum: `prd-flash-sale-inventory/addendum.md`
- Spine: `architecture-flash-sale-inventory/architecture-flash-sale-inventory.md` (draft)

The addendum's industry landscape is not a requirement. Its user-stated mechanism and robustness notes are in scope because the PRD points at them for the Kafka post, the database write, and contention. The spine's transactional outbox still deducts in PostgreSQL and posts `inventory.order-accepted` after commit. That realizes the mechanism. It is not a drop.

## Findings

### 1. High — Admission turn-away weakens fairness, FR-6's place in line, and the closed set of responses

The vision says a scarce pool stays fair: units go to as many interested customers as the quantity allows, and a unit deducted for one customer is not given to someone else. The addendum's user-stated mechanism deducts for the earliest successful orders while the pool is above zero. UJ-1's edge and FR-6 say the customer keeps their place in the request order when the accepted quantity is at least one. Jobs to be done allow only four landing-page results: in process, wait one hour, sold out, or refused. The feature NFR says the service's own resources withstand a high volume of orders, contention included.

AD-3 does keep the partial fill: the deducted units are the smaller of the request, the pool, and the units left under 3. AD-1 keeps a deduction and an order event together, so the same unit is not published twice. Those parts landed.

AD-4 also allows the other branch: attempts that do not get a transaction are turned away, and jitter applies to the retry after that turn-away. A customer who arrived earlier can lose the remaining units to someone admitted later. That weakens "earliest successful orders" and FR-6's "keeps their place," even though the smaller-of rule is intact for whoever is admitted. Turn-away is also a fifth shopper-facing result. The spine never maps it to one of the four glossary responses. Shedding the attempt weakens the NFR that this service's own resources handle the load. Waiting in the application, which AD-4 also allows, would not have this effect. Because both are permitted, two builders can choose incompatibly.

### 2. High — The one-hour wait and the customer limit are stored, and not bound as guards on the deduction

FR-3: an attempt during the one-hour wait is not a successful order. Nothing is deducted and no order event is sent. The customer is told to wait one hour. FR-4 and the customer-limit glossary: successful units never exceed 3, including across concurrent attempts. FR-5: a customer with no units left is refused (nothing deducted, no event) when the pool is above zero and the customer is not in the wait. SM-C1 says a sale is not a success when a customer's successful units exceed 3.

What landed:

- AD-4 evaluates sold out before the one-hour wait, and the wait before a customer-limit refusal. Sold out still wins when the customer is also in the wait. That is the quiet constraint, and it is explicit.
- AD-6 stores units already ordered and the earliest time that customer may order again, using PostgreSQL's clock plus one hour, written on a successful order.
- AD-3's smaller-of rule and "a quantity that is not a positive integer changes no row" cover a refusal when the computed units are 0, if that computation sees the committed total.

What did not land:

- No rule says a wait hit skips the pool update and writes no outbox row. AD-4 binds message order. It does not bind the FR-3 side effects. A positive quantity from a customer who still has units left still matches AD-3's smaller-of rule, so a builder who treats AD-4 as copy precedence can deduct during the wait.
- The customer-row write is not a conditional update. AD-3's conditional match applies to the item row. AD-6 says a successful order writes the customer row in the same transaction. It does not say the units-left and next-allowed checks are re-read under the customer-row lock before the deduction is fixed. Two admitted attempts for one user ID can each decide from a stale read, then both commit. AD-3 claims to prevent a deduction past 3. The rule does not enforce that claim.
- The first order for a user ID inserts the customer row when the transaction finds none. "One customer row per user ID" states the cardinality and does not bind a unique insert under concurrency. Two first attempts can both insert and both deduct, which breaks the wait and the limit together.

### 3. Low — SM-C1 is not bound

SM-1 and SM-2 are in the frontmatter binds and in AD-8. SM-C1 is not. The oversell half (a deducted unit was not in the pool) is enforced by AD-3's conditional update. The limit half is the hole in finding 2. The remaining sentence — request volume and database activity are not the goal by themselves — is absent. A builder can still treat the log totals as the thing to optimize, which is what the counter-metric forbids.

### 4. Low — The context diagram points the order service at the topic

UJ-1, the order-event glossary, and non-goal 3 say this service sends the event and other services continue from it. Order processing does not decide whether the pool allows another order. AD-5 matches that: this service publishes `inventory.order-accepted` and the payload has no payment or fulfillment fields.

The first flowchart draws both `inventory --> topic` and `orderService --> topic`. Read as flow, the order service is a producer. That contradicts the PRD and AD-5. The prose is the one that matches the PRD.

## Coverage

### Functional requirements

| ID | Result |
| --- | --- |
| FR-1 | Landed. Conditional pool update, outbox in the same transaction, page renders in process, event carries `unitsDeducted`. Payment stays out of the payload. |
| FR-2 | Landed. Empty pool writes no outbox row. Sold out is before the wait, including when the customer is in the wait. The sold-out hint is only after a conditional update finds the pool empty, and restock is out of scope. |
| FR-3 | Weakened. Duration and response order landed. No-deduction and no-event did not (finding 2). |
| FR-4 | Weakened. The limit of 3 and smaller-of landed for a single decision. Concurrent attempts for one user ID are not guarded (finding 2). |
| FR-5 | Landed for a single decision: smaller-of, and a non-positive computed quantity writes no row and no outbox row. The refusal message is in the AD-4 order and the conventions. The same concurrency hole as FR-4 applies. |
| FR-6 | Partial fill landed in AD-3. "Keeps their place" is weakened by turn-away (finding 1). Deducting no more than the pool holds landed. |

### Feature NFR and vision robustness

| Requirement | Result |
| --- | --- |
| A successful order never deducts a unit that was not in the pool | Landed (AD-3 conditional update; AD-1 writes no outbox row when no row changes). |
| The service's own resources handle high volume, contention included | Partially landed. The bounded gate and in-application wait address contention. Turn-away sheds it (finding 1). |
| No order deducts a unit that was not actually available (addendum robustness) | Landed, same as the NFR. |

### Success metrics

| ID | Result |
| --- | --- |
| SM-1 | Landed. Inventory logs running request totals. An order attempt counts, including success, wait, refusal, sold out, and turn-away. A page open without an order does not. That still validates Place an order. |
| SM-2 | Landed. Query count and update count include every statement this application runs, including the outbox publisher. |
| SM-C1 | Dropped as a bound metric (finding 3). Oversell is still prevented. The limit clause is finding 2. |

In-memory totals that reset on restart are compatible with the PRD. Lines already written to the log remain. The PRD does not require the counters to survive a restart.

### Glossary

| Term | Result |
| --- | --- |
| Customer | Landed. Recognized by user ID from one order to the next via the customer row. |
| User ID | Landed. Opaque account id. The page submits it. Inventory does not authenticate it and does not store a user table. |
| Item | Landed. One seeded item row. The PRD does not require a catalog. |
| Inventory pool | Landed. Item row, changed only by the conditional update. |
| Order | Landed. Thymeleaf form, user ID and quantity, in-process call into inventory. |
| Successful order | Landed as deduct plus outbox row in one transaction, then Kafka, then the in-process outcome. Wait and over-limit paths are finding 2. |
| Order event | Landed. `orderId`, `userId`, `itemId`, `unitsDeducted`. No payment or fulfillment fields. |
| Sold out | Landed, including "even when the customer is also in the one-hour wait." |
| One-hour wait | Clock landed (AD-6). Blocking the deduction did not (finding 2). |
| Customer limit | Value 3 landed. Atomic enforcement did not (finding 2). |
| Refused | Landed as the outcome after sold out and the wait, when no units are left. Side effects for a zero computed quantity are in AD-3. |

### Journeys

| ID | Result |
| --- | --- |
| UJ-1 | Main path landed: deduct, event, in process, later attempt told to wait one hour, limit reachable in one order or across later orders. |
| UJ-1 edge | Smaller-of landed. Place in the request order is finding 1. |
| UJ-2 | Landed. Pool not above zero, nothing deducted, no event, sold out. |

### Non-goals

| Non-goal | Result |
| --- | --- |
| No payment, fulfillment, or completing the purchase | Landed (AD-5). |
| No deducting more than the pool, and no successful units past the customer limit | Pool half landed. Limit half is finding 2. |
| Order processing does not decide whether the pool allows another order | Landed in AD-2 and AD-5. The context diagram contradicts it (finding 4). |
| No creating or managing accounts; use the existing user ID | Landed (AD-7). The customer row is limit and wait state, not an account store. |

### Quiet constraints called out for this pass

| Constraint | Result |
| --- | --- |
| Fairness | Split. No double allocation landed. Earliest orders and place in line are finding 1. |
| Sold out beats the one-hour wait | Landed (AD-4). |
| Partial acceptance | Landed (AD-3). |
| Logging of request, query, and update counts | Landed (AD-8). |
| No accounts | Landed (AD-7). |
| No payment | Landed (AD-5). |

### MVP

Section 4 is not deferred. The spine's deferred list is the gate's concurrency number, order-id algorithm, seed quantity and item id, log-line wording, form path, non-positive quantity copy, a hosted environment, and restock. None of those postpone an in-scope behavior. Non-positive quantity has no PRD response, and AD-3 already forbids a deduction and an event for it.

## Considered and not counted as gaps

- Outbox instead of one Kafka client performing the database write. The deduction and the event still commit as one outcome, and Kafka still receives the event after commit.
- A single local environment. The PRD's hobby version does not require a second deployment.
- Four outcome names in the conventions are shorter than the PRD sentences. The meanings match. Exact copy for a non-positive quantity is explicitly deferred and is outside the PRD.
