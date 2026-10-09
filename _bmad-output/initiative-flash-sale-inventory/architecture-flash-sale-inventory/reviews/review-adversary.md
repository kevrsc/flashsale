# Adversarial review — architecture spine

Spine reviewed, not edited: `architecture-flash-sale-inventory.md` (updated 2026-10-09).

Method: for each candidate hole, build two units one level down that each obey every current AD and Deferred entry to the letter, and show they still cannot be dropped into one system. Pairs are the page module and the inventory module, the inventory module and the order service, or the host process and the Compose services where an AD already splits that ownership. A Deferred line that still lets the pair diverge is a hole. A Deferred line that only one unit can see is not.

The 2026-10-09 text closes the earlier mutation, schema, event-body, and host-port holes (missed conditional update rolls back; customer-then-item lock covers reads; unique user id; exact four-field JSON; page does not embed an item id; shopper outcomes are the five names; one Boot process; localhost ports, database, user, and password). The holes below are the ones that text still allows.

**Verdict: fail.** An inventory module can follow every AD and still hand the page a different first-order outcome, or a customer total the limit of 3 does not survive. The same module can follow AD-5's allowed Kafka path and still put a new trace on `inventory.order-accepted` while the console shows the request trace. A Compose file and a Grafana/Prometheus config can each follow AD-10 and AD-11 and still point at different hosts and different metrics.

## Findings

### 1. The new customer row and the success write do not define one limit

- **Severity:** critical
- **Disposition:** autofix
- **Pair:** two inventory builds; the page renders the outcome and the order service fulfills `unitsDeducted`
- **Location:** AD-3, AD-6, AD-9
- **trigger_condition:** AD-6 says the row stores units successfully ordered and the earliest next-allowed time, and that an insert happens when no row exists. It does not say what that insert stores. AD-3 reads those columns under the customer lock and treats "units left" as given. AD-6 says a successful order "writes the units" and does not say it adds `unitsDeducted` to the stored total.
- **Construction:** Flyway A creates `units integer not null default 0` and `next_allowed timestamptz null`. The insert names only the user id. The first read sees zero units and a next-allowed time that is not in the future, so the order deducts. On success the SQL adds `unitsDeducted` and sets next-allowed to PostgreSQL's clock plus one hour. Flyway B uses the same unique user-id key and `next_allowed timestamptz not null default now() + interval '1 hour'`, because the row "stores the earliest time they may order again" from the moment it exists. The insert still names only the user id. AD-3 then sees a next-allowed time in the future, returns wait one hour, changes no pool, and writes no outbox row. The page shows wait. A third build stores the success by assigning `units = unitsDeducted`. After an order of 1, the locked row still reads as 1 on every later order, units left stay at 2, and the customer takes one unit per hour without bound. Each build uses JDBC, the unique key, the customer-then-item lock, and a conditional pool update.
- **guard_snippet:** Tighten AD-6 and AD-3. The insert stores zero successful units and a null next-allowed time. Null is not in the future. Units left are `3` minus the locked successful-units value. On success, add `unitsDeducted` to that value and set next-allowed to PostgreSQL's clock plus one hour. Wait, refusal, sold out, and not accepted do not change either column.
- **potential_consequence:** A first-time shopper is told to wait an hour, and no order event is sent, while the seeded pool is untouched. Or one user id stays under the stored limit of 3 and keeps deducting, and the order service fulfills every event.

### 2. The allowed Kafka send starts a new trace

- **Severity:** high
- **Disposition:** discuss
- **Pair:** inventory module and the order service; the console log is the other reader of the same order
- **Location:** AD-1, AD-5, AD-11, stack table
- **trigger_condition:** AD-5 requires a W3C `traceparent` text header written only by `KafkaTemplate` observation (`spring.kafka.template.observation-enabled=true`), forbids a second producer, a producer interceptor, and `EventExternalizationConfiguration.headers`, and requires the send to stay in the order-request trace. Modulith 2.1.1's Namastack handler is `(payload, metadata) -> externalizer.externalizeBlocking(payload)`. It drops `metadata` and calls `KafkaTemplate` on the outbox worker. `namastack-outbox-starter-jdbc` 1.7.3 does not bring a tracing module. Observation on a thread with no parent writes a new root `traceparent`.
- **Construction:** Inventory A uses the stack table, externalization mode `outbox`, and observation enabled. It does not add an interceptor or `headers()`. The worker's `traceparent` is a new trace. The console line for that order prints the request's Micrometer `traceId`. Inventory B adds `namastack-outbox-observability` so the worker restores the captured context before `KafkaTemplate.send`, and still lets the template write the header. The order service continues whichever `traceparent` arrives. The two records do not join the same console trace. B is the only one that meets the same-trace sentence, and it uses a library the spine never binds. A meets every named mechanism and fails the same-trace sentence.
- **guard_snippet:** Decide one propagation path and bind it. Keep the template as the only header writer. Either add `namastack-outbox-observability` 1.7.3 and require that it restore the request context on the handler thread before `KafkaOperations.send`, or name a single other restore that is not a second producer. Drop the same-trace sentence until that path is checked against `externalizeBlocking`. A new root remains forbidden once a path is chosen.
- **potential_consequence:** The collector shows the order on a trace that is not the shopper's request. The order service continues that other trace. AD-11's debug view and the console `trace_id` do not name the same order.

