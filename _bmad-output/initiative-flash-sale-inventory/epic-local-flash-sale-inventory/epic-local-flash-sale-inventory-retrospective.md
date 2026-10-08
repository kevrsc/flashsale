---
epic: epic-local-flash-sale-inventory
date: 2026-10-05
verdict: accepted
criteria: declared
headless: false
---

# Retrospective: Local flash-sale inventory

## Epic summary

Epic `epic-local-flash-sale-inventory`, status done. No going-in concerns were named. All nine stories are `done`. None are still `built`. `pending_tickets` is empty (`tickets.py status`, 2026-10-05).

| Ref | Title | Status |
|-----|-------|--------|
| 1.1 | One order reduces the pool and sends the event | done |
| 1.2 | Extra attempts wait and are not sold out | done |
| 1.3 | The wait and the limit of 3 hold under the lock | done |
| 1.4 | A short pool or a short limit keeps a subset | done |
| 1.5 | An empty pool is sold out, even during the wait | done |
| 1.6 | A blank user ID or quantity is not an order | done |
| 1.7 | The log shows request and database totals | done |
| 1.8 | Refactor sweep | done |
| 1.9 | The sale checks run end to end | done |

No story file was pulled. `tickets.py status` reports `file: null` for every row. The build intent is each plan's frozen block and the entry in `tickets.toml`.

Plan ranges, oldest baseline first. Each range is that plan's `baseline_revision` through the next baseline. Story 1.9's end is `HEAD` (`c2212ba`), inferred. `git_evidence.py` reported `merge_count` 0 on every range. Commit subjects do not name a story ref.

| Ref | Range | Commits |
|-----|-------|---------|
| 1.1 | `b62ff70..9836dca` | `aa0edad`, `a7863a2`, `9836dca` |
| 1.2 | `9836dca..fb6282a` | `fb6282a` |
| 1.3 | `fb6282a..064760e` | `670b481`, `4b5e79a`, `064760e` |
| 1.4 | `064760e..2cae00a` | `9a320df`, `2cae00a` |
| 1.5 | `2cae00a..3951ee6` | `a6739ef`, `3951ee6` |
| 1.6 | `3951ee6..675d945` | `2e7f3f3`, `675d945` |
| 1.7 | `675d945..b308aca` | `b308aca` |
| 1.8 | `b308aca..0bc7bc4` | `0bc7bc4` |
| 1.9 | `0bc7bc4..HEAD` | `b24915f`, `441ce18`, `c2212ba` |

Evidence present: the epic file (Done when at lines 33–39), the spec capabilities, the architecture spine, nine plans with baselines and review logs, and the git ranges above. The initiative file has Outcome and Done when and no Requirements section; the CAP ids the stories cover are in the epic Requirements (lines 23–30) and the spec. One session transcript exists for this build and was not read line by line. Process notes below come from the plan review logs.

Evidence absent: a previous epic, so there is no earlier retrospective. No pulled story files.

Across `src/`, the epic diff `b62ff70..HEAD` is 20 files, 1478 insertions. The largest product file by net added lines is `OrderPoolEventTest.java` at 898. `Orders.java` is 136. Story 1.8 changed no file under `src/`.

## Findings

### Architecture delta

Checked and clean. `inventory/package-info.java` allows no module dependency. `page/package-info.java` allows only `inventory`. Page imports inventory types. Inventory does not import page. No cycle and no second sale path through the page. Derived from those two files and the import graph of the changed Java sources, not from a dependency tool. No language-native graph tool was run.

### Duplication map

Checked and clean for the sale rule. One `Orders.place` applies the smaller-of deduction, the wait, the limit, and sold out. One `Admission.admit` is the shopper entry. Later stories edited those methods. They did not add a second copy. Source: `Orders.java` and `Admission.java` in the epic source diff.

### God-class / size growth

`OrderPoolEventTest.java` grew by 898 lines across stories 1.1 through 1.9 (`git diff --stat b62ff70..HEAD -- src`). Each story appended scenarios and helpers to that one class. Current role: every shopper rule and the closing sale. `Orders.java` grew by 136 lines and stays one transaction method. That size is the sale rule in one place, not a class that outgrew its job.

Disposition: accept as-is. The epic asked for one suite. A split is not required for Done when.

Prevention: nothing. One test class was the intended home.

### Pattern divergence

Checked. Shopper sentences stay on `OrderOutcome`. The page renders `message()` and does not invent a sentence. Story 1.8 added no product pattern of its own (`0bc7bc4` touches only its plan).

### Spec-to-implementation reconciliation

