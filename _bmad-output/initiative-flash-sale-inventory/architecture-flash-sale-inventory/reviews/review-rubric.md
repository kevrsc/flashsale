# Rubric review — architecture spine

Verdict: **Revise**

The spine was not edited. This is a good-spine checklist review of the draft initiative spine against its memlog.

- Spine: `architecture-flash-sale-inventory/architecture-flash-sale-inventory.md` (draft, altitude initiative)
- Memlog (authority for what was decided): `architecture-flash-sale-inventory/.memlog.md`
- Driving PRD: `prd-flash-sale-inventory/prd-flash-sale-inventory.md` (final), plus the user-stated mechanism in `addendum.md` where the PRD points at it

The transactional outbox, the page→inventory direction, the same-transaction deduction, the schema split, and the single local topology are real contracts. Handoff waits on the critical and high items below. They are tightenings of rules the spine already aims at.

## Checklist

| Check | Result |
| --- | --- |
| Fixes the real divergence points one level down (page module vs inventory module, and this service vs the separate order service), and misses none | **Gap.** Module direction, who deducts, who publishes, who counts, and the topic payload are fixed. Overflow behavior, the shopper-outcome set, wait/limit as guards, and order-service delivery/idempotency are open. |
| Every AD Rule is enforceable and actually prevents its stated divergence | **Gap.** AD-2, AD-5's field list, AD-8, AD-9, and AD-10 hold. AD-3's customer-limit prevent, AD-4's "second stock count," AD-6's one-row rule, and AD-7's "no browser JSON API" are stronger than the rules that follow them. |
| Nothing under Deferred could let two units diverge | **Pass.** See the deferred sweep. |
| Named tech is verified-current, using memlog `(version)` entries as the research record | **Gap.** Boot, Modulith, Java, and PostgreSQL are supported. Two stack pins are not. |
| Brownfield: ratify, do not contradict the codebase | **Pass.** Greenfield. No application code exists to contradict. |
| Covers the driving PRD capabilities listed in `binds` | **Gap on side effects.** FR-1–FR-6, SM-1, and SM-2 each have a map row. FR-3 and FR-4 are stored and ordered as messages, and their no-deduction side effects are not bound. |
| Parent spine inherited without a local AD weakening it | **Pass.** No parent spine. Nothing is inherited as an AD. |
| Every dimension this altitude owns is decided, deferred, or an open question | **Gap.** Deployment and infra are decided. Operations is silent. |

## Findings

### 1. Critical — The first customer insert does not enforce one row

AD-6 says one customer row per user ID, and that an admitted transaction which finds no row inserts one before it locks the item row. AD-3 says this prevents a deduction past the customer limit of 3. AD-4's lock order applies to a row that already exists.

Two admitted first attempts for the same user ID can both see no row, both insert, and both lock a different customer row before they touch the item. Each then deducts up to 3. The pool's conditional update still stops an oversell of the item. The customer limit does not hold, and the one-hour wait is stored on two rows.

The cardinality is stated. The insert path has no unique user ID and no "on conflict, lock the existing row and continue in this transaction." Disposition: **autofix**.

### 2. High — The wait and the customer limit are stored, and are not guards on the deduction

FR-3, FR-4, and FR-5 are in `binds`. The capability map assigns them to AD-3, AD-4, and AD-6.

What the rules actually require:

- AD-6 stores units already ordered and the earliest next order time, written on a successful order, from PostgreSQL's clock plus one hour.
- AD-4 evaluates sold out, then the one-hour wait, then a customer-limit refusal. Sold out still wins during the wait.
- AD-3 computes the smaller of the request, the pool, and the units left under 3, and conditional-updates the item when the pool still holds that many.

What a builder can still do while following those sentences:

- Treat AD-4 as the order of messages and still run AD-3's update during the wait. Nothing says a future next-allowed time skips the pool update and writes no outbox row.
- Compute the smaller-of number from a customer read taken before the customer-row lock. AD-4 says lock customer then item when both rows are updated. It does not say the units-left and next-allowed checks are re-read under that lock before the deducted quantity is fixed. Two attempts on one existing row can both pass the limit.

AD-3's prevent ("a deduction past the customer limit of 3") and FR-3's side effects (nothing deducted, no order event) are not what the rules enforce. The response-order sentence in AD-4 does match the PRD's precedence, and that part should stay. Disposition: **autofix** — under the customer-row lock, a wait hit and a zero-units-left hit write no pool change and no outbox row; the smaller-of quantity is taken from that locked read.

### 3. High — The order-service contract stops at the happy-path JSON

Scope is this service and its boundary with the separate order service. AD-5 fixes the topic `inventory.order-accepted`, the order id as the message key, a JSON object with string `orderId`, string `userId`, string `itemId`, and integer `unitsDeducted`, `spring-modulith-events-kafka`, no schema registry, and no second producer. That stops a second encoding.

