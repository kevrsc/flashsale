# Java 21 / servlet gateway reality check

Checked 2026-10-14 against Maven Central POMs, the Spring Boot 4.1.1 reference and system-requirements pages, Boot 4.1.1 source at tag `v4.1.1`, and Spring Cloud Gateway 5.0.3 source and docs at tag `v5.0.3`. The spine was not edited. No Gradle resolution was run. Versions below are only ones fetched this session.

## Verdict

Pass. The Java 21 toolchain, the servlet gateway starter, and the non-reactive Resilience4j starter match the artifacts and Boot 4.1.1 source. The spine does not name WebFlux or the reactor starter as a dependency.

## Claims

| Claim | Result | Evidence fetched this session |
| --- | --- | --- |
| `org.springframework.cloud:spring-cloud-starter-gateway-server-webmvc:5.0.3` exists and is the servlet gateway | Confirmed | Maven Central POM. Description is "Spring Cloud Gateway Server Web MVC". Direct dependencies are `spring-cloud-starter` 5.0.3, `spring-cloud-gateway-server-webmvc` 5.0.3, and `spring-boot-starter-web` 4.0.8. No WebFlux artifact. `spring-cloud-starter` 5.0.3 depends on `spring-boot-starter`, `spring-cloud-context`, `spring-cloud-commons`, and `bcprov-jdk18on`. The module POM depends on `spring-boot-starter-web` 4.0.8. Current Gateway MVC starter docs: built on Spring Boot and Spring WebMvc.fn, works with servlet runtimes such as Tomcat and Jetty, and says many reactive libraries do not apply. |
| Spring Boot 4.1.1 runs on Java 21 | Confirmed | System requirements page for Spring Boot 4.1.1: at least Java 17, compatible through Java 26. Java 21 is inside that range. The 4.1.1 `SpringApplication` reference page is labeled `(v4.1.1)`. |
| `spring.threads.virtual.enabled=true` turns on virtual threads for embedded Tomcat only on Java 21+ | Confirmed for the Java 21 gate and for embedded Tomcat. The property is not Tomcat-only. | Boot 4.1.1 reference, Virtual threads: "Virtual threads require Java 21 or later. … set the `spring.threads.virtual.enabled` property to `true`." `Threading.VIRTUAL` in Boot 4.1.1 is active only when that property is true and `JavaVersion` is 21 or newer. `TomcatWebServerConfiguration` registers `TomcatVirtualThreadsWebServerFactoryCustomizer` under `@ConditionalOnThreading(Threading.VIRTUAL)`. The 4.1.1 javadoc says that customizer activates `VirtualThreadExecutor` on Tomcat's protocol handler. The same reference page also says thread-pool properties stop having an effect, and that scheduler threads become daemon virtual threads. See finding 2. |
| `spring-cloud-starter-circuitbreaker-resilience4j` 5.0.3 is the non-reactive starter and is the right one for this servlet gateway. The reactor starter is not required. | Confirmed by the circuit-breaker docs, the 5.0.3 gateway POM, and the filter source. The 5.0.3 filter doc page still names the reactor starter. | Circuit Breaker Resilience4j docs: `spring-cloud-starter-circuitbreaker-resilience4j` is for non-reactive applications; `spring-cloud-starter-circuitbreaker-reactor-resilience4j` is for reactive applications. The non-reactive starter POM 5.0.3 depends on `spring-cloud-starter` 5.0.3, `spring-cloud-circuitbreaker-resilience4j` 5.0.3, `resilience4j-circuitbreaker` 2.3.0, and `resilience4j-timelimiter` 2.3.0. It does not depend on the reactor starter. `spring-cloud-gateway-server-webmvc` 5.0.3 declares `spring-cloud-starter-circuitbreaker-resilience4j` 5.0.3 as optional. It does not declare the reactor starter. `CircuitBreakerFilterFunctions` at tag `v5.0.3` takes `CircuitBreakerFactory` and `CircuitBreaker` from `org.springframework.cloud.client.circuitbreaker` and runs the blocking `circuitBreaker.run`. See finding 1. |