### 3. Grafana and Prometheus have no legal address

- **Severity:** high
- **Disposition:** autofix
- **Pair:** Compose services (collector, Prometheus, Grafana); the host Boot process is a third client of the published ports
- **Location:** AD-10, AD-11, Structural Seed
- **trigger_condition:** AD-10 gives host addresses (`localhost:4318`, `localhost:9090`, `localhost:3000`) and says Compose services call each other by service name. It never assigns those names. AD-11 says Grafana's datasource is Prometheus and Prometheus scrapes the collector on port 8889, with no URL.
- **Construction:** Compose file A names the services `otel-collector`, `prometheus`, and `grafana`, publishes the host ports in AD-10, and sets the Grafana datasource to `http://prometheus:9090` and the scrape target to `otel-collector:8889`. Compose file B names them `collector`, `prom`, and `grafana` and uses `http://prom:9090` and `collector:8889`. Both use service names between containers and leave the Boot process on localhost. A third config sets the datasource to `http://localhost:9090` because that is the only Prometheus address written in AD-10. Inside the Grafana container that host is Grafana. None of the three can be rejected for using a name the spine forbade, because the spine never chose one.
- **guard_snippet:** Pin the service names and the in-network URLs in AD-10. Grafana's datasource is `http://prometheus:9090`. Prometheus scrapes `otel-collector:8889`. The Boot process keeps the localhost URLs. `localhost` inside a Compose container is that container.
- **potential_consequence:** The app exports OTLP to the host port and Grafana stays empty, or Prometheus scrapes itself. Two Compose files that both match the image tags never form one telemetry path.

### 4. Port 8889 and the debug exporter do not identify the order

- **Severity:** high
- **Disposition:** autofix
- **Pair:** collector config and Prometheus/Grafana; a person reading the collector log is the trace consumer AD-11 names
- **Location:** AD-11, Deferred "Tempo, Loki, and a hosted log aggregator"
- **trigger_condition:** AD-11 says the collector exposes metrics on 8889 for Prometheus to scrape, and writes traces and logs to its debug exporter so a person can read the order's trace. Collector 0.162.0's debug exporter defaults to `verbosity: basic`, which logs a span count and not a trace id. Port 8889 is also a legal port for the collector's own process metrics. The Deferred line treats that debug exporter as the trace and log view.
- **Construction:** Collector A puts the Prometheus exporter of the OTLP metrics pipeline on `0.0.0.0:8889` and sets `debug.verbosity: detailed`. Collector B serves the collector's own telemetry on 8889 and leaves `debug` at the default. Prometheus scrapes port 8889 in both. Grafana shows application metrics for A and `otelcol_*` series for B. A's log contains the order's trace id. B's log contains a line with a span count. Both configs receive three OTLP signals, expose 8889, and name the debug exporter. Tempo and Loki stay absent.
- **guard_snippet:** Tighten AD-11. The metrics pipeline is OTLP to the Prometheus exporter on `0.0.0.0:8889`, and that exporter is what Prometheus scrapes. The collector's internal telemetry stays off that port. Traces and logs go to the debug exporter with `verbosity: detailed`.
- **potential_consequence:** Grafana never shows the order's metrics, or the only trace view has no trace id. The Deferred "read it from the debug exporter" path does not hold for a config that uses the exporter's default.

### 5. Micrometer, the span exporter, and the MDC keys are different contracts

- **Severity:** high
- **Disposition:** autofix
- **Pair:** host Boot process and the collector; the console pattern and the exported log are the two readers
- **Location:** AD-8, AD-11, Consistency Conventions
- **trigger_condition:** AD-11 says Micrometer exports metrics to `/v1/metrics` and traces to `/v1/traces`. On Boot 4.1.1, `micrometer-registry-otlp` exports metrics. Spans export only when `management.opentelemetry.tracing.export.otlp.endpoint` is set. Log records reach `/v1/logs` only when `management.opentelemetry.logging.export.otlp.endpoint` is set and `OpenTelemetryAppender.install` runs on the Boot `OpenTelemetry` bean. The console rule names Micrometer MDC keys `traceId` and `spanId` under the labels `trace_id` and `span_id`. The OpenTelemetry logback MDC appender, also named `OpenTelemetryAppender`, writes `trace_id` and `span_id` instead.
- **Construction:** Process A sets `management.otlp.metrics.export.url` to `http://localhost:4318/v1/metrics` and the same style of property for traces, installs `opentelemetry-logback-appender-1.0`, and prints `trace_id=%X{trace_id}` because that is the label AD-11 requires. Metrics arrive. Traces do not. The console labels are empty, because Micrometer filled `traceId`. Process B sets `management.opentelemetry.tracing.export.otlp.endpoint` and `management.opentelemetry.logging.export.otlp.endpoint` to the AD-11 URLs, prints `trace_id=%X{traceId} span_id=%X{spanId}`, and keeps the sale totals on the console appender. The collector shows B's request trace and A's metrics only. Both processes use the starter, the OTLP HTTP URLs, and a console pattern whose labels are `trace_id` and `span_id`. The sale totals remain on the console in both, so AD-8's "Micrometer does not replace the log" still holds, and the trace next to those lines does not.
- **guard_snippet:** In AD-11, Micrometer OTLP exports metrics via `management.otlp.metrics.export.url=http://localhost:4318/v1/metrics`. The OpenTelemetry span exporter uses `management.opentelemetry.tracing.export.otlp.endpoint=http://localhost:4318/v1/traces`. Logs use `management.opentelemetry.logging.export.otlp.endpoint=http://localhost:4318/v1/logs` plus `io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender.install` on the Boot bean. The console pattern reads `%X{traceId}` and `%X{spanId}` and does not read MDC keys `trace_id` or `span_id`. Do not add the logback MDC appender.
- **potential_consequence:** One build's collector has no order trace and a console line whose `trace_id` label is empty. The other build correlates. AD-8's sale line cannot be tied to the order in the debug exporter for the first build.

