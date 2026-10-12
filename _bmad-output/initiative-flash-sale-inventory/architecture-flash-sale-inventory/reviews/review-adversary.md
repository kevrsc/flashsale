# Adversarial review — architecture spine

Spine reviewed, not edited: `architecture-flash-sale-inventory.md` (updated 2026-10-12).

Method: for each candidate hole, build two units one level down that each obey every current AD and Deferred entry to the letter, and show they still cannot be dropped into one system. Pairs are the inventory module and the Grafana dashboard, two inventory builds, the page module and the inventory module, or the host process and the collector. A Deferred line that still lets the pair diverge is a hole.

The 2026-10-12 text closes the earlier sale-log tally. The counters are Micrometer, the token characters are a closed set, a success may not increment before commit, `SELECT` is `query`, `INSERT` / `UPDATE` / `DELETE` is `update`, zero-row changes count, a page open does not, and the HTTP server request timer is named as not the tally. Those sentences hold. The holes below are the ones that text still allows, centered on `sale.attempts` and `sale.statements`.

**Verdict: fail.** Two inventories can each follow AD-8 and still report a different request total for the same form post, because `admit` is not defined and only `in_process` is tied to an outcome. A Grafana dashboard can follow AD-11 and still query a different series than the counter inventory registered, including the HTTP timer's count.

## Findings

### 1. The result tokens are not a map, and `admit` does not say which calls count

- **Severity:** critical
- **Disposition:** autofix
- **Pair:** two inventory builds; Grafana sums `sale.attempts` for both
- **Location:** AD-4, AD-6, AD-7, AD-8
- **trigger_condition:** AD-8 lists `in_process`, `wait`, `refused`, `sold_out`, `not_accepted`, and `turned_away`, and says one `admit` increments `sale.attempts` once. The only mapping it states is that a success is `result=in_process`. AD-7's outcomes are the different strings "in process", "wait one hour", "sold out", "refused", and "not accepted". AD-4's turn-away is not one of those five. `admit` is not a method, a permit, or a form post anywhere else in the spine. AD-6's "admitted transaction" is a different boundary: it starts after the gate and after a blank user id has already been rejected.
- **Construction:** Inventory A treats `admit` as every call the page makes, including the AD-4 retry loop. Each call increments once. Turn-away writes `turned_away` and opens no transaction. Blank and non-positive write `not_accepted` before a transaction. Wait, refused, and sold out write `wait`, `refused`, and `sold_out`. A success writes `in_process` only after commit. Five gate rejections and then a committed order produce six attempts. Inventory B treats `admit` as AD-6's admitted transaction, the call that acquired the permit. Turn-away and the blank-user rejection happen before that, so they do not increment. The same shopper produces one `in_process`. B still uses only the closed token set, still increments a success only after commit, and still leaves the page open uncounted. A third inventory uses the same admit boundary as A but writes the wait outcome as `not_accepted` and the not-accepted outcome as `refused`. Both tokens are in the set, and AD-8 never assigns them. Grafana grouping by `result` is a different sale for each build.
- **guard_snippet:** In AD-8, map each outcome to one token: in process → `in_process`, wait one hour → `wait`, refused → `refused`, sold out → `sold_out`, not accepted → `not_accepted`, and an AD-4 turn-away → `turned_away`. `admit` is one call from the page into inventory, including each retry, a turn-away, a sold-out hint, and a not-accepted rejection before a transaction. That call increments `sale.attempts` exactly once, with that token. No other call increments it. The success increment is the `in_process` one, and it is still the only one that waits for commit.
- **potential_consequence:** SM-1 for one sale is either the form posts, the gate retries, or only the committed orders. A wait can be graphed as not accepted. The page can show "in process" while Grafana's `in_process` series has not moved, or the reverse.

### 2. A success may be counted at any later moment after commit

- **Severity:** high
- **Disposition:** autofix
- **Pair:** inventory module and Grafana; the page is the other reader of the same order
- **Location:** AD-1, AD-5, AD-8
- **trigger_condition:** AD-8 says the `in_process` increment happens only after the order transaction has committed. It does not say it happens before the outcome returns to the page, or before the Kafka send. AD-1 returns the outcome after commit and sends only after that return. The send is at-least-once.
- **Construction:** Inventory A increments `result=in_process` on the request thread after commit returns and before it returns the shopper outcome. One commit, one increment. Inventory B increments `result=in_process` once, when the outbox publisher's send first completes, which is also after commit and still one increment for that admit. The page has already rendered in process. Until the publisher drains, Grafana's success total is behind the pages B has shown and behind A's counter. A process that dies with a committed outbox row leaves B short until a later send, and a publisher that incremented on every at-least-once attempt would count one order twice. The second of those is forbidden only if "one admit increments once" is read as exactly once; the lag is allowed either way.
- **guard_snippet:** The `in_process` increment happens after the AD-1 commit returns and before the shopper outcome is returned to the page. It does not wait for the Kafka send. A later send, including a retry, does not increment `sale.attempts`.
- **potential_consequence:** During the sale, Grafana's success count and the pages already shown are not the same number. A broker delay, which AD-1 says must not change the shopper outcome, still changes SM-1.

