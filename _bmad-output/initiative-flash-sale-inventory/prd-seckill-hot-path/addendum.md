# Addendum: Seckill hot-path

User-stated mechanism and constraints. These belong with architecture and operations. They are not the PRD's feature list.

## Boundary

- The user expects the pattern used by large seckill platforms, including the published Alibaba approach: the hot item is pulled off the normal order path.
- Technology already in this service may be removed when the pattern does not need it. A service the pattern needs may be added.

## Components the user requires

- **Traffic throttling and pre-filtering.** A CDN and an API gateway filter and rate-limit attempts so only a fraction of the crowd reaches the service's qualification path, and only qualifications reach the durable store.
- **Distributed caching.** The pool count lives in a shared in-memory store such as Redis. An attempt does not read a disk database to learn the count. Every instance uses that same count. The disk database records orders after qualification.
- **Asynchronous queuing.** A qualification is pushed to a broker. Kafka and RabbitMQ are both named. The requirement is a queue. The durable order is written from that queue.
- **Graceful degradation.** Work that is not qualification yields when it would threaten the core path. Spring Cloud Circuit Breaker backed by Resilience4j (`spring-cloud-starter-circuitbreaker-resilience4j`) wraps the order write, the qualification report, the close report, and telemetry export. Hystrix is not used. The pool decrement stays on the qualification path.

## Published seckill pattern

Landscape notes from published Taobao and Alibaba Cloud writeups. Not requirements until adopted. Sources disagree on the shared pool and on when a broader stock record updates.

- The sale is a separate activity, off the normal catalog path. Xu Lingbo, "淘宝大秒系统设计详解" (https://developer.aliyun.com/article/64762), preheats a seller-registered activity onto its own cluster. He does not describe a warehouse transfer of a set number of units at the on-sale second. Alibaba Cloud's Tair guide (https://help.aliyun.com/zh/redis/use-cases/use-apsaradb-for-redis-to-build-a-business-system-that-can-handle-flash-sales) loads `Total`, `Booked`, and a start flag before the burst, then flips the flag to open the sale. Hu Dao (https://developer.aliyun.com/article/754897) lends cache buckets from a MySQL total pool and can refill a bucket during the sale.
- A winning click deducts the hot pool, not warehouse on-hand. Xu Lingbo's 2016 authoritative update is 下单减库存 (order-time stock deduction) on one hotspot database row. The Tair guide increments `Booked` in the request and enqueues the order; the order database is written by a worker. Hu Dao deducts the bucket, then writes an inventory journal asynchronously.
- Many application instances share an external counter. Process memory is only a short-lived read cache. Xu Lingbo's shared pool is the hotspot row, on the successful write. The Tair guide and the help-center article (https://help.aliyun.com/zh/document_detail/2860128.html) share one Redis key and skip the database when the deduction fails. Hu Dao shares cache buckets and journals to the database afterward.
- "Tell inventory only when the sale ends" and "deduct warehouse stock inside the winning request" both miss these designs. Tair refreshes the hot counter from the database on a timer after cancellations. Hu Dao refills during the sale and returns leftovers when a bucket is retired. The help-center article reconciles with messages and a scheduled job.
- Click-time success is a stock qualification, not a payment. Xu Lingbo's GeekTime chapter (public reproduction: https://blog.nowcoder.net/n/a03a799a1da440a1a8f644bc1ac51675) recommends 下单减库存 for a large seckill. 预扣 with a timeout, released if unpaid, is the ordinary-commerce default. Login, payment, warehouse on-hand, and normal catalog orders stay off the click path.
- Sold-out traffic is dropped before the counter. Xu Lingbo measured about 600,000 front-end requests per second against about 1,500 order-time deductions. The system used a cached page, a CDN, a quiz that spreads clicks, and read-tier checks. The Tair read path stops further requests once accepted orders reach `Total`.

## Operations

- Metrics, traces, and logs are exported through an OpenTelemetry collector. Prometheus scrapes the metrics. Grafana shows them.
- Logging should be meaningful.
- Test coverage and similar orthogonal concerns stay in scope.
