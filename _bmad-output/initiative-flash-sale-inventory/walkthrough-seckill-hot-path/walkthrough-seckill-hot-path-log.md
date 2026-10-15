# Review log: seckill-hot-path

Target: commit 18ca4d9 on branch seckill-hot-path (`Add the seckill sale and gateway so qualification uses one shared Redis pool.`)

## 1 — orientation — create narrative

Session: 2f6e5a27-5b36-4647-a31b-12dec177390d · Timestamp: 2026-10-14T18:40:00-07:00

- Action: Opened a walkthrough of commit 18ca4d9 from the approved plan intent. Wrote the narrative with seven blocks. Block 1 is in progress; the rest are unvisited.
- Result: No findings. Narrative is walkthrough-seckill-hot-path.md.
- Evidence: `_bmad-output/initiative-flash-sale-inventory/plan-seckill-hot-path.md` Intent section, pasted verbatim. Baseline of the commit is 18d02aa.
- Open: User has not accepted block 1.

## 2 — 1. Intent — accepted

Session: 2f6e5a27-5b36-4647-a31b-12dec177390d · Timestamp: 2026-10-15T10:16:00-07:00

- Action: User said to continue the walkthrough. Treated block 1 as done and moved to block 2.
- Result: Block 1 accepted as-is. No findings.
- Evidence: Intent text unchanged from plan-seckill-hot-path.md.
- Open: Block 2 is in progress. Walkthrough files are uncommitted.

## 3 — 2. Broad strokes — thoughts

Session: 2f6e5a27-5b36-4647-a31b-12dec177390d · Timestamp: 2026-10-15T10:29:00-07:00

- Action: Thoughts on the five-stop path: gateway hash read, Lua decrement, HTTP result, relay publish, order insert then Redis ack.
- Result: Path accepted as the right shape. Open observations: `republish` can send before the HTTP return; `AttemptApi` reads `pending` before it returns and throws if that field is gone; `beforeInsert` is a production hook. None fixed.
- Evidence: AttemptEdge.java:37, AttemptApi.java:41, QualificationRelay.java:42 and :49, OrderInsert.java:45, OrderListener.java:16.
- Open: Block 2 still in progress. User has not said the block is done.

## 4 — 2. Broad strokes — edits

Session: 2f6e5a27-5b36-4647-a31b-12dec177390d · Timestamp: 2026-10-15T10:38:00-07:00

- Action: Commented `OrderInsert.beforeInsert`. `AttemptApi` still returns the script result when `pending` throws. Left `republish` unchanged so a crash before `afterCompletion` can still send the document.
- Result: Comment added. Missing-document response fixed, covered by `AttemptApiTest`. Relay poll accepted as-is.
- Evidence: OrderInsert.java beforeInsert, AttemptApi.java attach, AttemptApiTest.
- Open: Block 2 still in progress. Sale tests not yet re-run for this edit.

## 5 — 2. Broad strokes — test

Session: 2f6e5a27-5b36-4647-a31b-12dec177390d · Timestamp: 2026-10-15T10:42:00-07:00

- Action: Ran `:sale:test jacocoTestCoverageVerification` after the AttemptApi and OrderInsert edits.
- Result: Passed. `AttemptApiTest.missingPendingDocumentStillReturnsTheScriptResult` included.
- Evidence: Gradle BUILD SUCCESSFUL for `:sale:test`.
- Open: Block 2 still in progress. Edits are uncommitted.

## 6 — 2. Broad strokes — accepted

Session: 2f6e5a27-5b36-4647-a31b-12dec177390d · Timestamp: 2026-10-15T10:44:00-07:00

- Action: User said to continue. Marked block 2 done. Pointed block 3's AttemptApi line at the script-result return, including a missing pending document.
- Result: Block 2 accepted. Narrative line for AttemptApi updated from the edit in entry 4.
- Evidence: walkthrough-seckill-hot-path.md block 3.
- Open: Block 3 in progress. Walkthrough notes and the sale edits are uncommitted.

