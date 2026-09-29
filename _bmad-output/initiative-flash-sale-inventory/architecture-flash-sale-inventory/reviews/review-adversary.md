# Adversarial review — architecture spine

Spine reviewed, not edited: `architecture-flash-sale-inventory.md`.

Method: for each candidate hole, build two units one level down that obey every current AD and Deferred entry to the letter, and show they still cannot be dropped into one system. Pairs are the page module and the inventory module, or the inventory module and the separate order service. Schema/JDBC and Compose/application are used only where an AD already splits ownership across those build units. A Deferred line that still lets the pair diverge is a hole. A Deferred line that only one unit can see is not.

**Verdict: fail.** A page and an inventory module can each follow the spine and still disagree on which item is sold, which result the shopper sees, and what a missed conditional update commits. An inventory module and an order service can each follow the spine and still disagree on how many units were sold, what bytes arrive on the topic, and whether a redelivered event is a second purchase.

## Findings

### 1. A missed conditional update can commit customer state and look like sold out

- **Severity:** critical
- **Pair:** inventory module (mutation) and page module (renders that result); order service never sees an event
- **Location:** AD-1, AD-3, AD-4
- **trigger_condition:** AD-1 only forbids an outbox row when the conditional update changes no row. It does not roll the transaction back, and it does not forbid the customer-row write from staying. AD-4 allows a sold-out hint after the update "finds the pool empty," without defining that as a read of a pool that is not above zero.
- **Construction:** Inventory locks the customer row, inserts it, adds the requested units on that row, then runs `UPDATE item SET pool = pool - ? WHERE id = ? AND pool = ?`. A concurrent commit changed the pool, so zero rows change. Inventory writes no outbox row, treats zero rows as an empty pool, commits the customer increment, and returns sold out. The page renders sold out. The order service receives nothing. Every cited rule holds.
- **guard_snippet:** Tighten AD-1 and AD-3. If the conditional update changes no row, roll the transaction back, including any customer insert or customer update. Set the sold-out result and the sold-out hint only after a statement shows the pool is not above zero. When the pool is still above zero, recompute the smaller-of under the locks already held and retry the conditional update. Do not return sold out for that miss.
- **potential_consequence:** Shoppers are told the item is sold out while units remain. The customer row records units that were never deducted, so later orders are refused. Those units never become an order event.

### 2. The smaller-of is computed before the customer row is locked

- **Severity:** critical
- **Pair:** two mutation paths inside the inventory module; the page and the order service both trust the result
- **Location:** AD-3, AD-4, AD-6
- **trigger_condition:** AD-4 requires the customer row to be locked before the item row when both are updated. It does not require the read of units already ordered, or of the next-allowed time, to happen after that lock. The customer write is not itself conditional.
- **Construction:** Both orders for one user read `units_ordered = 0` before either takes the customer lock, so each computes a smaller-of of 3. Each then locks the customer row, then the item row, and runs a legal conditional pool update plus `SET units_ordered = units_ordered + 3`. The locks serialize the writes. The customer ends at 6. Both events carry `unitsDeducted` 3. The page shows in process twice, an hour apart only if the wait was also read early and missed.
- **guard_snippet:** Tighten AD-3 and AD-6. After the customer row is locked, and before the item update, read that row's successful units and next-allowed time. Compute the smaller-of from those values and from the pool read under the item lock. On success, update the customer row in the same transaction with a predicate that still matches the locked units (or an increment applied only in that locked transaction after the item update matches).
- **potential_consequence:** One customer takes more than 3 units. The pool and the order events agree with that over-deduction, so the order service fulfills it.

### 3. Page and seed do not share one item id

- **Severity:** critical
- **Pair:** page module and inventory module (Flyway seed)
- **Location:** AD-7, AD-9, Deferred "Starting pool quantity and the item-id characters"
- **trigger_condition:** The form must submit the item id of the single seeded row, and both sides must treat item id as an opaque string. The characters themselves are deferred until the migration is written. Nothing says the conditional update's predicate is that submitted id, or that the page must ask inventory for the id instead of embedding one.
- **Construction:** The page hardcodes `ITEM-1` and submits it. The migration inserts `item-1` with a full pool. Inventory's conditional update matches `item_id = ?` on the submitted value, changes no row, writes no outbox row, and returns sold out. The page renders sold out. Starting-pool deferral is untouched. Both units obeyed the spine.
- **guard_snippet:** Take item-id characters out of Deferred. Either fix one literal in a new AD that the seed inserts and the form submits, and require the conditional update to match that id, or forbid the page from embedding an id and require it to pass through the single id the inventory module reads from the seeded row. Leave only the starting pool quantity deferred.
- **potential_consequence:** Every order misses the row. The shopper sees sold out and no order event is sent while the seeded pool is untouched.

