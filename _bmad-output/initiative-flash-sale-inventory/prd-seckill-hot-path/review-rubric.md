# PRD Quality Review — Seckill hot-path

## Overall verdict

This is a usable learning-model capability spec. The thesis is specific, the cut from the earlier flash-sale service is named, and the award rules are written so a builder can fail a test: one shared pool, 100 qualifications out of 100,000 attempts, and no double grant of the last unit. Usefulness thins where acceptance is not decidable. FR-7 treats a durable order and a merely visible write failure as the same success, FR-5's pre-filter has no pass/fail line, and one glossary noun covers two reports that the inventory service is also said to adjust stock from. Those three spots are where story and architecture work would invent a rule.

## Decision-readiness — adequate

The sale rules are stated as decisions. Loading an allotment and opening the sale are separate: "Loading the allotment does not by itself open it." The unit is taken at qualification, and "Payment outcome leaves that unit taken." Repeat qualification is a requirement in FR-3 and a closed question in §10: "Repeat qualification while units remain was confirmed, and the earlier per-customer limit stays retired." §7 names what this effort drops from the earlier service. A reader who objects that one customer can take many units, or that an unpaid qualification still consumes the allotment, finds that objection recorded.

The report path is the decision that stays smooth. The glossary defines Report as "Notice to the inventory service that units were qualified, or that units remain when the sale closes." FR-9 requires both a mid-sale notice and a close snapshot. §2.1 says the inventory service "later adjusts stock on hand from this service's reports," while SM-2 says it can "account for" qualified units and units remaining at close. "Adjusts" and "account for" are different jobs, and neither sentence says how a delta and a snapshot compose. FR-9's replay rule is only "A delayed or repeated report does not cause a second qualification," which protects the pool. The addendum records that published sources disagree on when the broader stock record updates. The PRD then commits to both timings and leaves the composition unstated. That is the objection an architect will raise.

FR-7's ending (an order, or a failure the builder can see) is the other unmade choice. The finding for it is under done-ness, because that is where it breaks acceptance.

### Findings

- **medium** Two reports, one noun, no stock rule (§3 Report, §2.1, §4.3 FR-9, SM-2) — Report covers "units were qualified, or that units remain when the sale closes." FR-9 requires a mid-sale "report of the unit taken" and, again, "Close reports the units still in the pool." The inventory job is to adjust stock from those reports; SM-2 only requires that inventory can account for both numbers. A retry (FR-10) can deliver the same notice twice, and the only idempotency line forbids a second qualification. *Fix:* Split the glossary into a qualification notice and a close snapshot. State, in one sentence, whether a replay of the qualification notice must not move warehouse stock a second time, or whether stock application is entirely the inventory service's rule and this service only emits the two facts.

## Substance over theater — strong

The vision cannot be dropped into a generic inventory PRD. It names "on the order of 100,000 customers against a pool of 100," earliest qualifications, a unit that is not given twice, and a crowd that is not held on a disk write. There is no persona chapter and no differentiation section. Mina, Omar, and the inventory service each drive a feature group. The NFR block is four product-specific sentences: the 100,000-against-100 burst, qualifications equal to the allotment, a shared in-memory count, and a collector that "does not change who qualified." FR-11 names an OpenTelemetry collector, Prometheus, and Grafana because that keep-list was confirmed; Redis, the queue, and the circuit breaker stay in the addendum. The counter-metric is specific: "A fast rejection, a quiet durable store, or a low error rate is not success when more units were qualified than the allotment."

### Findings

No findings.

## Strategic coherence — strong

The thesis is the vision: sell one preloaded allotment by deciding on a shared pool, answer the customer before the durable write, and answer attempts that cannot qualify before they contend for that pool. Features 4.1 through 4.4 follow that arc in order: open and close, qualify, record and report, keep the core path up. §8's in-scope list is the same arc. Several hot items, an internal sale clock, returning units when payment does not finish, and a shopper page are out because they are a different product. SM-1 checks the award invariant on the learning burst. SM-C1 blocks treating a quiet store or a low error rate as success when the allotment was oversold, left unawarded while attempts remained, or never reported.

