# Version reality check — Seckill hot-path architecture spine

Checked 2026-10-14 against `build.gradle`, `compose.yaml`, the collector, Prometheus, and Grafana config in this repo, Maven Central POMs, the Spring Cloud project page, the Spring Cloud 2025.1.3 release announcement, Spring Cloud Gateway and Circuit Breaker docs, the official Redis image tag list, and the current Redis Lua docs.

## Verdict

Conditional pass. Every version number in the stack table matches this repo or a published artifact fetched for this check. The gaps are coordinates and pairings the spine does not record.

## Stack table

| Spine pin | Result | Evidence |
| --- | --- | --- |
| Java 17 | Matches the project | `build.gradle` toolchain `JavaLanguageVersion.of(17)` |
| Spring Boot 4.1.1 | Matches the project. On the Maven Central directory fetched this check, 4.1.1 (2026-08-20) is the newest 4.1 entry. 4.2.0-M1 and 4.2.0-M2 are milestones and were not used as the pin. | `org.springframework.boot` plugin `4.1.1` |
| Spring Cloud 2025.1.3 | Newest non-milestone in the `spring-cloud-dependencies` metadata list. Compatible with Boot 4.1.x by the release announcement. The BOM property is Boot 4.0.8. | Finding 3 |
| Spring Cloud Gateway 5.0.3 | Matches the 2025.1.3 BOM property `spring-cloud-gateway.version`. The starter coordinate is not in the spine. | Finding 1 |
| Spring Cloud Circuit Breaker 5.0.3 | Matches the BOM property `spring-cloud-circuitbreaker.version`. | `spring-cloud-dependencies-2025.1.3.pom` |
| Resilience4j 2.3.0 | Matches the circuit-breaker BOM and the published starter POM. | Finding 2 |
| Spring Kafka 4.1.1 | Matches the Boot 4.1.1 BOM and the Kafka library already declared by an artifact in this repo. | Finding 5 |
| Apache Kafka image 4.3.1 | Matches the project | `compose.yaml` image `apache/kafka:4.3.1` |
| PostgreSQL 18.6 | Matches the project | `compose.yaml` image `postgres:18.6` |
| Redis image `redis:8` | The tag exists. It is not in this repo. It is a moving major tag. | Finding 4 |
| OpenTelemetry collector 0.162.0 | Matches the project | `otel/opentelemetry-collector:0.162.0` |
| Prometheus 3.15.0 | Matches the project version. The compose tag is `prom/prometheus:v3.15.0`. | `compose.yaml` |
| Grafana 13.2.3 | Matches the project | `grafana/grafana:13.2.3` |

JaCoCo at 95% line and 95% branch matches `jacocoTestCoverageVerification` in `build.gradle`. Neither the spine nor the Gradle file pins a JaCoCo tool version.

## Named technologies

Kafka, Postgres, the OpenTelemetry collector, Prometheus, and Grafana are running definitions in `compose.yaml`. The collector metrics pipeline exports Prometheus on port 8889. `prometheus.yml` scrapes `otel-collector:8889`. The Grafana datasource URL is `http://prometheus:9090`. That matches AD-9.

Current Redis docs still describe one Lua 5.1 engine, `EVAL`, and atomic script execution. The official Redis tag list fetched this check groups `8.10.2`, `8.10`, `8`, and `latest`.

`spring-cloud-starter-circuitbreaker-resilience4j:5.0.3` is on Maven Central. `spring-cloud-starter-gateway-server-webflux:5.0.3` is on Maven Central. `spring-cloud-starter-gateway:5.0.3` returns 404.

RabbitMQ is named only as unused. No RabbitMQ version was checked.

## Findings

### 1. The Boot 4 gateway starter name is absent, and the old name is gone at 5.0.3

AD-6 says the gateway is Spring Cloud Gateway. The table says 5.0.3. The spine never names `spring-cloud-starter-gateway-server-webflux`.

Fetched 2026-10-14:

- Maven Central returns 200 for `org.springframework.cloud:spring-cloud-starter-gateway-server-webflux:5.0.3`.
- Maven Central returns 404 for `org.springframework.cloud:spring-cloud-starter-gateway:5.0.3`.
- The Spring Cloud Gateway project page tells a WebFlux server to use `spring-cloud-starter-gateway-server-webflux`. AD-7's reactive breaker is that server. The page also documents `spring-cloud-starter-gateway-server-webmvc` for the MVC server.
- The 5.0.3 WebFlux starter POM depends on `spring-boot-starter-webflux` version `4.0.8`.

Gateway 5.0.3 is the version in the 2025.1.3 BOM. The historical starter id does not exist at that version.

### 2. The gateway's reactive breaker is a different starter from the one AD-7 names

