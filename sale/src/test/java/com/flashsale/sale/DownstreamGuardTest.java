package com.flashsale.sale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.cloud.client.circuitbreaker.ConfigBuilder;

import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigBuilder;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigurationProperties;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4jBulkheadProvider;

class DownstreamGuardTest {

	@Test
	void openBreakerShedsExportAndStillRunsInsertAndReport() {
		DownstreamGuard guard = guard();
		AtomicInteger exports = new AtomicInteger();
		guard.export(() -> exports.incrementAndGet());
		guard.export(() -> {
			throw new IllegalStateException("collector down");
		});
		guard.export(() -> exports.incrementAndGet());
		assertEquals(1, exports.get());

		AtomicInteger inserts = new AtomicInteger();
		assertThrows(IllegalStateException.class, () -> guard.insert(() -> {
			throw new IllegalStateException("insert down");
		}));
		guard.insert(() -> inserts.incrementAndGet());
		assertEquals(1, inserts.get());

		AtomicInteger reports = new AtomicInteger();
		assertThrows(IllegalStateException.class, () -> guard.reportUntilAck(() -> {
			reports.incrementAndGet();
			throw new IllegalStateException("no ack");
		}));
		assertEquals(3, reports.get());
	}

	@Test
	void runtimeFallbackIsRethrownAndBreakerExplosionsAreShed() {
		DownstreamGuard rethrow = new DownstreamGuard(factory(new CircuitBreaker() {
			@Override
			public <T> T run(java.util.function.Supplier<T> toRun, Function<Throwable, T> fallback) {
				return fallback.apply(new IllegalStateException("insert down"));
			}
		}));
		IllegalStateException error = assertThrows(IllegalStateException.class, () -> rethrow.insert(() -> {
		}));
		assertEquals("insert down", error.getMessage());

		DownstreamGuard exploding = new DownstreamGuard(factory(new CircuitBreaker() {
			@Override
			public <T> T run(java.util.function.Supplier<T> toRun, Function<Throwable, T> fallback) {
				throw new IllegalStateException("breaker blew up");
			}
		}));
		exploding.export(() -> {
		});
	}

	private static CircuitBreakerFactory<Object, ConfigBuilder<Object>> factory(CircuitBreaker breaker) {
		return new CircuitBreakerFactory<>() {
			@Override
			public void configureDefault(Function<String, Object> defaultConfiguration) {
			}

			@Override
			protected ConfigBuilder<Object> configBuilder(String id) {
				return null;
			}

			@Override
			public CircuitBreaker create(String id) {
				return breaker;
			}
		};
	}

	private static DownstreamGuard guard() {
		return TestBreakers.guard();
	}

}
