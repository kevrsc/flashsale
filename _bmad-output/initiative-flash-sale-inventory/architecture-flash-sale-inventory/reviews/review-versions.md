# Version review — architecture spine

Verdict: **Revise**

The spine was not edited. This review checks whether every committed technology pin was web-researched or reality-checked, rather than asserted from training data: current versions, that each named technology still exists and fits, and the live defaults of any starter the spine leans on.

- Spine: `architecture-flash-sale-inventory/architecture-flash-sale-inventory.md` (final, updated 2026-10-09)
- Research record: `architecture-flash-sale-inventory/.memlog.md` (version lines dated 2026-09-29 and 2026-10-09)
- Checked on 2026-10-09 against OpenTelemetry collector install docs and Docker Hub, Prometheus download and GitHub releases, Grafana OSS download and Docker Hub, Maven Central, Spring Boot 4.1.1 reference and API, Spring Modulith 2.1.1 `KafkaEventExternalizerConfiguration` source, and the Namastack JDBC starter POM
- Project: greenfield for this service. No application build file was used to ratify the new rows

The 2026-10-09 image pins are current. The gaps are fit, not freshness: the log appender pin is the newest alpha and does not match the OpenTelemetry SDK Spring Boot 4.1.1 manages, and the same-trace Kafka rule is a step past what the memlog actually verified.

## 2026-10-09 pins this review confirmed

| Pin | Record | This review |
| --- | --- | --- |
| `otel/opentelemetry-collector:0.162.0` | `(version)` 2026-10-09 — Docker install docs; 0.163 called nightly | Confirmed. The collector Docker install page still pulls `otel/opentelemetry-collector:0.162.0`. GitHub release v0.162.0 is 2026-09-28/29. Docker Hub's newer tags are `0.163.0-nightly.*` (pushed 2026-10-07), not a stable 0.163.0. |
| `prom/prometheus:v3.15.0` and Prometheus 3.15.0 | `(version)` — released 2026-09-24 | Confirmed. prometheus.io/download lists 3.15.0 / 2026-09-24 as the latest release. Docker Hub has `prom/prometheus:v3.15.0`. |
| `grafana/grafana:13.2.3` and Grafana 13.2.3 | Correction line — grafana.com download, 2026-09-29; 13.0.3 retired | Confirmed. Grafana OSS download shows 13.2.3, AGPLv3, release date 2026-09-29, as the selected stable version. Docker Hub has `grafana/grafana:13.2.3`. Enterprise is the separate image `grafana/grafana-enterprise`. |
| `spring-boot-starter-opentelemetry` on Boot 4.1.1 | `(version)` — Boot 4.1 docs | Confirmed. Maven Central has `org.springframework.boot:spring-boot-starter-opentelemetry:4.1.1`. Its POM pulls `micrometer-registry-otlp` 1.17.1, `micrometer-tracing-bridge-otel` 1.7.1, and `opentelemetry-exporter-otlp` 1.62.0. |
| `micrometer-registry-otlp` on the Boot 4.1.1 BOM | Stack row | Confirmed as a runtime dependency of that starter, version 1.17.1, which is the Micrometer version in `spring-boot-dependencies` 4.1.1. Metrics export URL in the Boot 4.1 metrics docs is the full `…:4318/v1/metrics` path. Micrometer's own default, used when Boot's property is unset, is `http://localhost:4318/v1/metrics`. |
| OTLP HTTP paths on port 4318 | AD-10, AD-11 | Confirmed as the paths Boot 4.1 documents: `/v1/metrics`, `/v1/traces`, `/v1/logs`. The tracing exporter's HTTP transport is the default (`transport=http` matches when unset). See finding 3 for what does not turn on by itself. |
| `opentelemetry-logback-appender-1.0` `2.32.0-alpha` exists | `(version)` — Maven Central; no stable line | The artifact exists: `io.opentelemetry.instrumentation:opentelemetry-logback-appender-1.0:2.32.0-alpha`. The 1.x and 2.x lines published for this artifact are alpha. See finding 2 for fit with Boot. |
| `OpenTelemetryAppender.install(OpenTelemetry)` | AD-11 | Confirmed. Boot 4.1 loggers docs show that call, on `io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender`, against the Boot `OpenTelemetry` bean. The appender is not part of Spring Boot. |
| MDC keys `traceId` and `spanId` | AD-11 | Confirmed. Boot 4.1 tracing docs: Micrometer Tracing adds MDC `traceId` and `spanId`. Printing them under the labels `trace_id` and `span_id` is this spine's console pattern. |
| `spring.kafka.template.observation-enabled` | AD-5 | Confirmed on the Boot 4.1.1 API. `KafkaProperties.Template` has `setObservationEnabled`. That nested class binds under `spring.kafka.template`. Boot 4.1 `management.tracing.propagation.produce` defaults to `[W3C]`, so an observation that has a current span writes `traceparent` (and `tracestate` when the propagator has state). |
| Modulith sends through `KafkaOperations` | `(version)` and AD-5 | Confirmed in Modulith 2.1.1 source. `KafkaOutboxConfiguration` is `@ConditionalOnProperty(… mode=outbox)` and takes `KafkaOperations`. `createKafkaTransport` calls `operations.send(message)`. `KafkaTemplate` is that bean when Boot auto-configures one. |

