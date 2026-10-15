package com.flashsale.sale;

import java.time.Duration;

import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.bulkhead.ThreadPoolBulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;

import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigBuilder;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigurationProperties;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4jBulkheadProvider;

public final class TestBreakers {

	private TestBreakers() {
	}

	public static DownstreamGuard guard() {
		CircuitBreakerConfig openedByOneFailure = CircuitBreakerConfig.custom()
				.slidingWindowSize(1)
				.minimumNumberOfCalls(1)
				.failureRateThreshold(1)
				.waitDurationInOpenState(Duration.ofMinutes(5))
				.build();
		TimeLimiterConfig limit = TimeLimiterConfig.custom().timeoutDuration(Duration.ofSeconds(2)).build();
		Resilience4JCircuitBreakerFactory factory = new Resilience4JCircuitBreakerFactory(
				CircuitBreakerRegistry.ofDefaults(),
				TimeLimiterRegistry.of(limit),
				new Resilience4jBulkheadProvider(
						ThreadPoolBulkheadRegistry.ofDefaults(),
						BulkheadRegistry.ofDefaults(),
						new Resilience4JConfigurationProperties()));
		factory.configureDefault(id -> new Resilience4JConfigBuilder(id)
				.circuitBreakerConfig(openedByOneFailure)
				.timeLimiterConfig(limit)
				.build());
		return new DownstreamGuard(factory);
	}

}
