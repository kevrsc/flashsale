# Rubric review — Seckill hot-path architecture spine

Verdict: **Fail**

The spine was not edited. This is a good-spine checklist review of `architecture-seckill-hot-path.md` (draft, altitude initiative, updated 2026-10-14).

- Driving source: `prd-seckill-hot-path/prd-seckill-hot-path.md` (final) and `addendum.md`
- Brownfield: `compose.yaml`, `build.gradle`, `application.properties`, `otel-collector-config.yaml`, `prometheus.yml`
- Versions checked 2026-10-14, listed at the end. No parent spine is in force. The earlier `architecture-flash-sale-inventory` spine stays the record of the previous service.

The paradigm holds: one Redis Lua script decides the pool, the attempt answer comes back from that script, Kafka carries the qualification id, and Postgres stores one order per id. The fail is at the seams two features would have to share, and at the operational envelope this altitude leaves unspoken.

## Checklist

| Check | Result |
| --- | --- |
| Fixes the real divergence points one level down and misses none | **Gap.** Answers, topics, the single broker, the two deployables, and the no-cap rule are fixed. The Redis state the gateway and the script share, the attempt API, the qualification record, and where the new processes live are not. |
| Every AD Rule is enforceable and actually prevents its stated divergence | **Gap.** AD-5, AD-2's deployable split, and AD-9's "a down collector does not change the Lua result" hold. AD-1 forbids a pending-set write that AD-4 requires. AD-6 and AD-8 name flags they do not define. AD-8's close-report prevent is not in its rule. |
| Nothing under Deferred could let two units diverge | **Pass.** CDN placement, the admission number, inventory's stock posting, a second sale, payment return, and an internal clock each have one owner or are product non-goals. One deferred sentence cites a PRD fraction the final PRD does not contain; that is a wording fix, not a second contract. |
| Named tech is verified-current | **Gap on coordinates, not on the numbers.** The stack numbers that were checked are published and, where the repo already pins them, they match. The gateway starter and the gateway's breaker starter are unnamed, and `redis:8` is a floating tag. |
| Brownfield: ratify, do not contradict the codebase | **Pass on the pins.** Java 17, Boot 4.1.1, JaCoCo 95/95, Postgres 18.6, Kafka 4.3.1, the collector, Prometheus, and Grafana match the repo. Dropping Modulith matches this PRD. The spine never says the new processes join the existing compose file and the existing URLs, so that ratification is incomplete rather than a contradiction. |
| Covers the PRD capabilities | **Gap.** FR-3, FR-4, FR-6, and the stock-posting split in FR-9 are covered. FR-1's start signal, FR-5's flag short-circuit, FR-7's order contents, FR-8's remaining read, and FR-11's traces and order log are only partly in a rule. Settled product rules were not re-opened. |
| Parent spine inherited without a local AD weakening it | **Pass.** No parent. No Inherited Invariants. No local AD weakens one. |
| Every dimension this altitude owns is decided, deferred, or an open question | **Gap.** Deployment and environments, infra/provider strategy, and operations are silent. Stack image versions are not that envelope. |

## Findings

### 1. High — Deployment, environments, infra, and operations are silent

**Location:** Structural Seed, Stack, Deferred. No open question carries them.

**What is wrong:** Initiative altitude owns this envelope. The spine pins images and says the sale application exports OTLP to "the existing collector." It never says where Redis, the gateway, and the sale process run, which environment this learning model is, or which provider strategy applies. `compose.yaml` has Postgres, Kafka, the collector, Prometheus, and Grafana, and it has no Redis. Two features can add a second broker, point OTLP at a new port, or scrape Prometheus directly from the app. Nothing defers health, restart, or alerting either.

**Smallest fix:** Decide the local envelope in the seed, and defer only the hosted remainder. This learning model is the existing `compose.yaml` plus one Redis service. `sale` and `gateway` are two local processes on that network. They use the URLs already in `application.properties`: `jdbc:postgresql://localhost:5432/flashsale`, Kafka `localhost:9092`, and OTLP `http://localhost:4318/v1/traces`, `http://localhost:4318/v1/metrics`, and `http://localhost:4318/v1/logs`, with sampling probability 1. New topics are created the way `kafka-init` creates topics, replication factor 1. `inventory.order-accepted` belongs to the previous service. A hosted provider, process supervision, health checks, and alerting are deferred until the processes are no longer started by hand.

