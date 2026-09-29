# Version review — architecture spine

Verdict: **Revise**

The spine was not edited. This review checks whether every committed technology pin was web-researched or reality-checked, rather than asserted from training data: current versions, that each named technology still exists and fits, and the live defaults of Spring Initializr, the starter the memlog leans on.

- Spine: `architecture-flash-sale-inventory/architecture-flash-sale-inventory.md` (draft, altitude initiative)
- Research record: `architecture-flash-sale-inventory/.memlog.md` (2026-09-29)
- Checked on 2026-09-29 against Maven Central, the Modulith 2.1.1 release notes, Spring Modulith reference docs, and live `start.spring.io` metadata plus a generated Maven build
- Project: greenfield. No `pom.xml`, Gradle build, or `compose.yaml` exists to ratify

The versions written in the stack table are current. The memlog's `(version)` lines cover Boot, Modulith, Java, and PostgreSQL, and a same-day decision covers Kafka. What was not checked is what Initializr actually emits. Those live defaults disagree with the stack in two places that will fail or skip the outbox if a builder treats "generate from Initializr" as the pin.

## What the research record already supports

| Pin | Record | This review |
| --- | --- | --- |
| Java 17 | `(version)` — default on start.spring.io; Boot 4.1.1 requires 17+ | Confirmed. Metadata `javaVersion.default` is `17`. Boot parent `4.1.1` sets `java.version` to 17. |
| Spring Boot 4.1.1 | `(version)` — stable on spring.io | Confirmed as the Maven Central coordinate (`spring-boot-starter-parent` `4.1.1` returns HTTP 200). Initializr's only stable Boot release in the list is `4.1.1.RELEASE`; `4.1.2` is a snapshot. See finding 1 for the suffix. |
| Spring Modulith 2.1.1 | `(version)` — stable on spring.io; notes upgrade to Boot 4.1.1 | Confirmed. Release `2.1.1` (2026-08-26) upgrades to Boot 4.1.1 and Namastack Outbox 1.7.3. Initializr BOM `spring-modulith` is `2.1.1`. |
| PostgreSQL 18.6 and `postgres:18.6` | `(version)` — postgresql.org and Docker Hub tag | Accepted from the memlog. Not re-fetched. |
| Apache Kafka 4.3.1 and `apache/kafka:4.3.1` | Decision, not a `(version)` line — kafka.apache.org; 4.4.0 tags called release candidates | Accepted from the memlog. Not re-fetched. |
| Externalization mode `outbox` | Decision — Modulith docs; default listener publish is not this mode | Confirmed. Docs set `spring.modulith.events.externalization.mode` to `outbox` and say that requires the Namastack starter. The listener path remains the default. |

## Confirmed even though the memlog did not cite a source

These were the unsupported stack rows. They are not stale.

- `org.springframework.modulith:spring-modulith-starter-namastack:2.1.1` exists. Its POM depends on `io.namastack:namastack-outbox-starter-jdbc:1.7.3` at runtime. That matches the stack. Namastack `1.8.0` is also on Maven Central, and Modulith main has a later upgrade to 1.8.0. `1.7.3` is the correct pin for Modulith 2.1.1. "Latest Namastack" is not.
- `org.springframework.modulith:spring-modulith-events-kafka:2.1.1` exists and depends on `org.springframework.kafka:spring-kafka:4.1.1`. The "brings spring-kafka 4.1.1" claim holds. The same POM also depends on `org.springframework.boot:spring-boot-kafka:4.1.1`, which the stack table does not name. That module is Boot 4's Kafka auto-config, not a second starter. No schema-registry client is in that POM.
- `org.flywaydb:flyway-database-postgresql` is managed by the Spring Boot 4.1.1 BOM (Flyway `12.4.0` in the `v4.1.1` platform catalog). Initializr's `flyway` dependency emits `spring-boot-starter-flyway` plus this module with no explicit version. The stack's "Spring Boot 4.1.1 BOM" pin matches that. `spring-boot-starter-thymeleaf` and `spring-boot-starter-flyway` at `4.1.1` match the Boot starter line.
- Namastack's JDBC starter is documented to create its own schema. Its 1.7.3 POM pulls the JDBC outbox modules, not Flyway and not Hibernate. AD-9's split (Flyway for item and customer, Namastack for outbox tables, no Hibernate DDL) fits this artifact.

## Findings

### 1. High — Initializr writes a Boot parent version that does not resolve

The stack and the memlog pin Spring Boot `4.1.1`. Live Initializr metadata uses the id `4.1.1.RELEASE`, and a generated Maven build for that id contains:

```xml
<artifactId>spring-boot-starter-parent</artifactId>
<version>4.1.1.RELEASE</version>
```

On Maven Central, `spring-boot-starter-parent` `4.1.1` returns HTTP 200. `4.1.1.RELEASE` returns HTTP 404. The memlog records a spring.io check for `4.1.1` and does not record a check of the generated build file.

