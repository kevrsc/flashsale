# DevOps: Redis for the sale

Support notes for the Redis state written by [`sale/src/main/resources/sale.lua`](../../sale/src/main/resources/sale.lua). The local image is `redis:8.10.2` on port `6379` (`compose.yaml`). The sale process reads that address from `spring.data.redis.host` and `spring.data.redis.port` (`localhost` and `6379`).

The script is the only writer of hash `sale`, and the only writer that adds a field to hash `pending`. The order consumer removes a `pending` field, through the script's `ack` command, only after the order insert commits. The gateway reads hash `sale` with `HMGET` and does not run the script for those reads.

One `EVAL` runs without another client command in between. An attempt's decrement and its pending document are the same execution.

## Keys

Two hashes. No key prefix, no TTL, and no set, list, string, or stream for this path. Both names are fixed: one sale, key `sale`, and its outbox, key `pending`.

### Hash `sale`

The sale record. `load` writes it once. Later commands update fields.

| Field | Value | Written by | Meaning |
| --- | --- | --- | --- |
| `itemId` | string | `load` | Item being sold |
| `allotment` | decimal string | `load` | Starting quantity |
| `remaining` | decimal string | `load`, then `attempt` | Units still available. `attempt` subtracts 1 |
| `soldOut` | `"0"` or `"1"` | `load`, then `attempt` | Set to `"1"` on the decrement that reaches 0 |
| `open` | `"0"` or `"1"` | `load`, then `start` | `start` sets `"1"` |
| `closed` | `"0"` or `"1"` | `load`, then `close` | `close` sets `"1"` |
| `closeRemaining` | decimal string | `close`, first time only | `remaining` at the first close. Later closes return this stored value |

A missing field is treated as `"0"` inside the script. The gateway does the same for `open`, `closed`, `soldOut`, and `remaining`. Flags are the strings `"0"` and `"1"`.

`load` and `start` do nothing once `open` or `closed` is `"1"`. `start` also does nothing when `itemId` is missing. `close` always sets `closed` to `"1"`. It stores `closeRemaining` only when that field is absent, so a second close reports the same count.

### Hash `pending`

Qualifications that have not been acknowledged. Each field is one qualification.

| | |
| --- | --- |
| Field name | `qualificationId`, a lowercase UUID built in the script (`8-4-4-4-12` from the first 32 hex digits of a SHA-1) |
| Field value | JSON: `qualificationId`, `saleId` (the string `sale`), `userId`, `itemId` |

`attempt` writes that field in the same execution that decrements `remaining`. `ack` deletes the field named by the qualification id. `ack` still returns `applied: true` when the field is already gone.

The id is a hash of the user id, the remaining count before the decrement, and Redis `TIME`. Each value is length-prefixed before the hash.

## Commands

`ARGV[1]` selects the command. Lifecycle and ack return `{"applied":true}` or `{"applied":false}`. `attempt` returns `answer`. `close` returns `saleId`, `itemId`, and `remaining`.

| Command | Effect |
| --- | --- |
| `load` | Writes `itemId`, `allotment`, `remaining` (equal to the allotment), `soldOut=0`, `open=0`, `closed=0`. Refuses when the sale is already open or closed, or when the allotment is not a decimal string. |
| `start` | Sets `open=1`. Does not change `remaining`. Refuses when the sale is already open or closed, or when `itemId` was never loaded. |
| `attempt` | Answers `unidentified`, then `closed`, then `not_open`, then `sold_out`, and only then decrements. A qualified attempt stores the pending document and returns `qualificationId`. |
| `close` | Sets `closed=1`. First close also stores `closeRemaining`. |
| `ack` | `HDEL` of one field on `pending`. |

## What to read during an incident

Reads do not take a unit and do not create a qualification.

```bash
docker compose exec redis redis-cli HGETALL sale
docker compose exec redis redis-cli HLEN pending
docker compose exec redis redis-cli HSCAN pending 0 COUNT 20
docker compose exec redis redis-cli HGET pending <qualificationId>
```

`HGETALL sale` is the pool the gateway answers from: `closed`, then `open`, then `soldOut` or `remaining` of `0`. `HLEN pending` is the number of qualifications not yet removed. A field still present means the order insert has not committed, or the relay has not finished republishing that document to `sale.qualification`. The relay scans `HGETALL pending` and republishes each value while the field remains.

Leave the hashes alone from `redis-cli` while a sale is open. `HSET` on `sale` and `HDEL` on `pending` skip the script, so the pool, the flags, and the order row can disagree.

## Alerts

Prometheus (`localhost:9090`) evaluates group `seckill.rules` from [`prometheus/seckill.rules.yml`](../../prometheus/seckill.rules.yml) every 15s. Nothing routes notifications. Read state at `http://localhost:9090/alerts`. Prometheus reads the rules file only at startup, so run `docker compose restart prometheus` after editing it. The sale and gateway push metrics to the collector every 10s, and the collector labels each series with `exported_job` (`sale` or `gateway`).

| Alert | Expression in words | For | Severity |
| --- | --- | --- | --- |
| `HighQueueDepth` | Sum of `kafka_consumer_fetch_manager_records_lag{exported_job="sale",topic="sale.qualification"}` above 50000 | 30s | `critical` |
| `RedisHitRateLow` | Gateway rate of `sale_qualification_total{answer=~"sold_out\|closed"}` divided by gateway plus sale rate of the same answers, below 0.99 | 60s | `warning` |

`HighQueueDepth` means the order consumer is behind on `sale.qualification`, so `HLEN pending` is growing and orders lag their qualifications. Kafka also reports each partition under the legacy name `sale_qualification`. The rule ignores it so lag is not counted twice.

`RedisHitRateLow` looks only at `sold_out` and `closed`. Those answers occur only after sell-out or close, so the gateway's share of them is the share answered from hash `sale` without `EVAL`. A low share means the gateway is forwarding attempts it should answer itself. Check `HGETALL sale` for `soldOut`, `remaining`, and `closed`. A missing side counts as 0. With no such answers the ratio has no value and the alert stays silent.

A healthy 100,000-attempt burst keeps both inactive. Lag returned to 0 after the burst, and the gateway answered 99879 of 99900 `sold_out` answers. The lowest 1-minute share, right after sell-out, was about 0.994.

`./gradlew prometheusRules` runs `promtool check rules` and `promtool test rules` against [`prometheus/seckill.rules.test.yml`](../../prometheus/seckill.rules.test.yml) in `prom/prometheus:v3.15.0`. `./gradlew check` depends on it.

## Local burst reset

`./gradlew jmeterReset -PconfirmReset=local` deletes both hashes and truncates `orders` and `qualification_report_sent`, so `./gradlew jmeterBurst` can run again. It is for the local learning sale only. Never run it in production: it removes every order and every qualification still waiting in `pending`, and nothing can rebuild them.
