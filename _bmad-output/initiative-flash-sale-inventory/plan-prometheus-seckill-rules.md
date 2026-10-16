---
title: 'Prometheus seckill.rules'
type: 'feature'
ticket: ''
created: '2026-10-15'
status: 'built'
baseline_revision: 'a93161f285856f61124202f514a720b93c86e8ef'
route: 'full'
route_source: 'auto'
review: 'quick'
review_source: 'pinned'
lenses_ran: ['quick']
review_loop_iteration: 0
context: []
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Prometheus scrapes the sale and gateway metrics but has no alert rules, so a backed-up order queue or a gateway that stops answering from the Redis hash goes unnoticed.

**Approach:** Add a Prometheus rule group `seckill.rules` with two alerts, adapted from the Medium snippet to this sale's own metrics. Load the group in the compose Prometheus. Prove both alerts with `promtool` rule tests and a live check against the running stack.

## Boundaries & Constraints

**Always:**
- **HighQueueDepth:** fires when consumer lag on topic `sale.qualification` stays above 50000 for 30s. Severity `critical`.
- **RedisHitRateLow:** looks only at attempts answered `sold_out` or `closed`. It fires when the gateway's share of those answers, out of gateway plus sale, stays below 0.99 for 60s. Severity `warning`.
- Gateway answers come from the hash without `EVAL`. Sale answers mean the attempt was forwarded and ran the script.
- Neither alert fires on a healthy 100,000-attempt burst.
- Both apps push metrics often enough for those windows to mean something.

**Never:**
- Do not alert on Redis INFO keyspace hit rate.
- Do not use the Medium series `kafka_consumer_lag_sum{topic="seckill_events"}`.
- Do not add Alertmanager or notification routing; alerts are read on Prometheus `/alerts`.
- Do not change the attempt path, `sale.lua`, or the answer order.
- Do not add a new meter where an existing one already gives the signal.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Queue backs up | Lag on `sale.qualification` 60000 for 2m | HighQueueDepth firing, `severity="critical"` | No error expected |
| Queue short spike or other topic | Lag 60000 for 20s, or 60000 on another topic | HighQueueDepth not firing | No error expected |
| Edge misses after sell-out | 98 gateway and 2 sale `sold_out`/`closed` answers per second for 2m | RedisHitRateLow firing, `severity="warning"` | No error expected |
| Edge holds | 995 gateway and 5 sale per second, or only `qualified` answers, or no traffic | RedisHitRateLow not firing | Zero traffic yields no value, not an alert |
| Edge forwards everything | 0 gateway and 10 sale `sold_out` answers per second for 2m | RedisHitRateLow firing | A missing gateway series counts as 0 |

</frozen-after-approval>

## Code Map

- `prometheus.yml` -- scrapes only `otel-collector:8889`; add `rule_files`
- `compose.yaml` -- `prometheus` service mounts `./prometheus.yml` only; mount the rules file beside it
- `otel-collector-config.yaml` -- the `prometheus` exporter turns OTLP into scrape series; confirmed live: resource `service.name` arrives as label `exported_job` (`sale`, `gateway`); `job` is always `otel-collector` because the scrape job's own label wins. Match on `exported_job`; do not add `honor_labels` or change the collector
- `gateway/src/main/java/com/flashsale/gateway/AttemptEdge.java:69` -- counter `sale.qualification{answer}` for edge answers (hash hits)
- `sale/src/main/java/com/flashsale/qualify/AttemptApi.java:38` -- the same counter for script answers (forwarded attempts)
- `grafana/dashboards/sale.json` -- already queries `sale_qualification_total{answer=...}`, which confirms the counter's Prometheus name
- Boot 4.1.1 `KafkaMetricsAutoConfiguration` (spring-boot-kafka) -- binds consumer metrics; confirmed live: gauge `kafka_consumer_fetch_manager_records_lag{exported_job="sale",client_id,partition,topic}`. Kafka reports each partition twice, once as `topic="sale.qualification"` and once with the legacy dot-to-underscore name `topic="sale_qualification"`; match `topic="sale.qualification"` exactly so lag is not double-counted, and sum over partitions
- `sale/src/main/resources/application.properties`, `gateway/src/main/resources/application.properties` -- OTLP metrics export currently pushes every 1m (gateway startup log)
- `_bmad-output/initiative-flash-sale-inventory/deferred-work.md` -- remove the Prometheus entry once this passes

## Tasks & Acceptance