A builder who copies the spine resolves. A builder who takes the Initializr archive the memlog calls the starter does not, until the parent version is rewritten to `4.1.1`.

Disposition: **discuss**. Keep the stack row as `4.1.1`. Record that the generator's version token is `4.1.1.RELEASE` and must not be copied into the build.

### 2. High — The Modulith checkbox is not the outbox starter

The memlog says the starter is Spring Initializr on Boot 4.1.1 with Modulith 2.1.1, and that externalization mode is `outbox`.

Live Initializr, Boot `4.1.1.RELEASE`, maps the `modulith` dependency to `org.springframework.modulith:spring-modulith-starter-core` on the Modulith BOM `2.1.1`. A generated build with `modulith` selected contains `spring-modulith-starter-core`, `spring-modulith-events-api`, and (when Kafka is also selected) runtime `spring-modulith-events-kafka` and `spring-modulith-runtime`. It does not contain `spring-modulith-starter-namastack`.

Modulith's own docs: outbox mode is off until that Namastack starter is added and `spring.modulith.events.externalization.mode` is `outbox`. The default remains the listener publisher. The stack table does name `spring-modulith-starter-namastack` `2.1.1`, and that artifact is real (finding cleared above). The research record never compared it to the checkbox.

Disposition: **discuss**. State in the stack or the memlog that Initializr's Modulith dependency is `spring-modulith-starter-core`, and that `spring-modulith-starter-namastack` is an extra dependency the generator does not add.

### 3. Medium — The live project-type default is Gradle, and nothing binds it

`start.spring.io` metadata `type.default` is `gradle-project` (Gradle Groovy). Packaging default is `jar`. Language default is `java`. No dependency is selected by default.

The memlog records only the Java 17 default. The spine names no build tool. Two builders can leave Initializr with a Gradle build and a Maven build and still match every version row.

Older Initializr docs still say the service defaults to Maven. That sentence is stale relative to the metadata fetched today.

Disposition: **discuss**. Pin Gradle or Maven. If the pin is Maven, say it overrides the live default.

### 4. Medium — Initializr's Kafka dependency is a different artifact than the one pinned

The stack pins `spring-kafka` `4.1.1`, brought by `spring-modulith-events-kafka` `2.1.1`. AD-5 says there is no second Kafka producer.

Initializr's `kafka` id is `org.springframework.boot:spring-boot-starter-kafka`. Selecting it, which is the natural way to add Kafka on the generator, adds that starter beside Modulith's events module. The memlog's "brings spring-kafka 4.1.1" line is true and does not mention this checkbox.

`spring-boot-kafka` (the Boot 4 module on the events-kafka POM) and `spring-boot-starter-kafka` (the Initializr checkbox) are different artifacts. Only the second one is the extra producer path.

Disposition: **discuss**. Name `spring-boot-starter-kafka` as not selected, and name `spring-boot-kafka` `4.1.1` as the Boot module the events artifact already brings.

### 5. Medium — Boot 4 starter coordinates the page and the JDBC path need are unnamed

The spine says the app uses JDBC and serves a Thymeleaf form. It does not name the starters a builder must add. Initializr's live map for Boot 4.1.1:

| Need | Initializr id | Artifact |
| --- | --- | --- |
| JDBC | `jdbc` | `spring-boot-starter-jdbc` |
| Spring Data JDBC | `data-jdbc` | a different starter |
| PostgreSQL | `postgresql` | `org.postgresql:postgresql` (runtime, version from the Boot BOM) |
| Web | `web` | `spring-boot-starter-webmvc` |

`spring-boot-starter-web` `4.1.1` still resolves, and its POM description says it is deprecated in favor of `spring-boot-starter-webmvc`. Namastack's JDBC starter does not pull `spring-boot-starter-jdbc` or the PostgreSQL driver. Those have to be declared. The memlog never names them, so a builder can pick Spring Data JDBC, the deprecated web starter, or the R2DBC-shaped reading of the PostgreSQL checkbox description (the generated dependency is the JDBC driver only).

Disposition: **discuss**. Add stack rows for `spring-boot-starter-jdbc` `4.1.1`, `spring-boot-starter-webmvc` `4.1.1`, and `org.postgresql:postgresql` managed by the Boot 4.1.1 BOM.

## Not findings

- Docker Compose in AD-10 is a hand-written `compose.yaml`, not Initializr's `docker-compose` dependency (`spring-boot-docker-compose`). That checkbox is not a default. Leaving it off matches AD-10's localhost topology.
- Group ids were missing from the spine but are unambiguous for the artifacts checked: `org.springframework.modulith`, `io.namastack`, `org.springframework.kafka`, `org.springframework.boot`, `org.flywaydb`, `org.postgresql`.
- Image tags for Postgres and Kafka stay on the memlog's same-day citations. This review did not open Docker Hub or kafka.apache.org again.