### 6. Turn-away is a retry with no shared signal

- **Severity:** high
- **Disposition:** autofix
- **Pair:** page module and inventory module
- **Location:** AD-4, AD-7, Deferred "Admission-gate concurrency number"
- **trigger_condition:** AD-4 says the gate turns an attempt away with no transaction, the page does not render that turn-away, and the page retries with jitter until inventory returns an AD-7 shopper outcome. AD-7 says the page renders the returned outcome and does not replace it. The five outcomes do not include turn-away. The Deferred line says turn-away stays a retry signal and does not name the type.
- **Construction:** Inventory returns an empty result and opens no transaction. The page retries only when inventory returns a dedicated `retry` value, treats empty as a missing outcome, and renders an error page. The other pairing has inventory throw, and the page retries on any throwable and treats an empty result as not accepted. The concurrency number can stay unset. Neither side renders the words "turn-away", and neither side returns one of the five names for that attempt.
- **guard_snippet:** Name one in-process result. The shopper outcomes stay the AD-7 five. Turn-away is a separate result, not an exception and not one of the five. The page retries with jitter only when it receives that result, and renders a shopper outcome only when it receives one of the five.
- **potential_consequence:** A gate rejection becomes an error page, a not-accepted page, or a retry loop that never matches. The admission bound can stay deferred and this clash remains.

### 7. Not accepted and the sale tally are counted in different places

- **Severity:** medium
- **Disposition:** autofix
- **Pair:** page module and inventory module; Grafana's HTTP metrics are the third counter
- **Location:** AD-6, AD-7, AD-8
- **trigger_condition:** AD-7 says not accepted is the outcome for a non-positive quantity or a blank user id, and the page renders the outcome inventory returns. AD-6 rejects a blank user id before a transaction and does not say which module does it. A quantity that is not an integer is neither a non-positive quantity nor given an AD-7 name. AD-8 counts not accepted only when the attempt reaches inventory. Micrometer's HTTP metric counts the form post, including a page view's neighbors, and AD-8 says that metric is not the sale tally.
- **Construction:** The page treats a blank user id and the text `abc` as not accepted and does not call inventory. The sale log does not move. Another page always calls inventory. Inventory counts both as requests and returns not accepted, and it throws on `abc` in a third build so the page has no AD-7 outcome to render. Grafana shows one HTTP request either way. All three leave the console totals as the named tally and leave Micrometer beside them.
- **guard_snippet:** Inventory alone returns not accepted, before a transaction, for a blank user id or a quantity that is not a positive integer. That return counts as one request. The page does not decide it. Blank means empty or whitespace, still with no trim of a non-blank id. Micrometer HTTP metrics are not SM-1.
- **potential_consequence:** The same form post is missing from the sale log, present on it, or shown as an error. Grafana's request series does not match the log for a run that also opened the page.

## Deferred audit

Holes (still let two units diverge):

- Tempo, Loki, and the debug exporter as the trace view. The revisit condition is fine. The default debug exporter is not a readable trace. See finding 4.
- Admission-gate concurrency number. The number can stay deferred. The retry signal cannot. See finding 6.

Not holes (only one unit can choose, and the revisit condition matches):

- Order-id text format. The id stays a unique opaque string, stored in the commit, and the order service does not parse it.
- Starting pool quantity. Only the seeded item row uses the number.
- Wording of the totals log line. Inventory is the only writer until a scraper exists. Finding 7 is about which attempts are counted, not the words.
- HTTP path of the form. Only the page module sees it.
- Process supervision, health checks, and alerting on an unpublished outbox row. No second unit publishes.
- A hosted environment. AD-10 already binds this build to one local topology.
- Restock, and clearing the sold-out hint. No second unit can raise the pool.