The memlog decision also binds this service and the order service by leaving out the original requested quantity, payment, and fulfillment, so the order service can continue without those fields. The Rule never closes the object and never puts a duty on the consumer. A producer can add fields. A consumer can require payment or fulfillment before it continues. Both still match the written rule.

The outbox sends after commit and can send the same row more than once. The spine never says delivery is at-least-once, and never says the order service treats `orderId` as the idempotency key. The two services can choose incompatibly: this service retries, the order service inserts a new order per message. The paradigm name does not bind a service that lives outside this repo. Disposition: **autofix**.

### 4. High — Page and inventory can still diverge on admission overflow

AD-2 correctly gives the gate to the inventory module and rendering to the page module. AD-4 then allows two overflow behaviors: wait in the application, or turn the attempt away with no transaction. Jitter applies only after a turn-away. The page waits for its turn, and a still-waiting attempt is not sold out while the pool is above zero.

The conventions name four outcomes for a positive quantity: in process, wait one hour, sold out, and refused. Turn-away is a fifth result with no shared name. The page module can show an error, retry silently, or show sold out. The inventory module can block, or return a code the page has no rule for. Those are the two units this altitude exists to keep aligned.

FR-6's "keeps their place in the request order" is the partial-fill case: the admitted attempt completes for the smaller quantity. AD-3 does that. This finding is the unnamed overflow result, not a claim that the PRD requires a global queue. The concurrency number itself may stay deferred; one module owns it. Disposition: **autofix** the shared outcome (wait in the call, or a named turn-away the page must render). Leave the number under Deferred.

### 5. High — Operations is a silent dimension

Initiative altitude owns the operational envelope. Deployment and environments are decided: AD-10 is one local environment, the Boot process on the host, Compose for PostgreSQL and Kafka, localhost addresses, and a hosted environment deferred until a launch target exists. Infra is decided the same way: `postgres:18.6` and `apache/kafka:4.3.1`, ports published to the host, no second topology.

Operations is neither a decision, a deferred item, nor an open question. Nothing says how many processes may run, what supervises them, how a failed publish is noticed, or what an operator does when the process restarts beyond AD-8's "totals reset." Two host JVMs use localhost, share one Compose file, and satisfy AD-10. They also split the sale totals and multiply AD-4's connection bound. Disposition: **discuss**. The matching decision is a single process. Health and supervision can be deferred in the same pass, with a revisit condition, so the dimension is no longer silent.

### 6. Medium — Two Prevent lines are wider than their Rules

- AD-4 prevents "a second stock count." The rule says a sold-out hint *may* answer later attempts, and that the item row remains the authority for every deduction. A second counter is still allowed. The hint is optional, so the prevent is not a rule.
- AD-7 prevents "a browser JSON API." The rule requires a Thymeleaf form and an in-process call. It does not say that form is the only HTTP entry. A JSON route beside the form still serves the Thymeleaf page.

AD-4's other prevents do hold: first-request jitter is limited to post-turn-away retries, lock order is customer then item, and a waiting attempt is not labeled sold out while the pool is above zero. Disposition: **autofix** the two prevents so each is a prohibition in the Rule, or narrow the Prevent text to what the Rule actually stops.

### 7. Medium — The four shopper outcomes are a sentence, not a return contract

The conventions row lists in process, wait one hour, sold out, and refused. No AD says the inventory module returns that closed set and the page module renders that set and nothing else. FR-1 through FR-5 make the shopper-visible result testable. Page and inventory can use different codes for the same case. Folding this into finding 4 is enough if the overflow outcome is added to the same closed set. Disposition: **autofix**.

### 8. Medium — Two stack pins lack support in the version record

Memlog `(version)` entries, treated here as the research record, support Java 17, Spring Boot 4.1.1, Spring Modulith 2.1.1, PostgreSQL 18.6, and the `postgres:18.6` image. Kafka `4.3.1` and `apache/kafka:4.3.1` are not `(version)` lines. A decision dated 2026-09-29 records them as the current stable download and rejects the 4.4.0 release-candidate tags, so the memlog does support those pins.

These pins are not supported that way:

- `namastack-outbox-starter-jdbc` 1.7.3 is asserted in a decision ("pulled by `spring-modulith-starter-namastack` 2.1.1") with no currency check. The starter's 2.1.1 matches the verified Modulith line. 1.7.3 is a different line.
- `flyway-database-postgresql` appears in the stack table and nowhere in the memlog. The decision says "Flyway's PostgreSQL module" and that `spring-boot-starter-flyway` is on the Boot 4.1.1 BOM. That supports Flyway-on-the-BOM. It does not support this artifact id.

`spring-boot-starter-thymeleaf` 4.1.1 and `spring-boot-starter-flyway` 4.1.1 are named as Boot 4.1.1 BOM starters. `spring-modulith-events-kafka` 2.1.1 matches the verified Modulith version. Those pins stand. Disposition: **discuss** — either add a version-record check for 1.7.3 and the Flyway module id, or stop naming artifacts the memlog does not identify.

## Deferred sweep

