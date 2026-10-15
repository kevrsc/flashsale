# Review log: jmeter-burst

Target: commit f5ec93d on main (`Add an opt-in JMeter burst so 100,000 attempts can be checked outside the service test run.`) plus the uncommitted cleanup edits to the `jmeterBurst` task in build.gradle (PostgreSQL driver now from a non-transitive `ordersDriver` configuration pinned to 42.7.13; JMeter launched on the Java 21 toolchain instead of Java 17).

## 1 — orientation — open review

Session: 2f6e5a27-5b36-4647-a31b-12dec177390d · Timestamp: 2026-10-15T13:20:00-07:00

- Action: Opened a walkthrough of the JMeter burst from the plan intent in `_bmad-output/initiative-flash-sale-inventory/plan-jmeter-burst.md`.
- Result: No findings yet.
- Evidence: Plan status is built. A live `jmeterBurst` run after the cleanup reported 100 qualified, 100 orders, and attempts finished in 39181 ms.
- Open: Narrative not yet written.

## 2 — orientation — create narrative

Session: 2f6e5a27-5b36-4647-a31b-12dec177390d · Timestamp: 2026-10-15T13:12:00-07:00

- Action: Wrote walkthrough-jmeter-burst.md with six blocks: Intent, Broad strokes, The JMeter plan, The Gradle task runs JMeter, The tally, Periphery. Presented block 1.
- Result: No findings.
- Evidence: Intent pasted verbatim from `plan-jmeter-burst.md`.
- Open: User has not accepted block 1. Cleanup of the old service (src/, live-sale-checks.sh, orders dashboard, old screenshots, inventory.order-accepted topic, README rewrite) is uncommitted.

## 3 — 1. Intent — test: used sale

Session: 2f6e5a27-5b36-4647-a31b-12dec177390d · Timestamp: 2026-10-15T14:20:00-07:00

- Action: Ran `./gradlew jmeterBurst` against the sale left by the previous burst (remaining 0, soldOut 1, open 1, 100 orders).
- Result: Task failed in 13s with `jmeterBurst: load returned 409; failed before any attempt` (build.gradle:216). The results file holds one sample: load 409 on `http://127.0.0.1:8081/sales/load`; no start and no attempt. Afterwards remaining 0, open 1, soldOut 1, orders still 100. Matches the plan's Sale already used row.
- Evidence: `build/jmeter/sale-burst.jtl`, redis `HMGET sale`, `SELECT COUNT(*) FROM orders`.
- Open: Block 1 not yet accepted.

## 4 — 1. Intent — thoughts: reset boundary

Session: 2f6e5a27-5b36-4647-a31b-12dec177390d · Timestamp: 2026-10-15T14:23:00-07:00

- Action: User asked whether resetting by hand is the right boundary.
- Result: Agreed the task should not delete data: the plan's Never list forbids it, the task targets whatever is on localhost, and today a mistaken run only gets a 409. Gaps (open): the 409 guards only hash `sale`; leftover `orders` rows or `pending` fields pass load, spend a fresh sale, and fail only after the two-minute order wait. README lacks the reset commands (`DEL sale pending`; `TRUNCATE orders, qualification_report_sent`). Suggested a read-only preflight beside the port check failing when orders is non-empty or `HLEN pending` > 0, and reset commands in the README Burst section. No change made.
- Evidence: build.gradle:30, build.gradle:212, QualificationRelay republish every 200ms.
- Open: User to choose. Block 1 not yet accepted.

## 5 — 1. Intent — edit: clean check and jmeterReset

Session: 2f6e5a27-5b36-4647-a31b-12dec177390d · Timestamp: 2026-10-15T14:40:00-07:00

- Action: User chose both suggestions from entry 4 plus a separate opt-in `jmeterReset`, documented as risky for production. build.gradle: `withOrders` helper (:22); read-only check in `jmeterBurst` (:60) fails when orders or `HLEN pending` is non-zero; `jmeterReset` (:333) refuses without `-PconfirmReset=local`, deletes Redis `sale` and `pending`, truncates `orders` and `qualification_report_sent`. Added buildSrc LocalRedis.java and LocalRedisTest.java. README Burst section has the destructive warning and hand-reset commands; docs/devops/README.md has a local burst reset section; plan-jmeter-burst.md Implementation Notes updated. Narrative: new block 6 Reset between bursts, block 4 changed, line links refreshed. Fixed a Gradle 10 deprecation (`Task.project` at execution) by reading the flag through `providers.gradleProperty`.
- Result: Entry 4 gaps fixed. buildSrc tests 8 of 8 pass (BurstTally 5, LocalRedis 3). `test`/`check` dry-run excludes the jmeter tasks. Burst on the dirty sale failed in 1s with `100 orders and 0 pending qualifications are left`. `jmeterReset` without the flag refused, orders still 100; with the flag it deleted 1 Redis hash and truncated, orders 0. Next `jmeterBurst` passed in 33110 ms: 100 qualified, 100 orders, no deprecation warnings.
- Evidence: build.gradle:22, :60, :333; buildSrc test report; Gradle output of the runs above.
- Open: Block 1 not yet accepted. All edits uncommitted.

