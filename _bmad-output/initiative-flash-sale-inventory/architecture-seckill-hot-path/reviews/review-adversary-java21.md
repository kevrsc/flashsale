# Adversarial review — Java 21 / virtual-thread / servlet-gateway amendment

Spine reviewed, not edited: `architecture-seckill-hot-path.md` (final, updated 2026-10-14). Scope is the amendment in AD-2, AD-6, AD-7, AD-10, and the Stack rows for Java, the MVC gateway starter, and the non-reactor breaker starter. Other ADs were used only as rules a unit must still obey.

Method: for each likely seam, build two units one level down that each obey every current AD to the letter, and show they still cannot be dropped into one system. A clash an existing Rule already forbids is not a hole. Versions and the gateway runtime classpath were checked 2026-10-14. The Boot plugin check imported `spring-cloud-dependencies:2025.1.3` with plugin `org.springframework.boot` 4.1.1 and no `ext` override.

**Verdict: pass.** No pair survives. A gateway left on Java 17 breaks the Java 21 toolchain sentence. A gateway that declares WebFlux or the reactor breaker starter breaks AD-2 or AD-7. Retargeting `src/` to language level 21 breaks “stays on Java 17.” A breaker on the forward of `POST /attempts` breaks the last sentence of AD-7. The pair that obeys all of those sentences is one Gradle build.

## Not a hole

### Which process enables virtual threads

AD-2 says both `sale` and `gateway` set `spring.threads.virtual.enabled=true`. AD-10 says both are local Java 21 processes, started by hand, with virtual threads enabled. Boot 4.1.1 turns on `Threading.VIRTUAL` only when that property is true and the JVM is Java 21 or newer, and `TomcatVirtualThreadsWebServerFactoryCustomizer` then sets Tomcat’s protocol-handler executor to `VirtualThreadExecutor`. AD-6 puts the gateway on embedded Tomcat. AD-7’s `spring-boot-starter-webmvc` is the sale application’s Tomcat starter.

- **Sale-only unit:** the property is set in `sale` and not in `gateway`. The gateway is a Java 21 jar whose Tomcat executor stays on platform threads. That unit breaks AD-2’s “both set” and AD-10’s “with virtual threads enabled.”
- **Property-without-21 unit:** both jars set the property and neither subproject sets a Java 21 toolchain, so a JDK 17 daemon compiles and launches them. Boot does not enable virtual threads on 17. That unit breaks “new Boot jars on a Java 21 toolchain.”
- **Obedient pair:** `sale/build.gradle` and `gateway/build.gradle` each set `JavaLanguageVersion.of(21)`, and each jar sets the property in configuration the hand-started process loads. Root `build.gradle` stays `JavaLanguageVersion.of(17)`. Both Tomcat servers take the same customizer. Nothing in the two jars disagrees about which process enables the switch.

`spring.main.keep-alive` is not a second switch these two processes need. Boot’s own note is that a virtual scheduler thread is a daemon and will not keep a JVM alive by itself. Both processes embed Tomcat, so the connector’s non-daemon threads keep the process up. A unit that omits keep-alive still matches AD-2 and AD-10.

### Whether the gateway pulls WebFlux

AD-2 says the two new projects do not depend on `spring-boot-starter-webflux`. AD-6 says the gateway is `spring-cloud-starter-gateway-server-webmvc` and WebFlux is not used. AD-7 says the gateway uses `spring-cloud-starter-circuitbreaker-resilience4j` and does not depend on `spring-cloud-starter-circuitbreaker-reactor-resilience4j`.

The 5.0.3 MVC starter POM depends on `spring-cloud-starter`, `spring-cloud-gateway-server-webmvc`, and `spring-boot-starter-web` 4.0.8. It does not depend on `spring-boot-starter-webflux` or on `spring-cloud-starter-gateway-server-webflux`. `spring-boot-starter-web` 4.0.8 and 4.1.1 are the deprecated servlet alias: Jackson, Tomcat, and `spring-boot-webmvc`. `spring-cloud-commons` 5.0.3 marks `spring-boot-starter-webflux` optional, so it is not transitive. A Gradle resolution of the MVC starter, the non-reactor breaker starter, and `spring-boot-starter-opentelemetry:4.1.1` contains no `spring-webflux`, no `spring-boot-starter-webflux`, and no `reactor-netty`. The same graph with the Boot plugin and the 2025.1.3 Cloud BOM rewrites `spring-boot-starter-web:4.0.8` to 4.1.1 and still has no WebFlux artifact.