**Execution:**
- [x] `sale/src/main/resources/application.properties`, `gateway/src/main/resources/application.properties` -- set `management.otlp.metrics.export.step=10s` -- a 1m push cannot satisfy a 30s `for`
- [x] `prometheus/seckill.rules.yml` -- group `seckill.rules` with HighQueueDepth and RedisHitRateLow, using the series names confirmed live; RedisHitRateLow treats each missing side as 0 -- the alerts themselves
- [x] `prometheus/seckill.rules.test.yml` -- `promtool test rules` cases, one or more per matrix row -- proves when each alert fires and when it stays silent
- [x] `prometheus.yml`, `compose.yaml` -- load `/etc/prometheus/seckill.rules.yml` -- Prometheus evaluates the group
- [x] `build.gradle` -- task `prometheusRules` runs `promtool check rules` and `promtool test rules` in `prom/prometheus:v3.15.0` through Docker; `check` depends on it -- keeps the rules from drifting
- [x] `README.md`, `docs/devops/README.md` -- name both alerts, their thresholds, and where to see them -- operators can read what fired
- [x] `_bmad-output/initiative-flash-sale-inventory/deferred-work.md` -- delete the Prometheus entry

**Acceptance Criteria:**
- Given the stack is up and a clean sale, when `./gradlew jmeterBurst` passes, then Prometheus lists group `seckill.rules` with both alerts loaded and neither firing, and both alerts' source series exist with the labels the rules match.
- Given `./gradlew check`, when it runs, then `prometheusRules` runs and fails if a rule test fails.

## Design Notes

RedisHitRateLow avoids needing a sale-state gauge. `sold_out` and `closed` answers can only happen while the sale is sold out or closed. So among those answers, the gateway share is the hash hit rate for that state.

```promql
(sum(rate(sale_qualification_total{exported_job="gateway",answer=~"sold_out|closed"}[1m])) or vector(0))
/
((sum(rate(sale_qualification_total{exported_job="gateway",answer=~"sold_out|closed"}[1m])) or vector(0))
 + (sum(rate(sale_qualification_total{exported_job="sale",answer=~"sold_out|closed"}[1m])) or vector(0)))
< 0.99
```

When there is no traffic, this divides 0 by 0. The result is NaN, and NaN never satisfies `< 0.99`.

Live baseline after a clean 100,000-attempt burst (1m push, before the 10s change): gateway `sold_out` 99892, sale `sold_out` 8, sale `qualified` 100, no `closed`. The gateway share is about 0.9999, so RedisHitRateLow stays silent on a healthy burst. Lag on `sale.qualification` read 0 after the burst.

## Verification

**Commands:**
- `./gradlew prometheusRules` -- expected: `promtool` reports SUCCESS for the rule check and every test
- `./gradlew jmeterReset -PconfirmReset=local` then `./gradlew jmeterBurst` -- expected: 100 qualified, 100 orders
- `curl -s localhost:9090/api/v1/rules` -- expected: group `seckill.rules`, both alerts `inactive`

**Manual checks (if no CLI):**
- `localhost:9090/alerts` shows both alerts as inactive after the burst

## Review Triage Log

| Finding | Verdict | Route | Evidence |
|---|---|---|---|
| `README.md` `## Diagrams` section is unrequested scope | false | rejected | The section was an uncommitted working-tree edit before this build started (`git status` at session start showed `README.md` modified); this change only added `## Alerts`. Kept out of this build's commit. |
| `RedisHitRateLow` false positive from in-flight `sold_out` answers after sell-out at moderate traffic | maybe-false | rejected | A one-off sale-side batch stays inside `rate(...[1m])` for under 60s, so it clears before `for: 60s`; the patched promtool test with a 30s sale-side miss goes pending and clears without firing. Live burst low was 0.994. If true it is only `low` (a `warning` with no notification routing); settling it needs a live low-traffic sale. |
| Test `edge miss shorter than 60s stays silent` never models a miss that ends | low | patch | It reused the sustained 98/2 series and evaluated at 50s. Patched: sale counter goes flat after 30s, silence asserted at 70s and 2m. |
| First acceptance criterion lacks evidence that source series exist | false | rejected | Live after the 10s-push burst: `sale_qualification_total` by `exported_job,answer` gave gateway `sold_out` 99879, sale `sold_out` 21, sale `qualified` 100; `kafka_consumer_fetch_manager_records_lag{exported_job="sale",topic="sale.qualification"}` present at 0. |
| Rules file edits need a Prometheus restart, undocumented | low | patch | Single-file bind mount, no `--web.enable-lifecycle`. Patched: `docs/devops/README.md` says to run `docker compose restart prometheus`. |