The durable-order half of the thesis sits in FR-7, and the metrics do not lock it. SM-2 checks that "A sold-out or not-yet-open attempt writes no order." It does not check that a qualification writes one. That gap is the done-ness finding on FR-7.

### Findings

No findings.

## Done-ness clarity — thin

The core award rules are testable, and this is the dimension story creation will lean on. FR-3 says "Across 100,000 attempts and an allotment of 100, the number of qualifications is 100." FR-6 says "Two instances cannot both qualify the last unit" and "The decision does not read the durable order store to learn how many units remain." FR-8 says "A read does not decrement the pool and does not write an order." "Within minutes" in the vision and the NFR is the shape of the burst. At learning-model stakes, leaving it unbound is the right call, and SM-1 grades the count.

An engineer still cannot tell done for every FR. FR-7 gives two endings. FR-4 names a customer-visible answer for one of the cases it covers. FR-5's stampede bullets pass on any decrement count other than exactly 100,000. Those are the acceptance holes inside the hot path, which is why this dimension is thin even though the award invariants are strong. FR-1's second load and FR-11's build gate are smaller gaps of the same kind.

### Findings

- **high** Qualification may end with no order (§4.3 FR-7, §9 SM-1) — "Each qualification produces one order" and "The write is retried until the order exists or the failure is visible to the builder" cannot be one acceptance line. A qualification with a decremented unit, no order, and a visible failure satisfies the second sentence and fails the first. "The same qualification" has no identity a retry can cite. FR-11 tells the suite to leave "the qualification unchanged" when the order write fails, which blesses that stuck state. SM-1 can still pass, because it counts qualifications and does not count orders. *Fix:* Choose one terminal bar. Either every qualification has one order and a visible stuck write is a failed build, or a stuck qualification is an accepted outcome and FR-7, FR-11, and the metrics stop requiring the order. Name the identity that makes "the same qualification" one thing across the decrement, the order, and the report.

- **medium** Filter bar cannot fail a test (§4.2 FR-5, §5) — "The edge admits a fraction of the crowd to the qualification path. A burst of 100,000 attempts does not become 100,000 pool decrements." "Once the pool is known to be empty, or the sale is known not to be open, further attempts do not each perform a decrement." Any admission short of 100,000 passes, including a near pass-through, and "do not each" still allows many empty-pool decrements. The last two bullets are decidable: a non-qualifying attempt does not become an order write, and an attempt that gets past the filter still cannot take a unit the pool does not have. *Fix:* State the service rule as: once the pool is known empty, or the sale is known not to be open, an attempt is answered with no pool decrement. If the learning model includes an admission cap, name the cap the test asserts.

- **medium** Only sold out names the answer (§4.2 FR-3, FR-4, §3) — FR-4 says an attempt "against a sale that is not open, or a pool with no unit left, receives that answer." The only named answer is "Sold out is the answer when the sale is open and the pool has no unit left." Before the start signal, and after close, the consequences say the attempt "takes no unit" and "writes no order." FR-3's "A missing customer identity takes no unit and writes no order" also names no answer. FR-11's log line distinguishes a qualification from a sold-out answer and stops there. *Fix:* Name the answer for not-yet-open, for closed, and for a missing identity, including when two of those reuse one word. Extend the FR-11 log line to those answers.

- **low** Second load is only a ceiling (§4.1 FR-1) — "A second load while the sale is open does not raise the pool above the units still unclaimed from that allotment." A lower second load, a second load before the start signal, and which quantity is "the allotment" after two loads are unstated. "The pool at the start signal equals the allotment" cannot be checked when two loads disagree. *Fix:* State that the last load before the start signal sets the allotment, and that a load while the sale is open does not change the pool.

- **low** Build gate skips open and close (§4.4 FR-11) — "Automated tests cover FR-3 through FR-10," including the shared pool, a sale-state read, and a failed order write, report, or telemetry export. FR-1 and FR-2 sit outside that list. A service that awards correctly once open, and never reports remaining units on close, still satisfies the named suite. *Fix:* Include load-before-start and close-reports-remaining in the tests that fail the build.

## Scope honesty — strong