## 6 — 1. Intent — explain: LocalRedis

Session: 2f6e5a27-5b36-4647-a31b-12dec177390d · Timestamp: 2026-10-15T15:31:00-07:00

- Action: User asked what buildSrc LocalRedis.java is for and how it works.
- Result: Explained it serves `HLEN pending` (jmeterBurst clean check) and `DEL sale pending` (jmeterReset). Chosen over a Redis library on the build classpath or shelling out to docker compose redis-cli. Sends RESP arrays with UTF-8 byte lengths, 2s connect/read timeout, reads one reply line, accepts only a `:` integer else IOException. No AUTH, TLS, SELECT, or multi-line replies by design. LocalRedisTest uses a loopback ServerSocket. No findings, no edits.
- Evidence: build.gradle:60, build.gradle:333, LocalRedis.java, LocalRedisTest.java.
- Open: Block 1 not yet accepted. Edits uncommitted.

## 7 — 1. Intent — accepted

Session: 2f6e5a27-5b36-4647-a31b-12dec177390d · Timestamp: 2026-10-15T15:39:00-07:00

- Action: User said continue to the next block. Checked block 1 done in the narrative. Presented block 2 Broad strokes and marked it in progress.
- Result: Block 1 accepted with the in-review edits (clean check, jmeterReset). Did not ask about committing mid-walkthrough, following the user's earlier preference to commit at wrap-up.
- Evidence: walkthrough-jmeter-burst.md Blocks section; commit preference in walkthrough-seckill-hot-path-log.md entry 7.
- Open: Block 2 not yet accepted. Edits uncommitted.

## 8 — 2. Broad strokes — accepted

Session: 2f6e5a27-5b36-4647-a31b-12dec177390d · Timestamp: 2026-10-15T15:47:00-07:00

- Action: User said continue to the next block.
- Result: Block 2 accepted as presented. No findings.
- Open: None for block 2.

## 9 — 3. The JMeter plan — finding: userId counter

Session: 2f6e5a27-5b36-4647-a31b-12dec177390d · Timestamp: 2026-10-15T15:47:30-07:00

- Action: Presented block 3.
- Result: Finding (open): loadtest/sale-burst.jmx:156 uses `u${__counter(TRUE)}`. Per JMeter docs, TRUE is a per-thread counter, so 100 threads likely each send u1..u1000 (1,000 distinct ids, each 100 times) instead of the 100,000 distinct userIds the plan's Always line requires. No functional effect on qualification or tally (no per-customer cap). Proposed fix: TRUE to FALSE (global counter). Not verified live: `docker compose exec` failed because Docker Desktop's engine pipe was unavailable, so `SELECT user_id` counts from orders could not run.
- Evidence: loadtest/sale-burst.jmx:156, plan-jmeter-burst.md Always section.
- Open: User to choose. No edit made.

## 10 — 3. The JMeter plan — edit: global counter; accepted

Session: 2f6e5a27-5b36-4647-a31b-12dec177390d · Timestamp: 2026-10-15T15:52:00-07:00

- Action: User chose the fix from entry 9. Changed loadtest/sale-burst.jmx:156 from `u${__counter(TRUE)}` to `u${__counter(FALSE)}` so attempts send u1..u100000. Updated the narrative block 3 line and checked block 3 done (changed during review).
- Result: userId counter finding fixed. Block 3 accepted. Not verified live: Docker Desktop engine still unavailable (`docker info` failed), so no burst or orders user_id check ran.
- Evidence: loadtest/sale-burst.jmx:156, walkthrough-jmeter-burst.md block 3.
- Open: When Docker is back, run `jmeterReset -PconfirmReset=local`, then `jmeterBurst`, and check `COUNT(DISTINCT user_id)` = 100.

## 11 — 4. The Gradle task runs JMeter — presented

Session: 2f6e5a27-5b36-4647-a31b-12dec177390d · Timestamp: 2026-10-15T15:52:30-07:00

- Action: Presented block 4: port check, clean check, download with SHA-512, idsFile clear, Java 21 launcher, seven-minute limit.
- Result: No findings.
- Evidence: build.gradle:49, :60, :93, :128, :141, :163.
- Open: Block 4 not yet accepted. Edits uncommitted.

## 12 — wrap-up — all blocks accepted

Session: 2f6e5a27-5b36-4647-a31b-12dec177390d · Timestamp: 2026-10-15T15:54:00-07:00

- Action: User said all blocks are completed. Checked blocks 4–7 done in the narrative and set the current block to wrap-up.
- Result: Review finished. Changes made during review: jmeterBurst clean check, jmeterReset with `-PconfirmReset=local`, LocalRedis and its tests, README and devops reset docs, global `__counter(FALSE)`.
- Evidence: walkthrough-jmeter-burst.md Blocks section; entries 5 and 10.
- Open: Counter fix not yet verified live (Docker down). Old-service cleanup, review edits, and walkthrough files are all uncommitted. Asked the user whether to commit locally (no remote exists).