Collector pieces named in AD-11 also exist on the core image, which is the image the spine pins. The Prometheus exporter and the debug exporter are both in the core distribution. The official collector configuration example sets the Prometheus exporter `endpoint` to `0.0.0.0:8889`. That key has no default, so 8889 happens because the Compose config sets it, which is what AD-11 says.

## Existing stack rows were not invented on 2026-10-09

The rows from Java through `apache/kafka:4.3.1` are the same pins the 2026-09-29 memlog and the earlier version review already checked (Boot 4.1.1, Modulith 2.1.1, Namastack JDBC 1.7.3, events-kafka and spring-kafka 4.1.1, `spring-boot-kafka`, webmvc, jdbc, thymeleaf, flyway, `flyway-database-postgresql` 12.4.0, the PostgreSQL driver from the Boot BOM, `postgres:18.6`, `apache/kafka:4.3.1`). The 2026-10-09 edit adds the collector, Prometheus, Grafana, the OpenTelemetry starter, `micrometer-registry-otlp`, and the logback appender. It does not replace those earlier rows.

Spot-checks of the older pins that could have moved by today:

- Spring Boot 4.1.1 is still the current stable release (announced 2026-08-20). The 4.1.2 milestone is due 2026-10-22 and is not released. The parent coordinate `4.1.1`, and the note that Initializr's token `4.1.1.RELEASE` is not a Maven Central coordinate, still match the 2026-09-29 check.
- PostgreSQL 18.6 is still the current 18 release (2026-08-13). Docker Hub still serves `postgres:18.6`.
- Apache Kafka's downloads page still lists 4.3.1 as the stable release, with image `apache/kafka:4.3.1`. Docker Hub's 4.4.0 tags are still release candidates (`4.4.0-rc4`).

Java 17, Flyway 12.4.0, and the Modulith and Namastack coordinates were not fetched again today. They are unchanged rows from the 2026-09-29 check, and Boot 4.1.1 has not been superseded, so the BOM-managed versions from that check still belong to this Boot release.

## Findings

### 1. High — The same-trace Kafka rule was not established by the mechanism the memlog checked

AD-5 says `spring.kafka.template.observation-enabled=true` makes the single `KafkaTemplate` write `traceparent`, and that the after-commit send stays in the order request's trace. The memlog's version line supports the first half: Spring Kafka writes `traceparent` when template observation is enabled, and Modulith 2.1 sends through the `KafkaOperations` bean. It does not record a check that the outbox worker still has that request's trace.

Modulith 2.1.1's outbox path registers a Namastack `OutboxHandler` whose body is `(payload, metadata) -> externalizer.externalizeBlocking(payload)`. The handler drops `metadata`. `createKafkaTransport` then calls `operations.send(message)` on whatever thread invokes the handler. Template observation injects `traceparent` from the trace context on that sending thread. Namastack's JDBC starter 1.7.3 POM depends on `namastack-outbox-api`, `namastack-outbox-core`, `namastack-outbox-jdbc`, `namastack-outbox-jackson`, and `kotlin-stdlib`. It does not depend on a tracing module. Namastack's docs put cross-thread trace propagation in `namastack-outbox-observability` (the older `namastack-outbox-tracing` module is deprecated). `io.namastack:namastack-outbox-observability:1.7.3` is on Maven Central and imports Spring Boot 4.1.1. It is not in the stack table.