- **Direct WebFlux unit:** the gateway declares `spring-boot-starter-webflux` or the WebFlux gateway starter. That breaks AD-2 or AD-6.
- **Reactor-starter unit:** the gateway adds `spring-cloud-starter-circuitbreaker-reactor-resilience4j` because the Gateway MVC circuit-breaker doc page still names it. The 5.0.3 filter class does not. `CircuitBreakerFilterFunctions` calls blocking `CircuitBreakerFactory`. The doc-following unit breaks AD-7.
- **Obedient gateway:** only the MVC starter and the non-reactor breaker starter. The classpath stays servlet. There is no second unit whose classpath is also legal and still brings WebFlux in.

### Whether `src/` is retargeted to 21

AD-2 says `sale` and `gateway` are new Gradle subprojects and new Boot jars on a Java 21 toolchain, and that the existing `src/` tree stays on Java 17 and is not extended. Root `build.gradle` is `JavaLanguageVersion.of(17)`. `settings.gradle` already applies the Foojay toolchain resolver and includes no subprojects. Gradle does not copy the root toolchain into a subproject.

- **Retarget unit:** the root `languageVersion` becomes 21, or `allprojects` sets 21, because the Stack table has a single Java row. `src/` then compiles and `bootRun`s as 21. That breaks “stays on Java 17.” `options.release = 17` on the root compile tasks does not repair it: `bootRun` still uses the toolchain JDK, so the previous service is a Java 21 process.
- **Leave-the-new-jars unit:** `sale` and `gateway` are included and do not set a language version, or they copy `of(17)`. With the daemon on 17 they stay on 17, and the virtual-thread property does nothing. That breaks “on a Java 21 toolchain.”
- **Obedient pair:** the root block stays `of(17)`; `sale/build.gradle` and `gateway/build.gradle` each set `of(21)`; neither `allprojects` nor `subprojects` assigns one language version to the root and those two projects. A throwaway multi-project with that shape, Boot plugin 4.1.1 on the root and on both subprojects, configured under Gradle 9.7.1. The hierarchy resolves. The two toolchain sites do not fight.

### Whether a gateway breaker wraps `POST /attempts`

AD-7 says the gateway uses `spring-cloud-starter-circuitbreaker-resilience4j` when it protects a route, does not depend on the reactor starter, and “The gateway breaker does not wrap the forward of `POST /attempts`.” `GatewayMvcProperties` in the 5.0.3 jar has a route list and no default-filter list. `FilterAutoConfiguration.CircuitBreakerFilterConfiguration` only registers the filter supplier, and only when `org.springframework.cloud.client.circuitbreaker.CircuitBreaker` is present. The supplier does not attach itself to every route. `resilience4j-spring-boot3`, which the non-reactor starter pulls, applies `@CircuitBreaker` to annotated methods only.

- **Wrap unit:** the attempts route’s `RouterFunction` calls `.filter(circuitBreaker(...))`, or the controller method that performs the forward is annotated `@CircuitBreaker`. That breaks the last sentence of AD-7. An open fallback that skips the script also breaks “The breaker does not skip the Lua script” and “The attempt response returns from the Lua result.”
- **Obedient gateway:** `POST /attempts` is forwarded with no circuit-breaker filter and no annotation on that forward. The same starter may protect some other route, or none. The sale application’s breaker stays off the Lua script. The two processes do not disagree about whether the attempt forward is inside a breaker.

## What held

One legal build satisfies the amendment. `sale` and `gateway` are subprojects on toolchain 21 with `spring.threads.virtual.enabled=true`. The root toolchain stays 17 and `src/` is not extended. The gateway dependency is the MVC starter; the Boot plugin lifts its hardcoded `spring-boot-starter-web` 4.0.8 to 4.1.1; WebFlux is not on that classpath. The breaker starter is the non-reactor one, and it is not on the forward of `POST /attempts`. AD-1, AD-4, and AD-5 are untouched by this amendment.