**Disposition:** autofix.

### 2. High — The gateway and the Lua script do not share a sale-state contract

**Location:** AD-1 Rule, AD-6 Rule, AD-8 Rule, the dependency diagram.

**What is wrong:** AD-6 says the gateway reads the open flag and the sold-out flag and answers `not_open`, `closed`, or `sold_out`. AD-8 says close sets a closed flag, and it never says the start signal sets the open flag. AD-1 says those flags change only inside one Lua script, and it never says the decrement sets sold-out when the count hits zero. No key names are given. A gateway feature and a script feature can answer `closed` and `not_open` from different encodings, and the gateway filter never sees sold-out if the script never writes the flag. AD-8 also prevents "a close report that disagrees with the pending set," but the rule only says the report carries the remaining count. The pending set is qualifications not yet ordered. The remaining count is the pool. A feature that forces those two numbers to match will publish the wrong close report.

FR-8's remaining read has the same hole. The paradigm says reads never call the script. No rule says a remaining read is a GET of the pool key, so a read feature can EVAL the script and take the qualification path.

**Smallest fix:** Name one key each for remaining, open, closed, sold-out, and the pending set. Load, start, close, and the attempt are commands of that same script. Start sets open and does not change remaining. The attempt command returns `not_open`, `closed`, or `sold_out` without a decrement and without a new id; on the decrement that reaches zero it sets sold-out in that same execution. Close stores the Redis remaining count and later closes republish that stored count. The pending set is not that count. Gateway and other readers GET the flag keys and the remaining key. They do not EVAL the script.

**Disposition:** autofix.

### 3. High — AD-1 forbids the pending-set removal AD-4 requires

**Location:** AD-1 Rule ("change only inside one Lua script"), AD-4 Rule ("The consumer removes the id from the pending set only after the insert commits").

**What is wrong:** The order consumer's removal is a write to the pending id. AD-1 allows that write only inside the script. One feature will `SREM` from the consumer. Another will refuse any write outside `EVAL` and leave the id pending forever, so the relay republishes after the order exists. The rule does not prevent the divergence it names, because the two rules cannot both be followed as written.

**Smallest fix:** Narrow AD-1. The script is the only writer that adds a pending id, changes remaining, or changes the flags. The order consumer is the only remover, and only after the insert commits.

**Disposition:** autofix.

### 4. High — The gateway and the sale application have no attempt or record contract

**Location:** AD-6 Rule, Consistency Conventions (Data and formats), AD-4, AD-5, the sequence diagram, the ERD.

**What is wrong:** AD-6 fixes the `answer` strings and says `qualificationId` is present only for `qualified`. It does not fix the path, the HTTP status, or the request body. The convention lists `answer`, `qualificationId`, `saleId`, `userId`, `itemId`, and `remaining` as one set. It does not say which message carries which field. AD-4 says the Kafka key and the order primary key are the qualification id. The pending structure in the diagram is a set of ids. A set of ids cannot carry `userId` or `itemId`, and the order consumer is not allowed to call the inventory service or to read a customer store. One feature will publish only the id. Another will expect the order row to contain the customer. Load, start, and close are "calls" with no path and no body, so the lifecycle feature and the script feature can also miss each other.

**Smallest fix:** One attempt route, `POST /attempts`, body `userId`. Every attempt response is HTTP 200 with `answer`. `qualificationId` is present only for `qualified`. Missing `userId` is `unidentified` and does not call the script. Lifecycle commands are `POST /sales/load` with `itemId` and the allotment quantity, `POST /sales/start`, and `POST /sales/close`. The pending entry and the `sale.qualification` record carry `qualificationId`, `saleId`, `userId`, and `itemId`, keyed by `qualificationId`. The order table is Flyway, matching this repo, and its primary key is that id plus those fields. The qualification report uses the same id as its key. The close report carries `remaining` and the stored count from finding 2.