### 4. The order event is not a closed contract

- **Severity:** critical
- **Pair:** inventory module and the separate order service
- **Location:** AD-5, Deferred "Order-id generation algorithm", flowchart `orderService --> topic`
- **trigger_condition:** AD-5 names four JSON fields and the topic and key. It does not bind `unitsDeducted` to the conditional-update delta, forbid extra properties or a Modulith/Namastack envelope, state at-least-once delivery, or require the order service to treat `orderId` as an idempotency key. The deferred algorithm only keeps the id opaque and unique, and revisits only if a consumer must parse it. The flowchart draws the order service at the topic with the same arrow as the publisher.
- **Construction:** Inventory subtracts the smaller-of from the pool and puts the shopper's requested quantity in `unitsDeducted`, adding `requestedUnits` because the rule says the object has those four fields, not that it has only those four. It uses an in-process counter as the opaque id, stored nowhere, and Modulith republishes after a broker timeout. The order service reads the four fields, fulfills `unitsDeducted`, and inserts a new purchase per delivery. It does not parse the id. A second build of the order service expects the four fields at the root and dead-letters a Namastack envelope that still contains them. A restart reissues id `1`. One consumer drops the new order as a duplicate; another attaches it to the first customer's purchase.
- **guard_snippet:** Tighten AD-5. `unitsDeducted` is the number of units the successful conditional update subtracted, not the requested quantity. The value is UTF-8 JSON with exactly `orderId`, `userId`, `itemId`, and `unitsDeducted` and no envelope. The Kafka key is that same `orderId`. Publication is at-least-once. The order service uses `orderId` as an idempotency key and does not open a second purchase for it. Replace the deferred algorithm with a binding rule: the id is generated inside the AD-1 transaction, stored with that commit, and never reused after a restart. The flowchart arrow into the topic is consume-only; inventory remains the only producer.
- **potential_consequence:** The order service ships more units than were deducted, creates a second purchase on redelivery, or drops a new sale because a restarted counter reused an id.

### 5. The page's outcome set is smaller than what inventory is allowed to return

- **Severity:** high
- **Pair:** page module and inventory module
- **Location:** AD-4, AD-7, AD-8, Consistency Conventions, Deferred "Response copy when the quantity is not a positive integer" and "Admission-gate concurrency number"
- **trigger_condition:** Conventions list four outcomes for a positive quantity. AD-8 also counts an admission-gate turn-away as a request that reaches inventory. AD-4 allows the gate to turn an attempt away instead of waiting, while also saying the page waits for its turn. AD-3 defines a non-positive quantity as no row and no event, and Deferred leaves the response as copy. The gate bound itself is deferred.
- **Construction:** Inventory, at a bound of one, returns turn-away without a transaction and without using any of the four names. The page waits only as long as the in-process call, then has no legal outcome, so it renders sold out. For quantity `0` or blank, inventory changes no row and returns no value; the page renders refused. Another page build renders an error page. Both pairs follow the written rules. The numeric bound can stay unset and this clash remains.
- **guard_snippet:** Close the outcome set in AD-4 and AD-7. Either the gate waits inside inventory and the page blocks until it receives only in process, wait one hour, sold out, or refused, or turn-away is a fifth named outcome the page must render, with jitter owned by the side that retries. Make a non-positive quantity a named result with no deduction and no event, and defer only the words inside that result. Leave the concurrency number deferred only after wait-versus-turn-away can no longer change the value the page receives.
- **potential_consequence:** A gate rejection or a blank quantity is shown as sold out, as refused, or as an unhandled error. Two compliant UIs do not mean the same thing, and SM-1's turn-away count has no shopper-facing counterpart.

### 6. "One customer row per user id" does not survive two inserts

- **Severity:** high
- **Pair:** Flyway schema and inventory JDBC, the split AD-9 already makes
- **Location:** AD-6, AD-9
- **trigger_condition:** AD-6 requires one row per user id and an insert when an admitted transaction finds none, before the item lock. AD-9 says Flyway creates the customer table and does not require a unique key. The insert is not specified as a conflict-safe write.
- **Construction:** The migration creates `customer(user_id, units_ordered, next_allowed)` with no unique constraint. Two admitted transactions for the same new user both see no row, both insert, then both lock their own new row and both deduct up to 3 from the item. Neither step violates the written rules.
- **guard_snippet:** Tighten AD-6 and AD-9. Flyway puts a primary or unique key on user id. The first write is a single conflict-safe insert, and the transaction that loses the race continues on the existing row under the customer-then-item lock order. It does not insert a second row.
- **potential_consequence:** The customer limit is enforced per row. One user id deducts twice the limit, with two legal order events.

