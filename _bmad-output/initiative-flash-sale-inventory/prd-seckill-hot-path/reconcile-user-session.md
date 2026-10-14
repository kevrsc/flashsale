# User-stated seckill hot-path requirements

Input: the nine requirements stated in this session. Compared with `prd-seckill-hot-path.md` and `addendum.md`. Neither source was edited.

## 1. Separate service, one allotted hot item, off the normal order path

**Where it landed:** Captured in the PRD. The named large-platform pattern is captured only in the addendum.

The PRD vision, glossary, non-goals, and integration make this service separate from the stock-on-hand ledger. The inventory service sets aside a limited quantity of one hot item. This service sells only that allotment. Orders leave through a queue. Payment, fulfillment, and catalog text stay with other services.

The addendum Boundary records the user's expectation of the large-platform pattern, including the published Alibaba approach: the hot item is pulled off the normal order path. The same file's published-pattern section says those landscape notes are not requirements until adopted.

**What the FR structure drops:** "Follow the large-platform pattern" is a design stance. The features keep the boundary (one allotment, warehouse stock stays with inventory, the order is written after the click). They do not name the pattern, and they do not test that the hot item left the normal order path.

## 2. Drop tech that is not the seckill shape; add what is missing; Modulith is not required

**Where it landed:** Captured in the PRD for Modulith and for keeping a current library only when it serves this PRD. The permission to add a missing service is captured only in the addendum.

PRD non-goals: Modulith is not a requirement. A current library stays only when the architecture chooses it for the behavior in this PRD.

Addendum Boundary: technology already in this service may be removed when the pattern does not need it, and a service the pattern needs may be added. Kept and dropped repeats that Modulith is not required, and lists the shopper page, the limit of 3, the one-hour wait, partial acceptance, and the Modulith split as not carried forward.

**What the FR structure drops:** "Not the ideal seckill implementation" is a judgment. No functional requirement fails because a leftover library is a poor fit. Adding a service is addendum prose, not a feature.

## 3. No account management; the customer is already authenticated and authorized

**Where it landed:** Captured in the PRD.

The glossary says this service does not create or look up an account. The job and UJ-1 assume the customer is already authenticated and already authorized. Non-goals exclude account creation and login. FR-3: a missing customer identity takes no unit and writes no order. The addendum Customers section restates the same rule.

## 4. Confirmed sale: separate load and start, one sale, one unit, repeat qualification, retired limits

**Where it landed:** Captured in the PRD.

The glossary Start signal separates loading the allotment from opening the sale. FR-1: one open sale, one item, one allotment, and an attempt before the start signal takes no unit. An attempt is a request for one unit (glossary, FR-3). FR-3: the same customer may qualify again while units remain; there is no per-customer cap and no cooldown. Non-goals and out of scope: no shopper page, no partial quantity. Open questions record that repeat qualification was confirmed and the earlier per-customer limit stays retired.

The names "limit of 3" and "one-hour wait" appear only in the addendum, as items not carried forward. The PRD states the rules those numbers were.

The addendum section "How a sale starts" still calls allocation-as-start a working guess left open to correction. That sentence disagrees with this confirmed model. See Gaps.

## 5. About 100,000 customers, a pool of 100, shared in-memory count, async order

**Where it landed:** Captured in the PRD as behavior. Redis, Kafka, and RabbitMQ are captured only in the addendum.

Vision, FR-3, FR-6, the cross-cutting NFR, and SM-1: on the order of 100,000 attempts against an allotment of 100 within minutes; one instance or many; one shared pool; the qualification count is in memory; the decision does not read the durable order store; qualifications equal the allotment. FR-7 and Integration: the customer is answered from the pool decrement, and the order leaves through a queue without holding the response on the durable write.

PRD section 5 places the named store and the named queue in the addendum. The addendum names Redis for the shared count, and names both Kafka and RabbitMQ, with the requirement being a queue between qualification and the durable order. A click does not read a disk database to learn the count.

The addendum Concurrency section still says sharing is done in the persistence layer. A later paragraph refines that sentence: the shared pool is the in-memory count, and the disk database records orders after qualification.

**What the FR structure drops:** "Within minutes" and "not a disk read on every click" become a count invariant (100 qualifications, and the decision does not read the order store). SM-1 checks that count. It does not check duration. The product names sit outside the feature list on purpose.

## 6. Tell inventory during the sale and at close; do not deduct warehouse stock on the click; do not return the unit; inventory closes; no sale clock

**Where it landed:** Captured in the PRD. The addendum still has the earlier, unchosen report timing.

FR-9: during the sale, a qualification is followed by a report of the unit taken, and the attempt response does not wait on that report. Close reports the units still in the pool. A delayed or repeated report does not qualify the unit again. Vision, glossary, and the inventory job: warehouse stock on hand stays with the inventory service, which adjusts stock from these reports. Glossary Qualification: the unit is taken at qualification, and payment outcome leaves that unit taken. Glossary Close, FR-2, and out of scope: the inventory service closes the sale, this service has no sale clock, and returning units when payment does not finish is out of scope.

The addendum says the inventory service must adjust stock from what this service reports, and that the user has not chosen a report on each sale, on a period, or at the end. It does not record the confirmed pair (during the sale, off the attempt response, and again at close with units remaining), the ban on a warehouse deduction inside the winning click, or the ban on giving the unit back.

## 7. Edge filtering, separate sale-state reads, shed non-essential work, Resilience4j on downstream calls

**Where it landed:** Read/write separation, shedding, and an edge that filters and rate-limits before the pool are captured in the PRD. CDN, the API gateway, and the Resilience4j contract are captured only in the addendum.