`spring-cloud-starter-circuitbreaker-resilience4j:5.0.3` is on Maven Central. Its POM depends on `resilience4j-circuitbreaker` 2.3.0 and `resilience4j-timelimiter` 2.3.0. It does not depend on `resilience4j-reactor`. That starter is the one AD-7 assigns to the order insert, the report publish, and telemetry export inside the sale application. The sale application in this repo uses `spring-boot-starter-webmvc`, so the non-reactive starter is the matching one for that process.

The same rule says the gateway's reactive breaker wraps only gateway routes. Current Spring Cloud Gateway WebFlux docs say the CircuitBreaker filter needs `spring-cloud-starter-circuitbreaker-reactor-resilience4j` on the classpath. That artifact exists at 5.0.3. Its POM adds `resilience4j-reactor` 2.3.0.

`spring-cloud-circuitbreaker-dependencies` 5.0.3 sets `<resilience4j.version>2.3.0</resilience4j.version>`, imports `resilience4j-bom` at that version, and manages both starters. The spine's Resilience4j 2.3.0 matches that BOM. The reactor starter id is not in the spine. The architecture memlog says "reactive starter" and does not give the artifact id.

This check did not look up a Resilience4j release newer than 2.3.0. 2.3.0 is the version the 5.0.3 BOM and both starter POMs publish.

### 3. Boot 4.1.1 compatibility is the release announcement; the BOM records Boot 4.0.8

Fetched 2026-10-14:

- [Spring Cloud project page](https://spring.io/projects/spring-cloud), heading Spring Cloud 2025.1.3. The compatibility table maps 2025.1.x (Oakwood) to Boot `4.0.x, 4.1.x (Starting with 2025.1.2)`. The getting-started sample on that same page still shows Cloud `2025.1.2` and Boot plugin `4.1.0`.
- [2025.1.3 release announcement](https://spring.io/blog/2026/08/20/spring-cloud-2025-1-3-has-been-released), 20 August 2026: "This release is based on Spring Boot `4.0.x` and is compatible with the Spring Boot `4.1.x` line as well." The module table lists Gateway 5.0.3 and Circuitbreaker 5.0.3.
- `spring-cloud-dependencies` 2025.1.3 sets `<spring-boot.version>4.0.8</spring-boot.version>`. That POM does not import `spring-boot-dependencies`.
- Maven metadata for `spring-cloud-dependencies` has `<latest>2026.0.0-M1</latest>` and `<release>2026.0.0-M1</release>`, lastUpdated 2026-09-24. The newest non-milestone in the version list is `2025.1.3`. This check treats 2025.1.3 as the current GA. The GitHub Supported Versions wiki still lists Oakwood's Boot line as `4.0.x` only. That wiki disagrees with the project page and the release announcement.

No Gradle resolution was run. This check does not show whether importing the Cloud BOM next to Boot plugin 4.1.1 keeps Boot artifacts on 4.1.1, or whether the starter POM's recorded 4.0.8 is what resolution selects.

### 4. `redis:8` exists and is not pinned the way the other images are

`compose.yaml` has no Redis service. The official Redis image page fetched this check lists `8.10.2`, `8.10`, `8`, `8.10.2-trixie`, `8.10-trixie`, `8-trixie`, `latest`, and `trixie` as one tag group. `redis:8` is a moving major tag. Postgres and Kafka in this repo are patch tags (`postgres:18.6`, `apache/kafka:4.3.1`).

The current Redis Lua docs still describe atomic `EVAL` on Lua 5.1. The Lua API reference fetched the same day lists `redis.call`, `redis.sha1hex`, `cjson`, `struct`, and `bit`. It does not list a UUID function. The spine says the qualification id is a UUID string generated in the Lua script. That generation step is not a Redis primitive on the page that was fetched. No substitute was chosen in this check.

### 5. Spring Kafka 4.1.1 matches the repo; the client and the broker image are different patch lines

`spring-boot-dependencies` 4.1.1 sets `<spring-kafka.version>4.1.1</spring-kafka.version>` and `<kafka.version>4.2.1</kafka.version>`.

`spring-modulith-events-kafka` 2.1.1 is already in `build.gradle`. Its published POM depends on `org.springframework.kafka:spring-kafka:4.1.1` and `org.springframework.boot:spring-boot-kafka:4.1.1`.

The broker image in `compose.yaml` is `apache/kafka:4.3.1`. The spine records Spring Kafka 4.1.1 and that image. It does not record `kafka-clients`. This check did not fetch an Apache statement that client 4.2.1 is a supported pair for broker 4.3.1.

## Decisions that are not version pins

AD-1 through AD-9, the topic names, the five answer strings, and the two deployables were not re-checked as product versions. CDN, the numeric admission rate, and the inventory service internals are deferred and carry no versions.

## What this check did not do

- No Gradle dependency resolution.
- No image pull of `redis:8` or the compose images.
- No claim that Gateway, Circuit Breaker, or Resilience4j have a GA newer than the 2025.1.3 BOM. Milestone train `2026.0.0-M1` is on Maven Central and was not diffed artifact by artifact.
- No Apache compatibility page for Kafka client 4.2.1 against broker 4.3.1.