§7 and §8.2 do real exclusion work. This service "is not the stock-on-hand ledger." There is "No shopper page, no per-customer cap, no cooldown, and no partial quantity," and those "belonged to the earlier service." There is no second sale beside the one open allotment, no sale clock inside this service, and no return of units when payment does not finish. That matches §11: "None remain. The draft assumptions were confirmed and written into the glossary and the requirements above." The empty open-question list records a confirmation, and the following sentence names the decision that was confirmed. The learning-model stakes stay in §0 and in the builder's job; they were not turned into a fake product ban.

The trust boundary is stated where a builder would look for it. The customer is "already authenticated and already authorized," and "This service does not create or look up an account."

### Findings

No findings.

## Downstream usability — adequate

Identifiers are contiguous and the cross-references resolve. Feature blurbs cite UJ-1 through UJ-3. FR-11 cites FR-3 through FR-10. SM-1 cites FR-3, FR-4, and FR-6. SM-2 cites FR-4, FR-5, FR-7, and FR-9. The NFR cites FR-6. Allotment, pool, start signal, qualification, sold out, and close are used the same way in the journeys and the FRs. UJ-1 Mina and UJ-2 Omar carry their context inline: already allowed in, sale open, pool state named. An architecture pass can lift §4 and §6 and still see the pool rules.

Extraction snags on the report noun in the decision-readiness finding: one glossary entry, two payloads, and a stock-adjustment job that uses both. Edge and filter are load-bearing in UJ-1 ("passes the filter"), FR-5, and §6, and they are not glossary entries. The addendum is what tells the reader the edge is a CDN and an API gateway, so §6 does not stand fully alone. That split matches this shape; it is why the dimension is adequate. UJ-3's protagonist is the inventory service, the right actor for that system journey.

### Findings

No findings.

## Shape fit — strong

§0 frames a capability spec for one service, written for the builder of a learning model and for the architecture that follows, at a length that matches those stakes. The spine is the one this shape needs: vision, three jobs, three short journeys, glossary, nested FRs, a short NFR block, integration, non-goals, an MVP cut, and operational metrics with a counter-metric. It does not grow a persona chapter, a market section, or a journey per screen. The journeys are the two customer outcomes and the allotment lifecycle. Brownfield is handled by leaving `prd-flash-sale-inventory` as the record of the earlier service and by naming the retired behaviors in §7, so these journeys read as the new sale. Mechanism the user required (the shared store, the queue, the circuit breaker) sits in the addendum. The PRD keeps the behavior: shed non-essential work, and do not skip the pool decrement.

### Findings

No findings.

## Mechanical notes

- ID continuity is clean. UJ-1–UJ-3, FR-1–FR-11, SM-1, SM-2, and SM-C1 have no gaps or duplicates. The UJ, FR, and SM cross-references above all resolve.
- Assumptions index roundtrip holds. There are no inline `[ASSUMPTION]` tags. §11 says none remain, and §10 records the confirmation. No `[NOTE FOR PM]` callouts, which matches that close.
- Glossary drift that did not become its own finding: "Edge" and "filter" are used in UJ-1, FR-5, §2.2, and §6 and have no glossary entries. "Hot item" appears in the vision and in §8.2 and is not defined. FR-5 says "durable store," FR-6 says "durable order store," and FR-7 says "durable write"; all three are the Order record. Report's two meanings are the decision-readiness finding, not a casing drift.
- FR-10's phrase "failing, open, or shed" uses "open" for a downstream call. Everywhere else in the PRD, "open" is the sale. A reader can take that bullet as a statement about the sale being open.
- §0 says "Named technologies and the published seckill notes live in `addendum.md`." FR-11 also names an OpenTelemetry collector, Prometheus, and Grafana. The addendum repeats that keep-list. The behavior matches; the document rule in §0 is broader than the text.
- UJ protagonist naming: Mina and Omar are named, with context inline. UJ-3 uses the inventory service as protagonist. For this capability spec that is the fitting actor.
- The addendum's sale-start, report, and concurrency sections agree with the PRD: load and start are separate, a qualification notice goes out during the sale, close reports units remaining, and the pool is the shared in-memory count. The published-pattern notes stay labeled as landscape, "Not requirements until adopted."
