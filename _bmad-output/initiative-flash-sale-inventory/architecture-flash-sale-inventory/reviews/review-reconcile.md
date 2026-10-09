# Reconciliation: epic and spec constraints against the architecture spine

Verdict: **gaps**

The spine was not edited. The epic and the spec were not edited.

- Epic: `epic-local-sale-telemetry/epic-local-sale-telemetry.md`
- Spec: `spec-flash-sale-inventory/spec-flash-sale-inventory.md` (Constraints, Non-goals, CAP-7)
- Spine: `architecture-flash-sale-inventory/architecture-flash-sale-inventory.md` (final)

TEL-1 through TEL-6 and the epic's Done when are satisfiable under the rules, except where AD-11's Prevents line fights TEL-4. The spec sentence that a metrics endpoint does not replace the sale totals log still holds in AD-8 and in the AD-11 rule. The same AD-11 Prevents line is wide enough that a builder can treat that log, and the required log exporter, as violations.

## Findings

### 1. High — AD-11's Prevents drops the split between the sale log, the log exporter, and Micrometer

TEL-3 and the spec Constraints: request totals and database query and update totals stay visible in the service log. A metrics endpoint does not replace that log. TEL-4: logs reach the collector through an OpenTelemetry log exporter, not through Micrometer. The epic decision is the same: Micrometer metrics are additive, the sale totals log remains the tally, and Prometheus does not replace it.

What landed:

- AD-8 still prevents a metrics endpoint in place of the log. The rule writes requests, queries, and updates to the application log, and says Micrometer metrics and the OpenTelemetry log export sit beside that log and do not replace those lines. The counts do not make a sale successful.
- AD-11's rule keeps the console appender, prints `trace_id` and `span_id`, and leaves the AD-8 sale totals on that console log. Logs go out through `opentelemetry-logback-appender-1.0`, not through Micrometer. Grafana reads Prometheus. That is a metrics view beside the log.

What the Prevents line does instead:

- "A second exporter beside Micrometer" treats the OpenTelemetry log exporter as forbidden. TEL-4 requires that exporter, and requires that it not be Micrometer. A builder who obeys the Prevents line and skips the appender cannot get the log signal to the collector.
- "Traces or logs leaving by a protocol other than OTLP" can be read as forbidding the console appender. The console appender is the service log the spec requires. Dropping it leaves the totals only on the collector path, which is the replacement the constraint forbids.

The rule and the Prevents line cannot both be followed. Until the Prevents line is narrowed, TEL-4 is not cleanly satisfiable, and the sale-totals constraint is only as stable as the reader who prefers the rule.

Fix the Prevents line so it says all of the following: Micrometer OTLP is the only metrics and traces export; the OpenTelemetry log exporter is the log export and is not a second Micrometer exporter; the console sale-totals lines stay; a metrics endpoint, Prometheus, or Grafana does not replace those lines; Grafana reads Prometheus only.

### 2. High — The schema-registry half of the order-event non-goal is not in AD-5

Spec non-goal: the order event is not Avro or Protobuf, and it has no schema registry. The memlog decision says the same: no schema registry and no shared Avro or Protobuf file.

AD-5 binds UTF-8 JSON with exactly `orderId`, `userId`, `itemId`, and `unitsDeducted`, and no envelope. That blocks an Avro or Protobuf value. It does not block a schema registry beside that JSON. TEL-5 keeps the JSON body, the message key, and `spring-modulith-events-kafka`, and adds W3C trace-context text headers. A builder can register a JSON schema and still match every sentence in AD-5 and TEL-5. The order service can then depend on the registry. That is the divergence the non-goal closed.

The header amendment did not need to weaken this. Put "no schema registry" on AD-5's rule or Prevents, next to the closed JSON object.

## Coverage

### TEL-1 through TEL-6

