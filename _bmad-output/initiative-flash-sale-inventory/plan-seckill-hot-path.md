---
title: 'Seckill hot-path replacement'
type: 'feature'
ticket: ''
created: '2026-10-14'
status: 'built'
baseline_revision: '18d02aa78aad123fee5a508ac4ee1e92213c3b96'
route: 'full'
route_source: 'auto'
review: 'quick'
review_source: 'pinned'
lenses_ran: [quick]
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/initiative-flash-sale-inventory/spec-seckill-hot-path/spec-seckill-hot-path.md'
  - '{project-root}/_bmad-output/initiative-flash-sale-inventory/architecture-seckill-hot-path/architecture-seckill-hot-path.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** The current service decides the pool by locking one Postgres row. That path cannot be the seckill hot path in the spec.

**Approach:** Add `sale` and `gateway` beside the current tree and move qualification, orders, and reports onto the spine. The current `src/` tree stays until those paths pass.

## Boundaries & Constraints

**Always:** AD-1 through AD-10. Root `languageVersion` stays Java 17 while `src/` remains. `sale` and `gateway` are Java 21 with `spring.threads.virtual.enabled=true`. JaCoCo stays 95% line and 95% branch on the new projects and on the current one. Compose service names and host ports stay. Traces, metrics, and logs go to the existing collector. Prometheus keeps scraping `otel-collector:8889`.

**Never:** WebFlux, the reactor breaker starter, Modulith or the SQL outbox in the new projects, RabbitMQ, a breaker around the Lua script or around the forward of `POST /attempts`, and new routes on `SaleApi`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Qualify | Open sale, remaining above 0, real userId | HTTP 200 `qualified` plus one qualificationId; pool loses one | Response does not wait on Kafka, Postgres, or inventory |
| Cannot qualify | Not open, closed, sold out, or blank userId | Matching answer, pool unchanged, no order | Unidentified is checked before the other answers |
| Last unit, two instances | Two sale processes, one unit left | One qualified, one sold out | The shared script is the only decrement |
| Retry | Same qualification document delivered twice | One order row | Ack runs only after the insert commits |
| Export down | Decrement succeeds, collector refuses export | Caller still hears qualified | Breaker may shed export only |

</frozen-after-approval>

## Code Map

- `settings.gradle` -- one project today, Foojay resolver, `rootProject.name = flashsale`. Add `include` for `sale` and `gateway`. Do not change the root name.
- `build.gradle` -- Boot 4.1.1, `JavaLanguageVersion.of(17)`, JaCoCo 0.95 line and branch, Modulith and the SQL outbox. Leave this toolchain and these dependencies in place while `src/` remains.
- `src/main/java/com/flashsale/api/SaleApi.java` -- current `POST /orders`, `POST /sale`, `GET /sale`, `POST /orders/{orderId}/release`. Do not add seckill routes here.
- `src/main/java/com/flashsale/inventory/Orders.java` -- Postgres row lock and conditional update. The new pool does not call this.
- `compose.yaml` -- postgres 5432, kafka 9092, kafka-init topic `inventory.order-accepted`, otel 4318, prometheus 9090, grafana 3000. No Redis. Add Redis and the three `sale.*` topics. Leave existing services.
- `prometheus.yml` -- one scrape, `otel-collector:8889`. Leave that scrape as the only one.
- `otel-collector-config.yaml` -- OTLP 4318, Prometheus exporter 8889. Reuse it.
- `src/main/resources/application.properties` -- JDBC, Kafka `localhost:9092`, OTLP `localhost:4318`. New processes use these URLs plus Redis `localhost:6379`.

## Tasks & Acceptance

