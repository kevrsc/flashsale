# Rubric review — Java 21 servlet amendment

Verdict: **Fail**

The spine was not edited. This review covers only the amendment that moves `sale` and `gateway` to Java 21 servlet processes with virtual threads. The spine is `architecture-seckill-hot-path.md` (final, altitude initiative, updated 2026-10-14). Brownfield checked: root `build.gradle` toolchain `JavaLanguageVersion.of(17)`, Boot plugin 4.1.1, `settings.gradle` with the Foojay toolchain resolver and no `include`. Versions checked 2026-10-14, listed at the end.

AD-1, AD-4, and AD-5 still bind the pool script, the qualification document, and Kafka. This amendment does not contradict them, so those rules stay closed.

## Checklist

| Check | Result |
| --- | --- |
| AD-2, AD-6, AD-7, AD-10, and the Stack agree: Java 21, `spring.threads.virtual.enabled=true`, `spring-cloud-starter-gateway-server-webmvc`, no WebFlux, no reactor Resilience4j starter | **Gap on where Java 21 applies.** The gateway artifact, the WebFlux ban, the non-reactor breaker starter, and the virtual-thread property agree. The Stack has one Java row, `21`. AD-2 also keeps `src/` on Java 17. |
| `src/` stays Java 17. Two builders cannot retarget it, and cannot leave `sale` or `gateway` on 17 | **Gap.** The outcome is written in AD-2. The Gradle site that makes both halves true at once is not. |
| A whole dimension stays decided, deferred, or an open question | **Pass.** Local runtime stays in AD-10. Hosted provider, supervision, health, and alerting stay deferred. The servlet and virtual-thread choice is in AD-2, AD-6, AD-7, and AD-10. |
| Settled pool, Kafka, and order-document rules | **Pass.** No new sentence changes the script, the topics, the qualification document, or the order primary key. |
| Named tech is current, and the brownfield root stays ratified | **Gap on the root pin.** The new artifacts exist and are servlet stacks. The Stack row `Java \| 21` is the pin a builder copies onto the only toolchain in `build.gradle`, which is 17. |
| Nothing under Deferred lets two units diverge on this change | **Pass.** Deferred does not offer a second server, a second Java version, or a WebFlux gateway. |

## Findings

### 1. High — Java 21 is a single stack pin, so one build retargets `src/` and another leaves the new jars on 17

**Location:** Stack row `Java | 21`. AD-2 Rule. AD-10 Rule ("local Java 21 processes"). Root `build.gradle` `languageVersion = JavaLanguageVersion.of(17)`.

**What is wrong:** AD-2, AD-10, and the Stack all say `sale` and `gateway` are Java 21. AD-2 also says the existing `src/` tree stays on Java 17. The Stack does not say that. This repo has one toolchain, on the root project, and `settings.gradle` includes no subprojects. Two builders can both claim the amendment:

- One sets the root `languageVersion` to 21, or sets the same version in `allprojects`, because the Stack has one Java row and the Boot plugin line in AD-10 is the root plugin. `src/` then compiles as 21.
- One adds `sale` and `gateway` without a language version of their own, or copies the root `JavaLanguageVersion.of(17)` into them. Gradle does not hand the root toolchain to a subproject. With the daemon on 17, those jars stay on 17. `spring.threads.virtual.enabled=true` does not move a Java 17 process onto virtual threads.

AD-2 states both outcomes and does not name the two Gradle sites. Its Prevents line covers a process left on Java 17 or on WebFlux, and does not cover the root toolchain moving to 21. The Foojay resolver is already applied, so each subproject can declare 21 without changing the root. Nothing in the spine says to do that, or forbids one shared toolchain block.

The other amendment tokens agree. AD-2 names `spring.threads.virtual.enabled=true` for both processes. AD-10's "virtual threads enabled" names no second switch. AD-6 names `spring-cloud-starter-gateway-server-webmvc` on embedded Tomcat and says WebFlux is not used. AD-2 forbids `spring-boot-starter-webflux`. AD-7 uses `spring-cloud-starter-circuitbreaker-resilience4j` and forbids `spring-cloud-starter-circuitbreaker-reactor-resilience4j`. The Stack lists those two starters.

**Smallest fix:** Scope the Stack: `sale` and `gateway` are Java 21; the root project that compiles `src/` stays Java 17. In AD-2, bind the sites: root `build.gradle` keeps `languageVersion = JavaLanguageVersion.of(17)`; `sale/build.gradle` and `gateway/build.gradle` each set `languageVersion = JavaLanguageVersion.of(21)`; no `allprojects` or `subprojects` toolchain assigns one language version to the root and those two projects. AD-10 repeats that split.

**Disposition:** autofix.

## Deferred

No new deferral. The Java split is a rule, not a later choice. Existing deferred items (CDN, admission number, inventory stock posting, a second sale, payment return, an internal clock, hosted provider and supervision) do not pick a JDK or a web stack.

## What holds

The servlet choice is one choice. The gateway artifact is the MVC starter at the 2025.1.3 / 5.0.3 pin, on Tomcat. Sale stays on `spring-boot-starter-webmvc` and the non-reactor breaker starter. The reactor breaker starter is forbidden. The virtual-thread switch is the Boot property, which on Boot 4.1 and Java 21 configures Tomcat's virtual-thread executor. AD-1's script, AD-4's document and order key, and AD-5's Kafka topics are unchanged. AD-10 still fixes the local compose file, Redis `8.10.2`, the existing JDBC, Kafka, and OTLP URLs, and the Boot plugin 4.1.1 winning over the Cloud BOM's Boot 4.0.8 line. JaCoCo 95% line and 95% branch on `sale` and `gateway` is unchanged.

## Versions checked 2026-10-14

| Pin | Check |
| --- | --- |
| Java 21 for `sale` and `gateway`; Java 17 for `src/` | Root `build.gradle` is `JavaLanguageVersion.of(17)`. Boot 4.1 documents `spring.threads.virtual.enabled=true` on Java 21 or later, and `TomcatVirtualThreadsWebServerFactoryCustomizer` turns on Tomcat's `VirtualThreadExecutor`. |
| `spring-cloud-starter-gateway-server-webmvc` 5.0.3 | On Maven Central. The starter POM depends on `spring-cloud-gateway-server-webmvc` and `spring-boot-starter-web` 4.0.8. It does not depend on `spring-boot-starter-webflux` or on `spring-cloud-starter-gateway-server-webflux`. |
| `spring-boot-starter-web` 4.0.8, upgraded when the Boot plugin wins | Still the servlet starter. Its POM description says Tomcat, deprecated in favor of `spring-boot-starter-webmvc`. The 4.1.1 POM is published and depends on `spring-boot-starter-tomcat` and `spring-boot-webmvc`. AD-10 already says plugin 4.1.1 wins over the Cloud BOM's 4.0.8 property. |
| `spring-boot-starter-webmvc` 4.1.1 | POM depends on `spring-boot-starter-tomcat`. Sale's AD-7 coordinate is that starter. |
| `spring-cloud-starter-circuitbreaker-resilience4j` 5.0.3 | POM depends on `spring-cloud-circuitbreaker-resilience4j`, `resilience4j-circuitbreaker` 2.3.0, and `resilience4j-timelimiter` 2.3.0. It does not depend on `spring-cloud-starter-circuitbreaker-reactor-resilience4j`. The reactor starter exists at 5.0.3 and is the one AD-7 forbids. |
| Gateway module optional breaker | `spring-cloud-gateway-server-webmvc` 5.0.3 marks `spring-cloud-starter-circuitbreaker-resilience4j` optional. That is the same non-reactor starter AD-7 already names. |