`spring-boot-starter-web` 4.0.8 and 4.1.1 both exist. Each POM description says Spring MVC with Tomcat as the default embedded container, deprecated in favor of `spring-boot-starter-webmvc`. Both depend on `spring-boot-starter-tomcat` and `spring-boot-webmvc` at the same version as the starter. `spring-boot-starter-webmvc` 4.1.1 exists. Its description is "Starter for using Spring MVC and Tomcat", and it depends on `spring-boot-starter-tomcat` 4.1.1. That is the sale application's starter in AD-7. The gateway coordinate stays the Web MVC gateway starter; the Boot starter it pulls is the deprecated servlet alias, still Tomcat.

The implementation module `spring-cloud-circuitbreaker-resilience4j` 5.0.3 lists `resilience4j-reactor` 2.3.0 and `reactor-core` 3.8.7 as optional. Optional dependencies are not brought in by the non-reactive starter. That module is not the reactor starter.

## Spine names

WebFlux and the reactor starter are not stack-table rows and are not required dependencies.

| Place | What it says |
| --- | --- |
| AD-2 Prevents | One of the two processes staying on Java 17 or on WebFlux. |
| AD-2 Rule | `sale` and `gateway` do not depend on `spring-boot-starter-webflux`. |
| AD-6 | The gateway is `spring-cloud-starter-gateway-server-webmvc`. "WebFlux is not used." |
| AD-7 | The gateway uses `spring-cloud-starter-circuitbreaker-resilience4j` and does not depend on `spring-cloud-starter-circuitbreaker-reactor-resilience4j`. |
| Stack | Java 21, Spring Boot 4.1.1, the Web MVC gateway starter 5.0.3, and the non-reactive breaker starter 5.0.3. |

No flag. Those names are exclusions.

## Findings

### 1. The 5.0.3 Gateway MVC filter doc still names the reactor starter

`docs/modules/ROOT/pages/spring-cloud-gateway-server-webmvc/filters/circuitbreaker-filter.adoc` at tag `v5.0.3` says the filter needs `spring-cloud-starter-circuitbreaker-reactor-resilience4j` on the classpath. The same sentence is on the current Web MVC circuit-breaker filter page.

The 5.0.3 module POM and `CircuitBreakerFilterFunctions` do not. The POM's optional breaker dependency is `spring-cloud-starter-circuitbreaker-resilience4j` 5.0.3. The filter uses the blocking `CircuitBreaker` API. AD-7 matches the POM and the filter type. A builder who follows that doc page instead of AD-7 would add the reactor starter the spine forbids.

### 2. The virtual-thread property is gated on Java 21 and is not limited to Tomcat

Boot 4.1.1 turns the Tomcat protocol handler onto `VirtualThreadExecutor` only when `spring.threads.virtual.enabled=true` and the JVM is Java 21 or newer. That part of the amendment holds for both jars, because the gateway starter and `spring-boot-starter-webmvc` 4.1.1 bring embedded Tomcat.

The same property is application-wide. The 4.1.1 reference says thread-pool properties no longer apply, and it recommends `spring.main.keep-alive=true` because a virtual scheduler thread is a daemon and will not keep the JVM alive. AD-2 and AD-10 set `spring.threads.virtual.enabled=true` and do not set `spring.main.keep-alive`. This check did not run the processes.

### 3. The gateway POM records Boot 4.0.8 on the servlet starter

`spring-cloud-starter-gateway-server-webmvc` 5.0.3 depends on `spring-boot-starter-web` 4.0.8. `spring-cloud-starter` 5.0.3 depends on `spring-boot-starter` 4.0.8. AD-10 says the Boot Gradle plugin 4.1.1 wins over the Cloud BOM property that records Boot 4.0.8. This check did not resolve a Gradle graph, so that override is still the spine's rule rather than a result shown here. `spring-boot-starter-web` 4.1.1 remains the servlet Tomcat starter, deprecated in favor of `spring-boot-starter-webmvc`.
