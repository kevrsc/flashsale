# Version review — architecture spine

Verdict: **Pass**

This review checks the 2026-10-12 sale-count amendment: AD-8, AD-11, and the Stack table. The amendment adds no library. It records two Micrometer counters on the registry the host process already exports, and it leaves the OTLP metrics export step at the Spring Boot default.

- Spine: `architecture-flash-sale-inventory/architecture-flash-sale-inventory.md` (AD-8, AD-11, Stack; memlog updated 2026-10-12)
- Research record: `.memlog.md` lines 49–50 are `(decision)` lines. They do not cite a `(version)` check for Counter tags or the export step. This review supplied that check.
- Checked on 2026-10-12 against the repo (`build.gradle`, `src/main/resources/application.properties`), the Gradle-cached `spring-boot-starter-opentelemetry-4.1.1` POM, `micrometer-core` 1.17.1 `Counter`, `micrometer-registry-otlp` 1.17.1 `OtlpMetricConverter`, `spring-boot-micrometer-metrics` 4.1.1 `PushRegistryProperties` and `OtlpMetricsProperties`, the Boot 4.1.1 `OtlpMetricsProperties` API page, and the Boot 4.1 common application properties appendix
- Image pins were not re-fetched. Collector `0.162.0`, Prometheus `v3.15.0`, Grafana `13.2.3`, and appender `2.26.0-alpha` are the same names as before this edit

## Repo

| Claim | Where | This review |
| --- | --- | --- |
| `spring-boot-starter-opentelemetry` on Boot 4.1.1 | AD-11, Stack | Present. `build.gradle` uses the Spring Boot plugin `4.1.1` and `implementation 'org.springframework.boot:spring-boot-starter-opentelemetry'`. |
| `micrometer-registry-otlp` on the Boot 4.1.1 BOM | AD-11, Stack | Present as a dependency of that starter, not as a second direct line. The resolved starter POM `4.1.1` depends on `io.micrometer:micrometer-registry-otlp:1.17.1` at runtime, and on `spring-boot-starter-micrometer-metrics:4.1.1`. |
| Metrics URL | AD-11 | Present. `application.properties` sets `management.otlp.metrics.export.url=http://localhost:4318/v1/metrics`. |
| Export step left unset | AD-11 | Present as an absence. `application.properties` does not set `management.otlp.metrics.export.step`. The repo has no `OTEL_METRIC_EXPORT_INTERVAL`. |

## APIs this amendment relies on

| Claim | This review |
| --- | --- |
| A Micrometer counter carries one dimension | Confirmed on `micrometer-core` 1.17.1, the Micrometer version in the starter POM. `Counter.Builder` has `tag(String key, String value)` and `tags(...)`. The 1.17.1 counters doc registers with `.tags("region", "test")`. There is no `attribute` method on the builder. |
| That dimension is an attribute on the OTLP export | Confirmed in `micrometer-registry-otlp` 1.17.1 `OtlpMetricConverter`. `getKeyValuesForId` reads `id.getConventionTags(...)` and `createKeyValue` writes each tag as an OTLP `KeyValue`. Data points call `addAllAttributes` with those keys. A counter built with `.tag("result", "in_process")` exports attribute `result=in_process`. The same mapping holds for `kind`. AD-8's word "attribute" is the exported key. The Java call that produces it is `tag`. |
| The OTLP metrics export step stays at the Spring Boot default | Confirmed on Boot 4.1.1. `OtlpMetricsProperties` is `@ConfigurationProperties("management.otlp.metrics.export")` and extends `StepRegistryProperties`. `PushRegistryProperties` initializes `step` to `Duration.ofMinutes(1)`. The Boot 4.1 properties appendix lists `management.otlp.metrics.export.step` with default `1m`. Leaving the property unset is that default. |

## Findings

None.

## Not findings

- The Stack table gains no row in this edit. `spring-boot-starter-opentelemetry` and `micrometer-registry-otlp` were already on the Boot 4.1.1 BOM. The starter POM still pulls registry `1.17.1`.
- Collector `otel/opentelemetry-collector:0.162.0`, `prom/prometheus:v3.15.0`, `grafana/grafana:13.2.3`, and `opentelemetry-logback-appender-1.0` `2.26.0-alpha` keep the names from the previous pin. This review did not re-check those registries.
- `flashsale.sale-totals.interval-ms=60000` in `application.properties` is a different key from `management.otlp.metrics.export.step`. It does not set the OTLP export step.
- Older stack rows (Java 17 through Kafka `4.3.1`, Modulith, Namastack, Flyway) were not part of this amendment and were not re-fetched.
