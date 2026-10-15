# Walkthrough: JMeter burst

Target: commit f5ec93d on `main`, plus uncommitted edits to the same task. The cleanup moved the driver into its own configuration and runs JMeter on Java 21. The review added a check that the sale starts clean, and a separate `jmeterReset`.

Current block: wrap-up

## Blocks

- [x] 1. Intent (done)
- [x] 2. Broad strokes (done)
- [x] 3. The JMeter plan (done; changed during review)
- [x] 4. The Gradle task runs JMeter (done; changed during review)
- [x] 5. The tally (done)
- [x] 6. Reset between bursts (done; added during review)
- [x] 7. Periphery (done)

## 1. Intent

From the frozen Intent section of [plan-jmeter-burst.md](../plan-jmeter-burst.md), verbatim:

**Problem:** The sale can qualify from one shared pool, but nothing sends the spec's burst: 100,000 attempts against an allotment of 100, expecting 100 qualifications and 100 orders. That check does not belong in `test`.

**Approach:** A separate Gradle task runs Apache JMeter 5.6.3 against a sale and gateway the operator has already started. It loads 100 units, starts the sale, sends 100,000 attempts through the gateway, and passes only when 100 attempts are qualified and those 100 ids each have one order.

## 2. Broad strokes

One opt-in Gradle task drives one JMeter plan, then checks its results against the `orders` table. Open these in order:

- [build.gradle:41](../../../build.gradle#L41) — `jmeterBurst`, the only entry point; nothing else depends on it.
- [sale-burst.jmx:15](../../../loadtest/sale-burst.jmx#L15) — setup group: load 100 units and start the sale on 8081.
- [sale-burst.jmx:130](../../../loadtest/sale-burst.jmx#L130) — burst group: 100 threads × 1,000 loops of `POST /attempts` on the gateway.
- [build.gradle:209](../../../build.gradle#L209) — reads the JMeter results file and counts load, start, and attempt samples.
- [BurstTally.java:13](../../../buildSrc/src/main/java/com/flashsale/jmeter/BurstTally.java#L13) — waits up to two minutes for exactly the qualified ids to appear as orders.

## 3. The JMeter plan

A setup thread group runs before the burst. It sends load and start to sale and stops the whole test if either is not HTTP 200. Only a successful start sets the property the burst checks. The burst sends every attempt through the gateway. A post-processor appends each qualified response body to a file, and the Gradle task reads that file afterwards.

- [sale-burst.jmx:16](../../../loadtest/sale-burst.jmx#L16) — `stoptestnow` when load or start fails.
- [sale-burst.jmx:29](../../../loadtest/sale-burst.jmx#L29) and [sale-burst.jmx:75](../../../loadtest/sale-burst.jmx#L75) — load `{"itemId":"burst","allotment":100}`, then start `{}`.
- [sale-burst.jmx:120](../../../loadtest/sale-burst.jmx#L120) — `saleReady` is set only on a 200 start.
- [sale-burst.jmx:145](../../../loadtest/sale-burst.jmx#L145) — the burst runs only when `saleReady` is set.
- [sale-burst.jmx:156](../../../loadtest/sale-burst.jmx#L156) — the attempt body; a global counter gives every attempt its own `userId`.
- [sale-burst.jmx:195](../../../loadtest/sale-burst.jmx#L195) — records qualified bodies under a shared lock.

## 4. The Gradle task runs JMeter

Before it downloads anything, the task checks that 8080 and 8081 accept connections. It then checks, without changing anything, that `orders` and the Redis `pending` hash are empty. It fetches JMeter 5.6.3 only when the cached archive is missing or fails its SHA-512 check. It runs JMeter non-GUI on the Java 21 toolchain and gives up after seven minutes.

- [build.gradle:49](../../../build.gradle#L49) — port check.
- [build.gradle:60](../../../build.gradle#L60) — leftover orders or pending qualifications fail the task before JMeter starts.
- [build.gradle:93](../../../build.gradle#L93) — download, then checksum, then rename into place.
- [build.gradle:128](../../../build.gradle#L128) — clears the previous run's qualified-body file and fails if it cannot.
- [build.gradle:141](../../../build.gradle#L141) — Java 21 launcher.
- [build.gradle:163](../../../build.gradle#L163) — seven-minute limit on the JMeter process.

## 5. The tally

The results file has to show one load (200), one start (200), and exactly 100,000 attempts, all HTTP 200, finished within five minutes. The qualified-body file has to hold exactly 100 distinct ids. The task then polls `orders` until its set of ids equals that set, or two minutes pass. The polling loop sits in `buildSrc`, so the wrong-tally paths are unit tested.

- [build.gradle:246](../../../build.gradle#L246) — a load that is not 200 fails before the attempt checks.
- [build.gradle:259](../../../build.gradle#L259) and [build.gradle:266](../../../build.gradle#L266) — attempt count and five-minute limit.
- [build.gradle:297](../../../build.gradle#L297) — 100 bodies, 100 ids, none invalid.
- [build.gradle:312](../../../build.gradle#L312) — hands the poll to `BurstTally.await`.
- [BurstTallyTest.java:18](../../../buildSrc/src/test/java/com/flashsale/jmeter/BurstTallyTest.java#L18) — missing order, extra order, wrong qualified count, query failure, and immediate match.

## 6. Reset between bursts

`jmeterBurst` never deletes anything. `jmeterReset` is a separate opt-in task that empties the local sale so the burst can run again. It refuses unless `-PconfirmReset=local` is passed. It deletes the Redis hashes `sale` and `pending` and truncates `orders` and `qualification_report_sent`. Both tasks reach Redis through a small client in `buildSrc` and PostgreSQL through one shared helper.

- [build.gradle:333](../../../build.gradle#L333) — `jmeterReset`.
- [build.gradle:338](../../../build.gradle#L338) — refuses without the confirmation flag.
- [build.gradle:22](../../../build.gradle#L22) — `withOrders`, the shared PostgreSQL helper used by the check, the tally, and the reset.
- [LocalRedis.java](../../../buildSrc/src/main/java/com/flashsale/jmeter/LocalRedis.java) — one Redis command with an integer reply.
- [LocalRedisTest.java](../../../buildSrc/src/test/java/com/flashsale/jmeter/LocalRedisTest.java) — request encoding, integer reply, and error reply.

## 7. Periphery

- [build.gradle:13](../../../build.gradle#L13) — `ordersDriver`, the non-transitive PostgreSQL 42.7.13 driver.
- [buildSrc/build.gradle](../../../buildSrc/build.gradle) — JUnit for the `buildSrc` tests.
- [README.md](../../../README.md) — the Burst section and the destructive-reset warning.
- [docs/devops/README.md](../../../docs/devops/README.md) — local burst reset, never in production.
- [deferred-work.md](../deferred-work.md) — the JMeter entry is gone and the Prometheus entry stays.