None of these let the page module and the inventory module, or this service and the order service, choose incompatibly. Each leaves a choice inside one owner, with the cross-unit constraint already in a rule.

| Deferred item | Why it does not fork two units |
| --- | --- |
| Admission-gate concurrency number | Inventory owns the gate (AD-2). The rule still requires a bound. The missing piece is the shared overflow *outcome* (finding 4), not the number. |
| Order-id generation algorithm | The id stays a unique opaque string. The order service is told not to parse it. The open piece is idempotency on that id (finding 3), which is not what this bullet defers. |
| Starting pool quantity and item-id characters | Both come from the one Flyway seed (AD-9). The form submits that seeded item id (AD-7). |
| Wording of the totals log line | Inventory is the only writer (AD-8). |
| HTTP path of the form | The page module owns the form. Inventory is an in-process call (AD-7). |
| Response copy when the quantity is not a positive integer | AD-3 already forbids a deduction and an outbox row. Copy is the page's. This does not open a second mutation path. |
| A hosted environment | AD-10 allows one environment in this build. |
| Restock, and clearing the sold-out hint | No path increases the pool. The hint stays valid while the pool only falls. The revisit condition matches that. |

## Dimensions

| Dimension | State |
| --- | --- |
| Paradigm and mutation | Decided. Transactional outbox, conditional pool update, customer row in the same transaction. |
| Boundaries | Decided. `page` depends on `inventory`. Inventory publishes. The order service is outside this application. |
| Shared data and schema | Decided. Flyway for item and customer, Namastack JDBC for outbox tables, JDBC for those rows, no Hibernate schema. |
| Identity | Decided. Opaque user id, no account store, no authentication. |
| Client channel | Decided. One Thymeleaf form, one seeded item. Exclusivity of that HTTP entry is finding 6. |
| Deployment and environments | Decided for local. Hosted is deferred with a revisit condition. |
| Infra / provider | Decided. Compose images and localhost. No cloud provider, because the hosted environment is deferred. |
| Operations | **Silent.** Finding 5. |
| Integration contract | Decided for the topic and the four fields. Delivery and idempotency are finding 3. |

## What held

- **Greenfield.** The repo has no application source, build file, or running service. The spine has no brownfield conventions to ratify and none it can contradict.
- **No parent spine.** There is no Inherited Invariants section and no parent AD set. Memlog constraints are PRD inputs, not inherited ADs. No local rule renumbers or weakens a parent AD.
- **AD-1** binds the pool update, the customer-row update, and the outbox insert to one PostgreSQL transaction, Modulith mode `outbox`, send after commit, and no outbox row when the conditional update changes nothing. The page module does not publish.
- **AD-2** is an enforceable dependency rule: inventory owns mutation, the gate, the rows, and publication; the page renders; inventory does not depend on the page.
- **AD-3** prevents two requests from deducting the same remaining pool units, via a conditional update for a positive integer. The customer-limit half of that prevent is finding 2.
- **AD-8** defines a request, includes every statement this application runs (including the outbox publisher), keeps totals in process memory, and resets them on restart. That covers SM-1 and SM-2 as bound.
- **AD-9 and AD-10** are concrete enough that two builders cannot split schema ownership or point one config at Compose DNS and another at localhost.
- **Binds coverage is present as a map.** FR-1 through FR-6, SM-1, and SM-2 each have a home. The holes are side effects and cross-service duties, not missing rows.
- **FR-6 partial fill** is the smaller-of update on the admitted attempt. That is the place-in-line the PRD states for that edge.

## Low

- The sequence diagram shows the inventory participant sending to Kafka and then returning the outcome. AD-1's externalization sends after commit and does not say the shopper response waits on the broker. A builder can block the page on the publish. Disposition: **autofix** the diagram so the outcome follows the commit, and the send is after that commit.
- The seed tree uses `src/main/java/<application>/`. The package name is neither chosen nor deferred, so two modules can root themselves differently. Disposition: **defer** with "pick one base package when the Initializr project is generated."
- `spring-kafka` 4.1.1 is a "brings" claim in a decision, not a `(version)` entry. It is transitive from `spring-modulith-events-kafka` 2.1.1. Disposition: **ignore** unless the stack table is meant to pin only artifacts that were checked on their own.
- The first diagram draws both the inventory module and the order service pointing at the topic. As a dependency picture that is coherent. As a data-flow picture it looks like a second producer, which AD-5 forbids. Disposition: **autofix** the arrow or the label so consumption and publication are distinct.

## Suggested fix order

1. Autofix finding 1 and finding 2 together. They are one customer-row critical section: uniqueness under concurrency, and checks taken under that lock that skip the deduction.
2. Autofix finding 3 (closed payload, at-least-once, `orderId` idempotency for the order service).
3. Autofix finding 4 and finding 7 as one closed outcome set, including overflow.
4. Discuss finding 5, then write either "one process" or an explicit operations deferral.
5. Autofix finding 6. Discuss finding 8 before the stack table is treated as verified.
