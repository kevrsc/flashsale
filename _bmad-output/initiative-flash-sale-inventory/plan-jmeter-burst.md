---
title: 'JMeter burst'
type: 'feature'
ticket: ''
created: '2026-10-15'
status: 'built'
baseline_revision: '065a273d625a91b519e735d72d53063c115a6455'
route: 'full'
route_source: 'auto'
review: 'quick'
review_source: 'pinned'
lenses_ran: [quick]
review_loop_iteration: 0
context: []
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** The sale can qualify from one shared pool, but nothing sends the spec's burst: 100,000 attempts against an allotment of 100, expecting 100 qualifications and 100 orders. That check does not belong in `test`.

**Approach:** A separate Gradle task runs Apache JMeter 5.6.3 against a sale and gateway the operator has already started. It loads 100 units, starts the sale, sends 100,000 attempts through the gateway, and passes only when 100 attempts are qualified and those 100 ids each have one order.

## Boundaries & Constraints

**Always:** JMeter 5.6.3. The task is opt-in and is not a dependency of `test`, `check`, or JaCoCo. Load and start go to sale on port 8081. Attempts go to the gateway `POST /attempts` on port 8080, each with its own `userId`, each answered HTTP 200. Allotment is 100. The 100,000 responses finish within five minutes. After that, within two minutes, `orders` holds exactly those 100 qualification ids, one row each.

**Never:** Do not change `sale.lua`, the gateway routes, compose, or the three `test` tasks. Do not call the root app's `POST /orders`. Do not flush Redis or truncate `orders` from the task. Do not boot sale, gateway, or compose from the task. Do not assert qualification reports, the close report, or Prometheus rules here.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Burst | Empty `sale` hash and empty `orders`; sale on 8081 and gateway on 8080 | Load 100, start, 100,000 attempts, 100 bodies `{"answer":"qualified",...}`, then exactly 100 order rows for those ids | No error expected |
| Sale already used | Load returns 409 | Task fails before any attempt | Non-zero exit; Redis and `orders` unchanged by the task |
| Edge down | 8080 or 8081 not accepting connections | Task fails before the burst | Non-zero exit; no partial pass |
| Wrong tally | Qualified count is not 100, or an id has no row, or `orders` has any other row | Task fails | Non-zero exit after the two-minute order wait |

</frozen-after-approval>

## Code Map

- `build.gradle` -- add the opt-in task only; leave the `test` and JaCoCo blocks alone
- `gateway/src/main/java/com/flashsale/gateway/AttemptRoutes.java` -- `POST /attempts` on 8080 is the burst target
- `sale/src/main/java/com/flashsale/qualify/SaleLifecycleApi.java` -- `POST /sales/load` and `POST /sales/start` on 8081
- `sale/src/main/resources/db/migration/V1__orders.sql` -- `orders.qualification_id` is the row the task counts
- `sale/src/test/java/com/flashsale/qualify/SaleHotPathTest.java` -- request shapes at the `load`, `start`, and `attempt` helpers; do not extend this test with the burst
- `compose.yaml` -- Postgres `localhost:5432`, database `flashsale`, user and password `flashsale`; the operator starts it
- `_bmad-output/initiative-flash-sale-inventory/deferred-work.md` -- remove the JMeter entry only after the task passes; leave the Prometheus entry

## Tasks & Acceptance

**Execution:**
- [x] `loadtest/sale-burst.jmx` -- non-GUI plan: load `{"itemId":"burst","allotment":100}`, start `{}`, then 100,000 `POST /attempts` with distinct `userId` values -- this is the burst the spec names
- [x] `build.gradle` -- task `jmeterBurst` downloads Apache JMeter 5.6.3 if needed, runs that plan, then checks the tally -- keeps the burst out of `test`
- [x] `_bmad-output/initiative-flash-sale-inventory/deferred-work.md` -- delete the JMeter entry after the task passes -- the Prometheus entry stays

**Acceptance Criteria:**
- Given `./gradlew test`, when it runs, then `jmeterBurst` does not run.
- Given sale and gateway are already listening and the sale hash and `orders` are empty, when `./gradlew jmeterBurst` runs, then it exits 0 only for the Burst row.

## Implementation Notes

- `jmeterBurst` ran against a live sale: 100,000 attempts finished in 39362 ms, 100 qualified bodies, 100 matching order rows, exit 0.
- A closed 8080/8081 exited 1 before any attempt. A second run against the used sale exited 1 on load 409 with no attempts, and `orders` stayed at 100.
- The wrong-tally wait lives in `BurstTally` and is what the task calls. `BurstTallyTest` ran five cases, all passing: a missing order, an extra order, a qualified count other than 100, a query failure, and an immediate match.
- Review patches: the task throws if `qualified-ids.txt` cannot be deleted, and it closes the PostgreSQL classloader after the order poll. A second live run after that patch finished in 33863 ms with 100 qualified and 100 orders.
- Old-service cleanup: the root project no longer builds a Java 17 app. `jmeterBurst` takes the driver from the non-transitive `ordersDriver` configuration and launches JMeter on Java 21.
- During the walkthrough the user asked for a read-only clean-state check and a separate reset. `jmeterBurst` now fails before JMeter starts when `orders` or `pending` is not empty, and it still deletes nothing. `jmeterReset` is a separate opt-in task that refuses without `-PconfirmReset=local`. It deletes `sale` and `pending` and truncates `orders` and `qualification_report_sent`. The README and the devops notes mark it as local only, never for production. Verified live: a dirty sale failed in 1s, an unconfirmed reset refused and changed nothing, a confirmed reset emptied both stores, and the next burst finished in 33110 ms with 100 qualified and 100 orders.

## Plan Change Log

## Review Triage Log

- medium, patch. `build.gradle` ignores `idsFile.delete()`, and `loadtest/sale-burst.jmx` appends qualified bodies. A leftover `qualified-ids.txt` is mixed into the next run, so a good burst fails the 100-body check. The delete result is unused and the recorder calls `File.append`.
- low, patch. `build.gradle` never closes the PostgreSQL `URLClassLoader`. The driver registers itself and pins that loader, so after `jmeterBurst` the Gradle daemon can keep the driver jar locked. `URLClassLoader` is `Closeable` and the task never calls `close()`.

## Design Notes

The task polls `orders` after the HTTP responses because the attempt returns before the insert. The product path stays unchanged. The operator starts compose, `:sale:bootRun`, and `:gateway:bootRun`, and presents an empty `sale` hash and an empty `orders` table. A 409 on load is the signal to reset by hand.

## Verification

**Commands:**
- `./gradlew help --task jmeterBurst` -- expected: the task exists and `test` does not depend on it
- `./gradlew jmeterBurst` -- expected: exit 0, 100 qualified attempts, 100 order rows, finished inside the five-minute and two-minute limits

**Manual checks (if no CLI):**
- Before the burst: `docker compose up` is healthy, sale answers on 8081, gateway answers on 8080, `HGETALL sale` is empty, and `SELECT COUNT(*) FROM orders` is 0