### 3. Grafana and inventory can choose different meter names

- **Severity:** high
- **Disposition:** autofix
- **Pair:** inventory module and the Grafana dashboard; the collector config is the third writer of the name
- **Location:** AD-8, AD-10, AD-11
- **trigger_condition:** AD-8 names the Micrometer counters `sale.attempts` and `sale.statements`. AD-11 says the provisioned dashboard includes those names by `result` and by `kind`. It does not give the Prometheus series, and it does not set the collector's `translation_strategy`. With that field unset, collector 0.162.0's Prometheus exporter keeps the default suffix behavior: dots become underscores, and a counter gains `_total`. A dimensionless counter `sale.attempts` is scraped as `sale_attempts_total`. `NoTranslation` plus Prometheus's default underscore escaping yields `sale_attempts` with no `_total`. `NoUTF8EscapingWithSuffixes` can yield `sale.attempts_total`. The attribute key `result` survives; the instrument name does not.
- **Construction:** Inventory registers `Counter.builder("sale.attempts").tag("result", "in_process")` and the same for `sale.statements` with `kind`. Collector file A leaves `translation_strategy` unset, so Prometheus stores `sale_attempts_total` and `sale_statements_total`. Dashboard A queries `sum by (result) (sale.attempts)` because that is the only name AD-11 writes. The panel is empty. Dashboard B queries `sum by (result) (sale_attempts_total)` and `sum by (kind) (sale_statements_total)`. Collector file B sets `translation_strategy: NoTranslation`, and dashboard B's `_total` query is the empty one. Each file uses the stack's images, scrapes `otel-collector:8889`, and includes the AD-8 names as the panel title or the PromQL. None of them can be rejected for a series the spine never chose.
- **guard_snippet:** Pin the collector and the PromQL in AD-11. The metrics pipeline's Prometheus exporter sets `translation_strategy: UnderscoreEscapingWithSuffixes`. The sale counters have no base unit. Grafana's request panel is `sum by (result) (sale_attempts_total)`. The statements panel is `sum by (kind) (sale_statements_total)`. The Micrometer names stay `sale.attempts` and `sale.statements`. A query for the dotted name, or for either name without `_total`, is not the dashboard.
- **potential_consequence:** The counters increment in the host process and Grafana shows no sale. Two Compose files that both match the image tags never form one dashboard query.

### 4. The HTTP count can still be the sale tally

- **Severity:** high
- **Disposition:** autofix
- **Pair:** host Boot process and the Grafana dashboard; inventory's counter is the other instrument on the same registry
- **Location:** AD-8, AD-11
- **trigger_condition:** AD-11 says the HTTP server request timer is not the tally, and that the dashboard includes `sale.attempts`. Boot 4.1's timer is `http.server.requests` unless `management.observations.http.server.requests.name` changes it. That property is unset in the spine. The timer's base unit is milliseconds, so the same default exporter exposes `http_server_requests_milliseconds_count`, not a series named `http.server.requests`. AD-8 forbids an HTTP auto-metric standing in for the sale meters and does not reserve the counter names against a second instrument. Opening the page does not increment `sale.attempts`, but it does increment the timer. One form post is one timer sample and can be many `admit` calls.
- **Construction:** Process A leaves the observation name at `http.server.requests` and registers the AD-8 counter. Dashboard A uses only `sum by (result) (sale_attempts_total)`. Process B sets `management.observations.http.server.requests.name=sale.attempts` and still registers the counter with tag `result`. Prometheus then has `sale_attempts_total{result=...}` and `sale_attempts_milliseconds_count` labeled with the HTTP tags, including `outcome`, not `result`. Dashboard B's sale row is `sum(sale_attempts_milliseconds_count)` plus a text panel that includes the words `sale.attempts` by `result`. The milliseconds series counts GETs of the form. The business counter does not. A third dashboard keeps the literal query `sum(http_server_requests_milliseconds_count)` as the request stat and adds an empty `sale.attempts` panel so the file still includes that name. B and the third file never graph a meter whose Micrometer name is the default timer name, which is the only HTTP instrument the rule names.
- **guard_snippet:** Reserve the two names. `sale.attempts` and `sale.statements` are only the AD-8 counters. `management.observations.http.server.requests.name` stays `http.server.requests`. The sale dashboard's request query is `sum by (result) (sale_attempts_total)` and no other target. `http_server_requests_milliseconds_count` and `sale_attempts_milliseconds_count` are not SM-1.
- **potential_consequence:** Grafana's request number includes page opens and excludes gate retries, or the reverse of the counter inventory recorded. Two instruments own the name `sale.attempts`.