A worker thread with an empty context still gets a `traceparent`. That header belongs to a new trace. AD-5 forbids that outcome. This review did not read the observability module's source, so adding the dependency is the documented candidate, and it still needs a check that it restores the request context around `KafkaTemplate.send`.

Disposition: **discuss**. Keep observation-enabled as the header writer. Decide whether `namastack-outbox-observability` 1.7.3 is in this build, and only then keep the sentence that the after-commit send stays in the request trace.

### 2. High — The logback appender pin is current and does not match Boot's OpenTelemetry SDK

`opentelemetry-logback-appender-1.0:2.32.0-alpha` is the newest version on Maven Central, and its POM depends on `opentelemetry-api` 1.66.0. The instrumentation 2.32.0 release notes say that release targets OpenTelemetry SDK 1.66.0. There is still no stable, non-alpha line of this artifact.

Spring Boot 4.1.1 manages OpenTelemetry at 1.62.0. The platform catalog sets the OpenTelemetry library to 1.62.0, and the OpenTelemetry starter POM pins `opentelemetry-exporter-otlp` 1.62.0. Instrumentation 2.28.0 is the release whose notes say it targets SDK 1.62.0, which is the line that matches this Boot BOM. The memlog records a Maven Central check for "the current line" and Boot's logging docs. It does not record a comparison with Boot's managed SDK.

Boot's dependency management will pull `io.opentelemetry` artifacts toward 1.62.0 while this appender was built for API 1.66.0. That is the combination AD-11 tells the host process to run.

Disposition: **discuss**. Pin the appender release that targets SDK 1.62.0 (2.28.0-alpha is the release that states that target; confirm whether a later 2.28.x patch is the last one that still targets 1.62.0). Keeping 2.32.0-alpha means overriding Boot's OpenTelemetry BOM on purpose.

### 3. Medium — Trace and log URLs are real, and they are off until two properties are set

AD-11 says Micrometer exports metrics to `http://localhost:4318/v1/metrics` and traces to `http://localhost:4318/v1/traces`, and that the logback appender exports to `http://localhost:4318/v1/logs` after `OpenTelemetryAppender.install`.

The metrics sentence matches Boot. `micrometer-registry-otlp` exports metrics, and its default URL is `http://localhost:4318/v1/metrics`.

Traces are a different exporter. Boot 4.1.1 `OtlpTracingConfigurations` builds `OtlpHttpSpanExporter` only when `management.opentelemetry.tracing.export.otlp.endpoint` is set (`@ConditionalOnProperty` on that key). The property's endpoint is nullable. The starter brings `opentelemetry-exporter-otlp` for this path; `micrometer-registry-otlp` does not export spans.

Logs match the Boot 4.1 loggers page on the install call and the appender class. That page also says logging via OpenTelemetry is not configured until `management.opentelemetry.logging.export.otlp.endpoint` is set, and the example value is `https://…:4318/v1/logs`. The appender forwards records into the OpenTelemetry API. The URL belongs to that Boot exporter property.

The 2026-10-09 memlog names both properties. AD-11 states the URLs and the install call, and leaves the properties out, so a builder who follows the rule gets metrics at `/v1/metrics` and no OTLP traces or logs.

Disposition: **autofix**. In AD-11, set `management.opentelemetry.tracing.export.otlp.endpoint` to `http://localhost:4318/v1/traces` and `management.opentelemetry.logging.export.otlp.endpoint` to `http://localhost:4318/v1/logs`. Say Micrometer OTLP exports metrics, and the OpenTelemetry span exporter exports traces.

## Not findings

- The collector, Prometheus, and Grafana image tags above are the current stables as of this check. Grafana 13.0.3 remains only in the memlog's first version line; the correction and the spine both say 13.2.3.
- Port 8889 is the port in the current collector configuration example. The Prometheus exporter's `endpoint` has no default. AD-11 already assigns that listen port to the collector config.
- The core collector image includes the OTLP receiver, the Prometheus exporter, and the debug exporter. AD-11 does not need the contrib image for those three.
- `spring-boot-starter-opentelemetry` already depends on `micrometer-registry-otlp`. Listing both on the Boot 4.1.1 BOM is accurate.
- Pre-2026-10-09 stack rows were carried forward. They were not replaced with newly asserted coordinates in this update.