**Disposition:** autofix.

### 5. Medium — The gateway artifact and its breaker starter are not the ones a builder can resolve from the stack table

**Location:** Stack (Spring Cloud Gateway 5.0.3, Spring Cloud Circuit Breaker 5.0.3), AD-6, AD-7.

**What is wrong:** AD-7 names `spring-cloud-starter-circuitbreaker-resilience4j` for the sale application. That starter is real at 5.0.3 and it depends on Resilience4j 2.3.0. It does not bring `resilience4j-reactor`. The gateway rule says a reactive breaker wraps gateway routes, and it never names `spring-cloud-starter-circuitbreaker-reactor-resilience4j`, which is the 5.0.3 artifact that does depend on `resilience4j-reactor` 2.3.0. The stack says "Spring Cloud Gateway 5.0.3" and does not name `spring-cloud-starter-gateway-server-webflux`. That WebFlux starter is on Maven Central at 5.0.3. Its POM depends on `spring-boot-starter-webflux` 4.0.8, while this repo's Boot plugin is 4.1.1. A gateway feature can take the WebFlux server or the MVC server. Those are different processes. The reactive sentence is not the artifact id.

The same AD-7 sentence lets an open breaker "retry or shed" the order insert and the report. FR-10 retries a failed report and does not grant another unit. Shedding the report drops it. The pending-set relay covers the order id, not the report. The gateway breaker "wraps only gateway routes" can also wrap the attempt route and turn a qualification into a shed.

**Smallest fix:** The gateway depends on `spring-cloud-starter-gateway-server-webflux` and `spring-cloud-starter-circuitbreaker-reactor-resilience4j` at the 5.0.3 BOM. The sale application depends on `spring-cloud-starter-circuitbreaker-resilience4j` and stays on `spring-boot-starter-webmvc`. Keep the Boot plugin at 4.1.1 when the Cloud BOM is imported. An open breaker may shed telemetry export. It retries the order insert and the report publish until the row exists and the report is published. The gateway breaker does not wrap `POST /attempts`.

**Disposition:** autofix.

### 6. Medium — FR-11 can be satisfied by three different telemetry pipes

**Location:** AD-9 Rule, capability map row FR-11.

**What is wrong:** The PRD requires metrics, traces, and logs, and a log that ties a qualification to its order. AD-9 says OTLP, Prometheus, and Grafana, and that logs name the answer and, for `qualified`, the qualification id. The order consumer is not required to log that id. The repo's collector already exports metrics to Prometheus on `otel-collector:8889`, and traces and logs go to the collector's debug exporter. AD-9 prevents "a second metrics stack" without saying the scrape target stays that collector. One feature can export OTLP. Another can add a direct Prometheus scrape and a new Grafana datasource. The provisioned home dashboard is still the previous service's `orders.json`.

**Smallest fix:** Sale and gateway export traces, metrics, and logs to the three existing OTLP paths. Prometheus keeps scraping `otel-collector:8889`. Grafana keeps the existing Prometheus datasource. The sale feature that exports also owns the sale panels, so a second feature does not invent series names. The order-insert log includes the qualification id.

**Disposition:** autofix.

### 7. Medium — `sale/` and `gateway/` can still be built inside the current Modulith application

**Location:** Structural Seed, Consistency Conventions (Naming), AD-2.

**What is wrong:** The seed shows two directories. The convention says packages `qualify`, `order`, and `report` live inside the sale application. `settings.gradle` is still one project, and `build.gradle` still applies the Modulith and Namastack starters to that project. This effort replaces that service, and the spine does not have to keep Modulith. As written, one feature can add those packages to `src/` and keep the outbox, and another can create two new processes. They will not share one Redis or one consumer group.

**Smallest fix:** `sale` and `gateway` are new Gradle subprojects and new Boot jars on the repo's Java 17 toolchain, with the same JaCoCo 95% line and 95% branch gate. They do not depend on `spring-modulith` or `namastack-outbox`. The existing `src/` tree is the previous service and is not extended.

**Disposition:** autofix.