### 5. The log may still print totals

- **Severity:** high
- **Disposition:** autofix
- **Pair:** inventory module and a person reading the console; the OpenTelemetry log export is the same events
- **Location:** AD-8, AD-11
- **trigger_condition:** AD-8 says the application log does not print running request, success, query, or update totals. AD-11 repeats that for the console and forbids a custom sale-totals log interval. The four nouns are the sum, the `in_process` total, and the two `kind` values. A per-attempt increment, a cumulative line for the other tokens, and a metrics-pipeline debug exporter are not those four nouns and are not an interval.
- **Construction:** Process A increments the counters and logs only the shopper sentence, with the AD-11 trace pattern and no sale numbers. Process B, on each admit, logs `sale.attempts result=in_process 1` (the increment, not the sum) and logs `turned_away`, `wait`, `refused`, `sold_out`, and `not_accepted` as cumulative values. It never prints `requests`, `successes`, `queries`, or `updates`, and it has no scheduled interval. The console appender and `OpenTelemetryAppender` both carry those lines. A reader summing `1` rebuilds a request total that need not match Grafana when finding 1's two inventories differ. Collector file B also puts the debug exporter on the metrics pipeline, still exports `0.0.0.0:8889`, and the collector log prints the cumulative points. AD-11 sends traces and logs to the debug exporter; it does not say the metrics pipeline has only the Prometheus exporter.
- **guard_snippet:** The application log, the console, and the exported log records do not print a sale counter name, a `result` or `kind` token together with a number, an increment, or a cumulative total. No schedule logs those numbers. The collector metrics pipeline's only exporter is the Prometheus exporter on `0.0.0.0:8889`.
- **potential_consequence:** The console is again a second tally. It can count a different set of attempts than `sale.attempts`, and the collector log can show the running totals the amendment removed from the application.

### 6. `sale.statements` has a floor and no ceiling

- **Severity:** medium
- **Disposition:** autofix
- **Pair:** two inventory builds; Grafana reads `sale.statements` by `kind`
- **Location:** AD-1, AD-8, AD-9
- **trigger_condition:** AD-8 says every database statement from inventory JDBC and from the outbox publisher increments `sale.statements`, including a change of zero rows. It does not say "only those statements" or "exactly once". Flyway's startup SQL, a pool check, and Namastack's in-transaction outbox insert are not named. The insert is not application code (AD-1) and is not obviously the later publisher.
- **Construction:** Inventory A wraps the inventory JDBC template and the publisher's client. Each of those statements increments once. `SELECT` is `query`. `INSERT`, `UPDATE`, and `DELETE` are `update`. Flyway and the in-transaction outbox insert go through the `DataSource` and do not increment. Inventory B wraps the shared `DataSource`. Every statement A counts still increments, and so do Flyway and the outbox insert inside the order transaction. A third wrapper increments in the repository and again in the pool decorator. Each named statement has incremented. A's `update` total for one committed order is short of B's by the outbox insert, and short of the third by a factor of two. All three reset on restart, live in the one process, and leave HTTP and JDBC auto-metrics off the dashboard.
- **guard_snippet:** Each of these statements increments `sale.statements` exactly once: inventory's statements on the customer and item rows, Namastack's outbox insert inside the AD-1 transaction, and every later statement the outbox publisher runs. `SELECT` is `query`. `INSERT`, `UPDATE`, and `DELETE` are `update`. Flyway, pool validation, `BEGIN`, and `COMMIT` do not increment it. Nothing increments it twice.
- **potential_consequence:** SM-2 for the same sale depends on which client was wrapped. A startup migration is graphed as sale updates. Two decorators double the query and update totals.

### 7. Turn-away still has no shared return value