FR-8: a read of whether the sale is open, sold out, or how many units remain does not decrement the pool, does not write an order, and does not block qualifications behind the same lock. FR-10: failure of the order write, the report, or telemetry export does not grant an extra unit, and work that is not the pool decision yields. Integration: attempts arrive through an edge that filters and rate-limits before the pool, and this service still enforces FR-5 when an attempt gets through. FR-5: once the pool is known empty, or the sale is known not to be open, further attempts do not each decrement.

PRD section 5 points the edge and circuit-breaker choices at the addendum. The addendum names a CDN and an API gateway, and names Spring Cloud Circuit Breaker backed by Resilience4j (`spring-cloud-starter-circuitbreaker-resilience4j`). Hystrix is excluded. The breaker wraps the order write, the inventory report, and telemetry export. The pool decrement stays on the qualification path. That section also says the filter lets only a fraction reach the durable store.

**What the FR structure drops:** "Only a fraction reach the core" and "graceful degradation" become FR-5 and FR-10: skip the decrement once the sale is already known to be closed or empty, and still return a qualification when export is down. There is no fraction. The breaker is not a feature.

## 8. Keep the OpenTelemetry collector, Prometheus, Grafana, meaningful logging, and tests

**Where it landed:** Captured in the PRD.

FR-11: metrics, traces, and logs are exported through an OpenTelemetry collector; Prometheus scrapes the metrics; Grafana shows them. A log lets the builder tell a qualification from a sold-out answer and tie a qualification to its order. Automated tests cover FR-3 through FR-7 and FR-9, including more than one instance sharing one pool, and the build fails when those tests fail. The NFR: a slow or unavailable collector does not change who qualified. The addendum Kept and dropped and Operations sections restate the same keep list.

**What the FR structure drops:** "Meaningful logging" becomes those two log facts. "Test coverage" becomes that FR list. The build-fail set is stated under Gaps.

## 9. A learning model of the production shape, not a system to launch or operate internally

**Where it landed:** The learning model is captured in the PRD. "Not a system to launch or operate internally" is missing from both documents.

The PRD purpose is for the builder of a learning model of a production seckill sale, and for the architecture that follows. The builder's job is to run the model and check that a burst awards only the allotted units. SM-1 calls the 100-from-100,000 case a learning burst. The addendum does not mention this constraint.

**What the FR structure drops:** The learning-model intent stays in the purpose and in one job. No feature and no non-goal says the result must not be launched or operated as an internal system. Success metrics grade a correct award.

## Qualitative ideas the FR structure drops

- Whether existing technology is the ideal seckill shape, and whether a missing service may be added, stay as architecture prose. A feature cannot fail the build for keeping a leftover library.
- The large-platform pattern, and pulling the hot item off the normal order path, become service boundaries. The published comparisons are labeled not requirements until adopted.
- "A fraction of the crowd" and "within minutes" become count rules: qualifications equal the allotment, a rejected attempt does not write an order, and a pool already known to be empty is not decremented again. No fraction and no duration are success criteria.
- "Meaningful" logs become two discriminators: qualification versus sold out, and a qualification tied to its order. Redis, Kafka or RabbitMQ, and the Resilience4j breaker are named outside the feature list, which PRD section 5 does on purpose.
- "Do not launch or operate this" never becomes a non-goal. The features describe a sale the builder can run and observe.

## Gaps

1. **The addendum still states the unconfirmed model.** "How a sale starts" still treats "the sale starts when inventory allocates" as an open guess. "Report back to inventory" still says the user has not chosen a report on each sale, on a period, or at the end. Concurrency still says the instances share the pool in the persistence layer, ahead of the later refinement that the pool is the in-memory count. The published-pattern notes describe mid-sale refill and leftover return, and the addendum never records the confirmed rule that this service keeps the unit when payment does not finish. The PRD already has the confirmed decisions: load and start are separate, reports go out during the sale off the attempt response and again at close with units remaining, the count is in memory, and the unit is not returned.

2. **No rule requires that only a fraction of attempts reach the core.** The user required a CDN and an API gateway so that only a fraction of attempts reach the core. The PRD requires an edge that filters and rate-limits before the pool, and FR-5 stops further decrements only after the sale is already known to be closed or empty. The addendum names the CDN and the API gateway, and aims that fraction at the durable store. A build can accept all 100,000 attempts into this service, write orders only for the 100 qualifications, and satisfy both documents. The queue alone keeps losers off the durable store.

3. **The Resilience4j contract has no testable consequence.** The user required Spring Cloud Circuit Breaker backed by Resilience4j (`spring-cloud-starter-circuitbreaker-resilience4j`), excluded Hystrix, wrapped the breaker around downstream calls, and left the pool decrement on the qualification path. That text is only in the addendum, under a header that says these notes are not the PRD's feature list. FR-10 is satisfied by retrying a failed report and by returning the qualification when telemetry export is down. Those consequences pass with no breaker, and they pass if a breaker is wrapped around the decrement.

4. **The tests that fail the build skip the read split and the shed path.** FR-11 fails the build on FR-3 through FR-7 and FR-9, which proves the award count, the shared pool, the single async order, and the inventory report. It does not require a test that a sale-state read leaves the pool unchanged (FR-8), or that a down order write, report, or telemetry export leaves the qualification and the award unchanged (FR-10). Those are the mechanisms in requirement 7.

5. **Nothing forbids launching or operating the model internally.** Requirement 9 says this is a learning model of the production shape, not a system to launch or operate internally. The PRD purpose and the builder job capture the learning model, and they also address "the architecture that follows." No non-goal, assumption, or addendum line states the ban on launch or on internal operation.