### 7. Localhost is not one topology

- **Severity:** high
- **Pair:** host application and Compose; then the publisher and the order service on the topic
- **Location:** AD-10, Structural Seed
- **trigger_condition:** AD-10 fixes the image tags and "localhost" versus Compose DNS. It does not fix the host port, database name, user, password, Kafka advertised listener, or who creates `inventory.order-accepted`.
- **Construction:** Compose publishes PostgreSQL as `localhost:5433` with database `postgres`, and Kafka with `KAFKA_ADVERTISED_LISTENERS` set to the container hostname on 9092. The application uses `jdbc:postgresql://localhost:5432/flashsale` and `localhost:9092`. Both configurations use localhost addresses and the pinned images. Neither creates the topic. The order service subscribes to `inventory.order-accepted` and waits.
- **guard_snippet:** Tighten AD-10 with one host port, database name, user, and password shared by Compose and the application URL, and one Kafka listener advertised as `localhost` on the port the application uses. Name a single owner that creates `inventory.order-accepted` before the publisher sends.
- **potential_consequence:** Two builds that both match AD-10 never connect. Committed outbox rows sit unpublished, and the order service never starts the purchase.

### 8. The outbox row has two legal writers

- **Severity:** high
- **Pair:** inventory JDBC and Modulith/Namastack externalization; the order service consumes whichever rows are published
- **Location:** AD-1, AD-5, AD-9
- **trigger_condition:** AD-1 says the successful order inserts the outbox row in the deduction transaction. AD-5 says publication uses `spring-modulith-events-kafka`. AD-9 says Namastack creates the outbox tables. Nothing says the Modulith publish is the only insert, or that application JDBC must not write those tables.
- **Construction:** Inventory JDBC-inserts the AD-5 JSON into the Namastack outbox table inside the deduction transaction, and also publishes a Modulith event in that transaction so externalization mode `outbox` is honored. Namastack inserts a second row. Flyway still does not create the tables. The order service sees two records for one deduction.
- **guard_snippet:** Tighten AD-1. The only outbox write is the Modulith event published inside the AD-1 transaction. Application code does not insert into outbox tables. Namastack remains the only DDL owner for those tables.
- **potential_consequence:** One deduction becomes two Kafka records. Combined with finding 4, the order service can fulfill the order twice.

### 9. The page can own the transaction AD-1 thinks inventory committed

- **Severity:** medium
- **Pair:** page module and inventory module
- **Location:** AD-1, AD-7
- **trigger_condition:** AD-1 requires one PostgreSQL transaction around the pool update, the customer-row update, and the outbox row. It does not say the inventory module opens that transaction. AD-7 has the controller call inventory in-process and does not forbid the controller from being transactional or from mapping a thrown exception onto a different outcome.
- **Construction:** The page starts a transaction and calls inventory. Inventory joins it, performs the AD-1 writes, and returns in process. The page template then fails and the outer transaction rolls back. A second build marks inventory `REQUIRES_NEW`, commits, returns in process, and the page renders an error page because rendering threw after the return. In both builds the page "calls inventory in-process" and inventory "inserts the outbox row in the same transaction as the pool update."
- **guard_snippet:** Tighten AD-1 and AD-7. The page does not start a transaction around the call. The inventory entry point opens and commits the AD-1 transaction. The page renders the returned outcome on the response and does not replace a committed success with refused, sold out, or an error outcome.
- **potential_consequence:** The shopper sees in process for a deduction that rolled back, or sees an error for a deduction that committed and was published.

### 10. Lock order does not cover a transaction that only reads both rows

- **Severity:** medium
- **Pair:** the deduction path and the wait/sold-out path in the inventory module
- **Location:** AD-4
- **trigger_condition:** The customer-then-item lock order applies only "when a transaction updates both rows." A transaction that reads both rows is unconstrained. AD-4 also claims to prevent a deadlock from opposite lock order.
- **Construction:** The success path locks the customer row, then the item row, then updates. The wait check locks the item row to read the pool, then locks the customer row to read the next-allowed time, and updates neither row if it is about to return wait. Each path obeys the rule. Under concurrency they deadlock.
- **guard_snippet:** Tighten AD-4. Any transaction that locks both rows, including a read, locks the customer row and then the item row. A sold-out check that only needs the item row does not then lock the customer row.
- **potential_consequence:** The gate fills with stuck transactions. Admitted attempts sit until the database cancels one of them, and the shopper does not get one of the defined outcomes.

