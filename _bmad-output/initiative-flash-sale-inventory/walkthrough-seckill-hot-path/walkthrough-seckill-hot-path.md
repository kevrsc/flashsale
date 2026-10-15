# Seckill hot path

Current block: wrap-up

## Blocks

- [x] **1. Intent** — done
- [x] **2. Broad strokes** — done
- [x] **3. The script decides the pool** — done
- [x] **4. The gateway stays off the write** — done
- [x] **5. The order follows the answer** — done
- [x] **6. Reports and a down collector** — done
- [x] **7. Periphery** — done

## 1. Intent

**Problem:** The current service decides the pool by locking one Postgres row. That path cannot be the seckill hot path in the spec.

**Approach:** Add `sale` and `gateway` beside the current tree and move qualification, orders, and reports onto the spine. The current `src/` tree stays until those paths pass.

Taken verbatim from the Intent section of [plan-seckill-hot-path.md](../plan-seckill-hot-path.md), the plan for commit `18ca4d9`.

## 2. Broad strokes

An attempt hits the gateway first. The gateway answers from the Redis hash when the sale is already not open, closed, or sold out. Otherwise it forwards to sale, and one Lua script is the only thing that decrements the shared pool. The HTTP answer comes back from that script. Kafka, the order row, and the reports happen after.

Open these in order:

- [sale.lua](../../../sale/src/main/resources/sale.lua) — the only writer of the pool and of a new qualification document
- [AttemptRoutes.java](../../../gateway/src/main/java/com/flashsale/gateway/AttemptRoutes.java) — answers at the edge or forwards `POST /attempts`
- [AttemptApi.java](../../../sale/src/main/java/com/flashsale/qualify/AttemptApi.java) — returns the script result as HTTP 200
- [QualificationRelay.java](../../../sale/src/main/java/com/flashsale/qualify/QualificationRelay.java) — publishes the qualification document after that response
- [OrderListener.java](../../../sale/src/main/java/com/flashsale/order/OrderListener.java) — inserts one order, then acks Redis

## 3. The script decides the pool

`attempt` checks unidentified, then closed, then not open, then sold out, and only then decrements. The same execution writes the qualification document under hash `pending`. A blank user id, including Unicode whitespace, never reaches that decrement. The id is a hash of the user id, the remaining count, and Redis `TIME`, each length-prefixed so those values cannot blur together.

- [sale.lua](../../../sale/src/main/resources/sale.lua:63) — `attempt` returns the answer and, when qualified, the qualification id
- [sale.lua](../../../sale/src/main/resources/sale.lua:85) — the id is built inside the script, and the document is stored before the script returns
- [UserIds.java](../../../sale/src/main/java/com/flashsale/qualify/UserIds.java:23) — a blank user id becomes empty before the script runs
- [AttemptApi.java](../../../sale/src/main/java/com/flashsale/qualify/AttemptApi.java:47) — the response body is the script result, including when the pending document is already gone

## 4. The gateway stays off the write

The gateway reads `closed`, `open`, `soldOut`, and `remaining` with one hash read. When that read already decides the answer, it responds and does not call sale. A sale that can still qualify is forwarded, and that forward is not wrapped in a breaker. A read of the sale state is the same hash, not the script.

- [AttemptEdge.java](../../../gateway/src/main/java/com/flashsale/gateway/AttemptEdge.java:37) — `answer` returns unidentified, closed, not open, or sold out, or nothing
- [AttemptRoutes.java](../../../gateway/src/main/java/com/flashsale/gateway/AttemptRoutes.java:24) — forwards only when `answer` is empty
- [AttemptEdge.java](../../../gateway/src/main/java/com/flashsale/gateway/AttemptEdge.java:58) — `read` is the hash, for a caller that is not attempting
- [SaleRead.java](../../../gateway/src/main/java/com/flashsale/gateway/SaleRead.java:17) — `GET /sale` returns that read

## 5. The order follows the answer

The response does not wait on Kafka or Postgres. After the response, the relay publishes the pending document to `sale.qualification`. The consumer inserts one row keyed by qualification id and deletes that pending field only after the insert commits. A second delivery of the same document does not create a second row. The unit is not returned if payment never finishes.

- [QualificationRelay.java](../../../sale/src/main/java/com/flashsale/qualify/QualificationRelay.java:42) — `afterCompletion` starts the publish on a virtual thread
- [QualificationRelay.java](../../../sale/src/main/java/com/flashsale/qualify/QualificationRelay.java:49) — `republish` sends any pending field that is still there
- [OrderListener.java](../../../sale/src/main/java/com/flashsale/order/OrderListener.java:16) — the group `sale-qualification` is the only consumer that inserts
- [OrderInsert.java](../../../sale/src/main/java/com/flashsale/order/OrderInsert.java:47) — `accept` inserts, then calls `ack`
- [V1__orders.sql](../../../sale/src/main/resources/db/migration/V1__orders.sql:1) — `qualification_id` is the primary key

## 6. Reports and a down collector

Each committed order is published to `sale.qualification-report` until the broker acks. `close` stores the remaining count once and publishes `sale.close-report`. A later close sends that same body again. An open breaker may drop span and log export. It does not wrap the script, the gateway forward, or the decision of who qualified. Insert and the qualification report still run when their breakers are open.

- [QualificationReportPublisher.java](../../../sale/src/main/java/com/flashsale/report/QualificationReportPublisher.java:33) — retries orders that have no report row yet
- [SaleLifecycleApi.java](../../../sale/src/main/java/com/flashsale/qualify/SaleLifecycleApi.java:40) — `close` runs the script, then publishes
- [CloseReport.java](../../../sale/src/main/java/com/flashsale/qualify/CloseReport.java:26) — a failed close publish is tried again on the next close
- [ExportShed.java](../../../sale/src/main/java/com/flashsale/sale/ExportShed.java:36) — wraps span and log exporters
- [DownstreamGuard.java](../../../sale/src/main/java/com/flashsale/sale/DownstreamGuard.java:51) — `export` sheds; `insert` and `report` do not

## 7. Periphery

- [settings.gradle](../../../settings.gradle:6) — includes `sale` and `gateway`; the root project name stays `flashsale`
- [sale/build.gradle](../../../sale/build.gradle:13) — Java 21, servlet Boot, JaCoCo 95% line and branch
- [gateway/build.gradle](../../../gateway/build.gradle:13) — the same language level and the servlet gateway starter
- [compose.yaml](../../../compose.yaml:16) — Redis `8.10.2` on port 6379
- [compose.yaml](../../../compose.yaml:57) — `sale.qualification`, `sale.qualification-report`, and `sale.close-report` beside the existing topic
- [application.properties](../../../sale/src/main/resources/application.properties:3) — virtual threads, and Redis, Kafka, and the collector on localhost
- [application.properties](../../../gateway/src/main/resources/application.properties:8) — forwards to sale on port 8081
- [sale.json](../../../grafana/dashboards/sale.json) — sale panels on the existing Prometheus datasource
- [SaleApiRoutesTest.java](../../../src/test/java/com/flashsale/api/SaleApiRoutesTest.java) — the old `SaleApi` still has no seckill route
- [deferred-work.md](../deferred-work.md) — the 100,000-attempt burst and the Prometheus `seckill.rules` alerts are not in this change