Done when items 1–5 match the code and the suite result `TEST-com.flashsale.OrderPoolEventTest.xml`: 25 tests, 0 failures, 0 errors, 2026-10-05T18:27:56.540Z, including `theSaleChecksRunEndToEnd`. Item 6 is that run through the page against local PostgreSQL and Kafka, as story 1.9 records. CAP-1 through CAP-8 are covered by the entries in `tickets.toml`. Nothing in the epic's scope was dropped. Restock, a hosted environment, payment, accounts, and a second item stay outside the epic Boundaries.

Accepted deviations, so a later retro does not reopen them:

- A user id of spaces is a real id. `Admission.java` rejects only `userId.isEmpty()`. Story 1.6 design notes: comparison does not trim.
- A quantity that is not an integer never reaches inventory. The page binds `@RequestParam int quantity` (`OrderPage.java`). Story 1.6 left that binding as the gate. CAP-8 names a blank id and a non-positive quantity.
- `place` does not repeat the not-accepted check. Story 1.6 put that check in `admit` before `place`. The page calls `admit`.
- The page retries a turn-away with no cap (`OrderPage.java` lines 36–42). Story 1.2 specified that loop. The admission bound stays the setting `2`.
- `next_allowed` is `timestamp` without time zone (`V2__customer_units_and_next_allowed.sql`). The wait uses `clock_timestamp()`. This epic is one local session. A hosted environment, already out of scope, is where that type would be revisited.
- `Orders.clearSoldOutHint` exists for tests. The product path does not call it (`Orders.java` comment above that method). Restock and clearing the hint stay deferred in the architecture spine.

### Adversarial, edge-case, and verification-gap

Reviewed `git diff b62ff70..HEAD -- src`. These three lenses ran. Editorial structure and prose did not: the content is code.

Closed by the tests added for action item 2:

- `theSaleChecksRunEndToEnd` now asserts that the first order's log line raises query and update totals, and that the blank attempt's log line does not. A JDBC counter that stops, or a blank attempt that touches the database, fails that sale.
- `soldOutHintAnswersFromThePageWhileTheBoundIsFull` fills both permits, then sets the sold-out hint, then posts through the page. The page says sold out within two seconds while those permits are still held. Removing the check before `tryAcquire` would leave that post retrying until a permit freed.

Dropped, because the source does not support the claim as a defect:

- The retry re-read uses a plain `SELECT` (`Orders.java` `lockedQuantity`). The item row is already held `FOR UPDATE` in the same transaction, so another order cannot change that quantity underneath the retry. The plain select is not a lost update.
- A conditional update that changes no row and then sees the same quantity returns `unmatched()`, whose message is null (`Orders.java` lines 94–100, `OrderOutcome.java` lines 9–10). The page then shows the form with no sentence. Under the held item lock that miss is not the specified sale path. Story 1 left `unmatched` as the no-sentence result, and later stories kept it for this stop. Accepted as that recorded result, not as a new shopper sentence.
- Several edge findings asked for a blank-page guard, a retry cap, `isBlank()`, or a binding error turned into not-accepted. Those contradict the accepted deviations above.

### Behavior verification

The sale app was started with `gradlew bootRun`. The log contained `Started FlashsaleApplication`. `GET /` returned 200, 345 bytes, and the body contained the user id field and the Order control. A live order was not posted in this retrospective. The order, wait, sold-out, and blank paths were last observed in the suite above, through MockMvc against the local PostgreSQL and Kafka that were up during that run (`flashsale-postgres-1` and `flashsale-kafka-1` were still up when the form was read). The app process was stopped after the form was read.

## Previous-retro follow-through

No previous epic exists in this initiative, so there is no earlier retrospective file and nothing to follow through on.

## Action items

1. On the next epic, run the verification-gap lens on the closing story. This epic's story reviews were quick and skipped that lens (story 1.9 plan, Review Triage Log). The two deferred gaps above sit on story boundaries that a single quick pass did not pin. Owner: whoever starts the next build. Not applied here.
2. Closed. `theSaleChecksRunEndToEnd` now shows query and update totals moving on the first order and staying flat on the blank attempt. `soldOutHintAnswersFromThePageWhileTheBoundIsFull` gets sold out from the page while both permits are held. The suite passed with 26 tests and 0 failures on 2026-10-08.

## Acceptance verdict

**accepted.** Criteria are declared in the epic Done when (lines 33–39). All nine tickets are done. Action item 2 is closed: the suite passed with 26 tests and 0 failures on 2026-10-08. Action item 1 remains a note for the next epic. It is not an open defect in this one.

## Open questions

None. Action item 2 is applied.

## Assumptions

Interactive run. The epic named was this initiative's only epic, `epic-local-flash-sale-inventory`. No going-in concerns were named, so none weighted the findings.