### 11. The sequence diagram sends Kafka before the page receives the outcome

- **Severity:** medium
- **Pair:** page module and inventory module
- **Location:** Structural Seed sequence diagram, contradicted by AD-1
- **trigger_condition:** The diagram is `Inventory->>Kafka` and then `Inventory-->>Page`. AD-1 says the send happens after the commit and does not say the page waits for the broker.
- **Construction:** The page treats the inventory call as unfinished until the broker accepts the record, and turns a broker error into a non-success page. Inventory commits, returns in process, and lets Modulith send later. Each reading is available in the spine. The shopper either blocks on Kafka or is shown a failure after the deduction exists.
- **guard_snippet:** Redraw the sequence as commit, return the outcome to the page, then Modulith sends. State in AD-7 that a broker delay is not a different shopper outcome.
- **potential_consequence:** A Kafka outage is shown as a failed order after the pool and the outbox row have committed, or the request holds a database connection until the send finishes.

### 12. Sale counts assume one writer and do not define a statement

- **Severity:** medium
- **Pair:** page module and inventory module
- **Location:** AD-8, AD-2
- **trigger_condition:** AD-8 says inventory alone keeps the totals and that the totals include every database statement this application runs, including zero-effect work by the outbox publisher. AD-2 does not forbid the page from reading PostgreSQL. Query versus update is not defined for a conditional update that changes no row.
- **Construction:** The page reads the seeded item id so it can submit it, and issues a `SELECT`. Inventory never sees that statement, and its log omits it. Another build has the page embed the id and issue no SQL. Both obey "the page does not publish" and "inventory alone keeps running totals." One inventory build counts a zero-row `UPDATE` as an update; another counts only rows actually changed. SM-2 differs.
- **guard_snippet:** Tighten AD-8. The page module runs no database statements. Every statement from inventory JDBC and from the outbox publisher counts, including statements that change zero rows. `SELECT` is a query. `INSERT`, `UPDATE`, and `DELETE` are updates.
- **potential_consequence:** Two builds that both satisfy SM-1 and SM-2 report different database totals for the same sale, and a page read of the item id is invisible to the only counter.

### 13. User id and item id have no shared comparison rule

- **Severity:** low
- **Pair:** page module and inventory module
- **Location:** AD-6, AD-7, Consistency Conventions
- **trigger_condition:** Both ids are opaque strings. Nothing says whether the page and inventory trim, case-fold, or reject empty before a customer row is inserted.
- **Construction:** The page trims and rejects a blank user id. Inventory does not trim, inserts a row for `" "`, and returns in process. The other way around, the page submits `"alex"` and inventory folds to `"Alex"`, so two strings that the page treats as different customers share one limit. Neither side authenticates the id, which is all AD-7 requires.
- **guard_snippet:** Add a convention both sides share: compare user id and item id as exact strings, with no trim and no case fold. Reject an empty or blank user id before a transaction, with the same named outcome as a non-positive quantity, and do not insert a customer row for it.
- **potential_consequence:** Blank submissions collapse onto one customer row, or the page and inventory disagree about whether an order was accepted.

## Deferred audit

Holes (still let the two units diverge):

- Item-id characters, together with the starting-pool bullet. The pool quantity can stay deferred. The id cannot. See finding 3.
- Order-id generation algorithm. Opacity is not enough. Uniqueness has to survive restart and redelivery, and the order service must key idempotency on it. See finding 4. The revisit condition "only if an order service must parse it" misses that divergence.
- Admission-gate concurrency number. The number can stay deferred. Wait versus turn-away cannot, because it changes the value the page receives. See finding 5.
- Response copy when the quantity is not a positive integer. The words can stay deferred. The outcome identity cannot. See finding 5.

Not holes (only one unit can choose, and the revisit condition matches):

- Starting pool quantity, once the item id is no longer deferred with it. Only the seed and the item row use the number.
- Wording of the totals log line. Inventory is the only writer until a scraper exists.
- HTTP path of the form. Only the page module sees it. Inventory is called in-process.
- A hosted environment. AD-10 already allows one local topology for this build.
- Restock, and clearing the sold-out hint. No second unit can raise the pool. The hint becomes a hole only if that changes, which is the stated revisit condition.