## 7 — 3. The script decides the pool — thoughts

Session: 2f6e5a27-5b36-4647-a31b-12dec177390d · Timestamp: 2026-10-15T10:48:00-07:00

- Action: Thoughts on sale.lua. User said to commit the walkthrough notes and the sale edits only after the walkthrough is complete.
- Result: Script accepted as the single atomic writer. Open observations: unidentified in the script is ASCII whitespace; ack deletes whatever id it is given. No code change.
- Evidence: sale.lua:63 attempt, :85 id, :12 escape_json, :22 bound, :100 close, :116 ack. UserIds.java:23 normalizes Unicode blanks before the script.
- Open: Block 3 still in progress. Do not ask to commit until wrap-up.

## 8 — 3. The script decides the pool — accepted

Session: 2f6e5a27-5b36-4647-a31b-12dec177390d · Timestamp: 2026-10-15T10:57:00-07:00

- Action: User said to continue. Marked block 3 done and opened block 4.
- Result: Block 3 accepted as-is. No narrative change.
- Evidence: sale.lua attempt, close, and ack as discussed in entry 7.
- Open: Block 4 in progress. Commit waits until wrap-up.

## 9 — 4. The gateway stays off the write — accepted

Session: 2f6e5a27-5b36-4647-a31b-12dec177390d · Timestamp: 2026-10-15T10:58:00-07:00

- Action: User said block 4 is done. Added the `GET /sale` reference to that block. Pointed `OrderInsert.accept` at line 47.
- Result: Block 4 accepted. Block 5 is in progress.
- Evidence: AttemptEdge.java:37, AttemptRoutes.java:27, SaleRead.java:17.
- Open: Block 5 in progress. Commit waits until wrap-up.

## 10 — 5. The order follows the answer — accepted

Session: 2f6e5a27-5b36-4647-a31b-12dec177390d · Timestamp: 2026-10-15T10:59:00-07:00

- Action: User said block 5 is done. Opened block 6.
- Result: Block 5 accepted as-is. No narrative change.
- Evidence: QualificationRelay.java:42, OrderListener.java:16, OrderInsert.java:47, V1__orders.sql:1.
- Open: Block 6 in progress. Commit waits until wrap-up.

## 11 — 6. Reports and a down collector — accepted

Session: 2f6e5a27-5b36-4647-a31b-12dec177390d · Timestamp: 2026-10-15T11:00:00-07:00

- Action: User said block 6 is done. Opened the periphery. Pointed the Java 21 toolchain lines at sale/build.gradle:13 and gateway/build.gradle:13.
- Result: Block 6 accepted as-is.
- Evidence: QualificationReportPublisher.java:33, SaleLifecycleApi.java:42, CloseReport.java:26, ExportShed.java:36, DownstreamGuard.java:51.
- Open: Block 7 in progress. Commit waits until wrap-up.

## 12 — 7. Periphery — accepted

Session: 2f6e5a27-5b36-4647-a31b-12dec177390d · Timestamp: 2026-10-15T11:02:00-07:00

- Action: User said block 7 is done and asked to remove the repeated Blocks entries. Dropped the second unvisited copies of blocks 4–7.
- Result: All seven blocks accepted. Review is at wrap-up.
- Evidence: walkthrough-seckill-hot-path.md Blocks section.
- Open: User has not yet confirmed the commit of the walkthrough notes and the sale edits.

## 13 — wrap-up — commit

Session: 2f6e5a27-5b36-4647-a31b-12dec177390d · Timestamp: 2026-10-15T11:03:00-07:00

- Action: User confirmed the commit of the walkthrough notes and the sale edits. Left docs/devops untracked.
- Result: Commit created for OrderInsert comment, AttemptApi missing-document return, AttemptApiTest, and the walkthrough folder.
- Evidence: git status before the commit.
- Open: none