- **Severity:** high
- **Disposition:** autofix
- **Pair:** page module and inventory module
- **Location:** AD-4, AD-7, AD-8, Deferred "Admission-gate concurrency number"
- **trigger_condition:** AD-4 turns an attempt away with no transaction, the page does not render that turn-away, and the page retries with jitter until inventory returns an AD-7 shopper outcome. AD-8's `turned_away` is a meter token, not the value of that call. The Deferred line says turn-away stays a retry signal and does not name the type. The five shopper outcomes still exclude it.
- **Construction:** Inventory A returns a dedicated result that is not an exception and not one of the five, and increments `sale.attempts` with `turned_away` when finding 1's map is applied. The page retries only when it receives that result. Inventory B throws, opens no transaction, and can still increment `turned_away` before the throw. The page that matches the dedicated result renders an error page. The other page retries on any throwable and treats an empty return as not accepted. The concurrency number can stay unset. Neither page renders the words "turn-away", and neither inventory returns one of the five names for that attempt.
- **guard_snippet:** Name one in-process result. The shopper outcomes stay the AD-7 five. Turn-away is a separate result, not an exception and not one of the five. The page retries with jitter only when it receives that result. That same result is the only call that increments `sale.attempts` with `turned_away`.
- **potential_consequence:** A full gate becomes an error page, a not-accepted page, or a retry loop that never matches. The meter can count `turned_away` for an attempt the page does not retry.

### 8. Blank is not a test the two modules share

- **Severity:** high
- **Disposition:** autofix
- **Pair:** two inventory builds; the page renders the outcome and `sale.attempts` records it
- **Location:** AD-6, AD-7, AD-8
- **trigger_condition:** AD-6 rejects a blank user id before a transaction, inserts no row, and uses not accepted. The same rule says user id compares with no trim and no case fold. It never says whether blank is empty or whitespace. AD-7 says not accepted is a non-positive quantity or a blank user id. AD-8 will record whichever outcome inventory returns, once finding 1 names the token.
- **Construction:** Inventory A treats blank as length zero. The user id `" "` is not trimmed, is not blank, gets a customer row, and can commit. The counter gets `in_process` after that commit. Inventory B treats blank as no character other than whitespace. `" "` is rejected before a transaction, no row is inserted, the page shows not accepted, and the counter gets `not_accepted`. Neither build trims `" Bob "`, and both reject `""`. The same form post is a sale in one build and a rejection in the other.
- **guard_snippet:** Blank means empty or whitespace only. Reject it before a transaction, insert no row, return not accepted, and increment `sale.attempts` with `not_accepted`. Do not trim or case-fold any other user id.
- **potential_consequence:** One build stores a customer row for a space and deducts the pool. The other counts a not-accepted attempt and leaves the pool alone. Grafana's `in_process` and `not_accepted` series swap for that post.

## Deferred audit

Holes (still let two units diverge):

- Admission-gate concurrency number. The number can stay deferred. The retry signal cannot. See finding 7.
- The closed sale-totals log deferral. Dropping the four running totals still leaves increment lines, the other result tokens, and a metrics debug exporter. See finding 5.

Not holes (only one unit can choose, and the revisit condition matches):

- Order-id text format. The id stays a unique opaque string, stored in the commit, and the order service does not parse it.
- Starting pool quantity. Only the seeded item row uses the number.
- Exemplars from a sale counter to a trace. No dashboard has a trace store to jump to. Tempo is still deferred.
- HTTP path of the form. Only the page module sees it. Finding 4 is the metric on that request, not the path.
- Process supervision, health checks, and alerting on an unpublished outbox row. No second unit publishes. Finding 2 is when the success counter moves, not an alert on the row.
- A hosted environment. AD-10 already binds this build to one local topology.
- Tempo, Loki, and a hosted log aggregator. Debug verbosity is `detailed`, and the console pattern's MDC keys are pinned. The metrics exporter on that pipeline is finding 5, not this deferral.
- Restock, and clearing the sold-out hint. No second unit can raise the pool.

## What held

- The token characters themselves are closed. `in process`, `sold out`, `not accepted`, and `turned away` are not legal `result` values. `query` and `update` are the only `kind` values, and the three DML verbs are assigned.
- A success must not increment before commit. The page-open request must not increment `sale.attempts`.
- The counters are in-process Micrometer counters on the single registry, not a second `MeterRegistry`, not an OpenTelemetry `Meter`, and not a customer or order id tag.
- The OTLP metrics URL and the one-minute export step are pinned. Tag `result` exports as attribute `result`.