### 8. Low — `redis:8` is a floating tag, and one deferred sentence cites a fraction the PRD does not require

**Location:** Stack row "Redis image / redis:8". Deferred, "The numeric rate the gateway admits."

**What is wrong:** The official Redis tag list checked 2026-10-14 groups `8.10.2`, `8.10`, and `8` together. Postgres and Kafka in this repo are patch tags. `redis:8` can move. The deferred bullet says the PRD requires a fraction of the crowd and not a number. The final FR-5 does not. It requires an answer with no decrement once the sale is known empty, not open, or closed. The addendum still mentions a fraction. Leaving the citation as a PRD requirement invites a gateway feature to invent a cap the PRD's tests do not assert.

**Smallest fix:** Pin `redis:8.10.2`. Change the deferred bullet to: no numeric admission rate is asserted; the gateway may throttle; the sale path still answers from the flags and the script.

**Disposition:** autofix.

## Deferred

These do not let two units pick incompatible behavior. Finding 8 only corrects the fraction citation.

- CDN vendor, cache keys, and TLS. The gateway is the filter this learning model runs.
- Inventory's internal stock posting. The spine's job is the two Kafka reports, once finding 4 names them.
- More than one open sale.
- Returning a unit when payment does not finish.
- A sale clock inside this service.

After finding 1, hosted infrastructure, process supervision, health, and alerting belong here too, with the revisit "when the processes are no longer started by hand."

## What holds

Hot-counter qualification is the right paradigm for this PRD. AD-1's atomic decrement, the ban on a Postgres read to decide the attempt, and the ban on a per-customer cap match FR-3 and FR-6. AD-5 fixes Kafka and names the three topics, and it rules RabbitMQ out. AD-2's two deployables and one consumer group are the right split, once the new projects are explicit. AD-4's idempotent order primary key, the build failure when a qualification id has no order, and the relay of ids still pending match FR-7. AD-3 keeps the inventory service outside the attempt. The five answer strings match the glossary. JaCoCo at 95% line and 95% branch matches `build.gradle`. The convention that tests cover FR-1 through FR-10, including two sale instances against one Redis, matches FR-11's suite. No AD re-opens repeat qualification, payment return, or the inventory service's stock rule.

## Versions checked 2026-10-14

| Pin | Check |
| --- | --- |
| Java 17, Spring Boot 4.1.1, JaCoCo 95% line and 95% branch | Match `build.gradle`. Boot 4.1.1's `spring-boot-kafka` POM depends on Spring Kafka 4.1.1. |
| Spring Cloud 2025.1.3 | Newest GA Oakwood found. Release announcement (2026-08-20) says the train is based on Boot 4.0.x and compatible with Boot 4.1.x. 2025.1.4 was still unreleased. Milestone `2026.0.0-M1` was not used. |
| Spring Cloud Gateway 5.0.3 | Module version in that train. `spring-cloud-starter-gateway-server-webflux:5.0.3` is on Maven Central. Its POM depends on `spring-boot-starter-webflux` 4.0.8. Gradle resolution against Boot plugin 4.1.1 was not run. |
| Spring Cloud Circuit Breaker 5.0.3, Resilience4j 2.3.0 | `spring-cloud-starter-circuitbreaker-resilience4j:5.0.3` depends on Resilience4j 2.3.0 and does not depend on `resilience4j-reactor`. `spring-cloud-starter-circuitbreaker-reactor-resilience4j:5.0.3` does. |
| Apache Kafka image 4.3.1, PostgreSQL 18.6 | Match `apache/kafka:4.3.1` and `postgres:18.6`. Boot 4.1.1 manages `kafka-clients` 4.2.1. No Apache compatibility page for client 4.2.1 against broker 4.3.1 was fetched. |
| Redis `redis:8` | Official tag list groups it with `8.10.2`. Not in `compose.yaml`. |
| OpenTelemetry collector 0.162.0, Prometheus 3.15.0, Grafana 13.2.3 | Match `compose.yaml` (`prom/prometheus:v3.15.0`, `grafana/grafana:13.2.3`). Upstream newer than those repo tags was not re-checked. |