**Execution:**
- [ ] `settings.gradle`, `sale/build.gradle`, `gateway/build.gradle` -- add the two Boot projects on Java 21, virtual threads, JaCoCo 95/95, servlet starters from the spine. Root toolchain stays 17.
- [ ] `compose.yaml` -- add Redis `8.10.2` on 6379 and the three `sale.*` topics beside `inventory.order-accepted`.
- [ ] `sale/` -- Lua commands `load`, `start`, `attempt`, `close`, `ack`, and the HTTP routes in AD-6 and AD-8.
- [ ] `gateway/` -- servlet gateway reads hash `sale` and forwards only attempts it cannot already answer. No breaker on that forward.
- [ ] `sale/` -- Flyway `orders`, consumer group `sale-qualification`, ack after commit.
- [ ] `sale/` -- qualification-report publisher, close report owned by `close`, Resilience4j around insert, report, and export.
- [ ] `sale/`, `gateway/` -- export traces, metrics, and logs to the existing collector. Logs name the answer and, for a qualification, the qualification id. The order-insert log includes that id.
- [ ] `sale/src/test`, `gateway/src/test` -- cover the matrix, including two sale instances against one Redis.

**Acceptance Criteria:**
- Given `src/` is still present, when the root project compiles, then its toolchain is Java 17 and `SaleApi` has no seckill route.
- Given two sale instances and one unit left, when both attempt, then one caller is qualified and the other is sold out.
- Given a qualification id with no order row, when the build runs, then the build fails.

## Implementation Notes

## Plan Change Log

## Review Triage Log

- verdict: false
  finding: A qualification id with no order leaves the Gradle build green because `QualificationOrderGate.require` runs only after the row is seen, and a random UUID expects the exception.
  evidence: `SaleHotPathTest.qualifyReturnsBeforeKafkaPostgresAndInventory` waits until `orders()` is 1 and then calls `gate.require`. If that row never appears, `await` fails the test and the Gradle build fails. The `assertThrows` on a random UUID only shows that the gate throws for an id the sale did not issue.

- verdict: false
  finding: A failed close report is dropped after three tries, so inventory can miss `sale.close-report`.
  evidence: `script.close()` stores `closeRemaining` before `CloseReport.publish`. AD-7's until-ack retry is for `sale.qualification-report`, which `QualificationReportPublisher` retries on a schedule. AD-8 says a second close republishes the stored body, and `SaleLifecycleApi.close` calls `publish` on every close.

- verdict: false
  finding: `AttemptApi` can return an error after a successful decrement because `script.pending` throws when `ack` deletes the document before the second read.
  evidence: `pending` is called on the request thread immediately after `EVAL`. `afterCompletion` publishes only after the controller returns. `republish` must finish a Kafka ack and the order insert before `ack` can delete the field, which cannot beat that next Redis read.

- verdict: medium
  finding: `sale.lua` hashes `userId`, `remaining`, and `TIME` by concatenation, so distinct pairs such as `ada`/`15` and `ada1`/`5` in the same microsecond overwrite one pending document and one decrement has no order.
  evidence: The script uses `userId .. remaining .. time[1] .. time[2]` with no boundary. The later `HSET` on hash `pending` replaces the first document, and `ON CONFLICT DO NOTHING` keeps a single order row.

- verdict: low
  finding: The gateway treats Unicode whitespace such as U+2003 as unidentified, while the script's `%s` class does not, so the same id can decrement on `POST /attempts` at the sale process.
  evidence: `AttemptEdge.answer` uses `String.isBlank()`. The script uses `^%s*$`. `UserIds.read` returns that Unicode id unchanged, and the script then decrements.

- verdict: low
  finding: The scheduled relay can publish `sale.qualification` before the attempt response is written.
  evidence: `afterCompletion` publishes only after the response. The 200ms `republish` poll can see the new pending field during the few milliseconds before the response is flushed. Callers are unlikely to meet that window, and stopping it needs a new sent-flag rather than a direct correction. Rejected.

- verdict: high
  finding: A `userId` containing a control character other than CR, LF, or tab produces a qualification document that is not valid JSON, so the relay never publishes it and the unit never becomes an order.
  evidence: `escape_json` escapes `\`, `"`, CR, LF, and tab only. `QualificationRelay.publishQuietly` catches `readTree` failure, logs, and returns, leaving the pending field in place.

- verdict: false
  finding: The 100,000-attempt allotment check from the spec is not in the tests.
  evidence: That burst was split out of this plan before approval and recorded in `deferred-work.md`. It is not an acceptance criterion of this plan. Rejected.

## Verification

**Commands:**
- `./gradlew test jacocoTestCoverageVerification` -- expected: the current tree and the new projects pass, coverage at least 95% line and branch