| ID | Result |
| --- | --- |
| TEL-1 | Landed. AD-10 runs the collector, Prometheus, and Grafana beside Postgres and Kafka. One Boot process stays on the host and uses localhost. AD-11 exports OTLP to `localhost:4318`. |
| TEL-2 | Landed. AD-11: Micrometer exports metrics and traces OTLP over HTTP to the collector. |
| TEL-3 | Landed in the rules. MDC keys `traceId` and `spanId` print as `trace_id` and `span_id`. Sale totals still list requests, queries, and updates. A metrics endpoint or view does not replace that log (AD-8, AD-11 rule). Finding 1 is the Prevents clash. |
| TEL-4 | Split. The rule names `otel-collector-config.yaml` in the structural seed, receives metrics, traces, and logs, exposes metrics on 8889, writes the trace to the debug exporter, and sends logs through the OpenTelemetry log appender. The Prevents line forbids that appender (finding 1). |
| TEL-5 | Landed. Topic, JSON body, and message key are unchanged. `traceparent` is a text header. `tracestate` is present only when the propagator emits it. Headers are not JSON properties. Publication stays `spring-modulith-events-kafka` through the single `KafkaTemplate`, with `spring.kafka.template.observation-enabled=true`. The send stays in the request trace. |
| TEL-6 | Satisfiable, and not an AD. AD-5's text headers can be read with `kafka-console-consumer` or a GUI, and a test can assert them. The spine does not forbid either check. |

### Done when

| # | Result |
| --- | --- |
| 1 | Landed with TEL-1. |
| 2 | Satisfiable. Grafana's datasource is Prometheus. The collector exposes 8889 for Prometheus to scrape and writes the order's trace to the debug exporter. The spine does not name a dashboard. Explore against those series meets "a metric from that run." |
| 3 | Landed if the console appender stays (finding 1). The pattern carries the trace id and span id. The collector debug exporter receives the log signal. AD-8 totals remain the requests, queries, and updates lines. The log span and the Kafka `traceparent` share the request trace; the rule does not force those two span ids to be equal, and Done when does not require that. |
| 4 | Satisfiable with TEL-5 and TEL-6. |
| 5 | Landed. AD-10 is one local environment. A hosted environment stays deferred. |

### Spec constraints

| Constraint | Result |
| --- | --- |
| Deduction and order event are one outcome | Landed (AD-1). |
| Landing page collects user ID and quantity and does not decide the pool | Landed (AD-2, AD-7). |
| Customer recognized only by a user ID this service does not create, manage, or authenticate | Landed (AD-6, AD-7). |
| One item; the pool is the only count of units still available | Landed (AD-4, AD-9). |
| Units go to the earliest successful orders | Held by AD-3's conditional update: the matching update takes the units, and the same unit is not published twice. AD-4 does not bind a FIFO queue across the admission gate. That is the existing admission rule, not a telemetry drop. |
| Order processing continues from the event and does not decide the pool | Landed (AD-2, AD-5). The context diagram draws inventory to the topic, then the topic to the order service. |
| High volume, including contention | Landed (AD-4). |
| Request, query, and update totals stay in the service log. A metrics endpoint does not replace that log | Holds (AD-8 and the AD-11 rule). Finding 1 is how the Prevents line can be read to drop it. |
| Page, inventory decision, publication, and local runtime follow this spine | Landed. Telemetry is AD-10 and AD-11. |

### Non-goals

| Non-goal | Result |
| --- | --- |
| No payment, fulfillment, or completing the purchase | Landed (AD-5). |
| No accounts | Landed (AD-7). |
| No second item | Landed (AD-7, AD-9). |
| No hosted environment | Deferred, with a revisit when a launch target exists. |
| No restock after the pool hits zero | Deferred, with a revisit only if the pool can increase after zero. |
| Order event is not Avro or Protobuf, and it has no schema registry | Avro and Protobuf are blocked by the JSON value rule. The schema registry is finding 2. |
| Hosted Loki, OpenSearch, and Elastic (epic boundary) | Deferred as Tempo, Loki, and a hosted log aggregator. The console pattern is what they would parse. |

### CAP-7

Landed in AD-8 under SM-1, SM-2, and SM-C1. The inventory log shows order attempts that reach inventory, and every database statement counts as a query or an update. Those totals are not a successful sale. A deducted unit that was not in the pool, or a customer above the limit of 3, fails SM-C1. Micrometer and the log export do not become that tally.

## Considered and not counted as gaps

- TEL-6's consumer command and test are epic acceptance checks. Text headers make them possible. The spine does not inventory tests.
- `tracestate` only when the propagator emits it matches W3C trace-context. TEL-5's headers are that pair, not a new JSON field.
- CAP-8's not-accepted outcome is counted as a request once it reaches inventory. CAP-7 asks for attempts that arrived. The spec does not exclude a rejected attempt from the request total.
- Page opens still are not requests. Micrometer may count the HTTP call. AD-8 keeps that call out of the sale tally, which is the metrics-endpoint constraint applied to HTTP metrics.
- In-memory totals that reset on restart stay compatible with CAP-7. Lines already written remain in the log.
