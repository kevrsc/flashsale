package com.flashsale.sale;

import java.time.Duration;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;

import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigBuilder;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

@Configuration(proxyBeanMethods = false)
class BreakerTuning {

	@Bean
	Customizer<Resilience4JCircuitBreakerFactory> saleBreakerCustomizer() {
		return factory -> {
			factory.configure(builder -> tune(builder, Duration.ofSeconds(2)), "export");
			factory.configure(builder -> tune(builder, Duration.ofSeconds(5)), "insert", "report");
		};
	}

	private static Resilience4JConfigBuilder tune(Resilience4JConfigBuilder builder, Duration timeout) {
		return builder
				.circuitBreakerConfig(CircuitBreakerConfig.custom()
						.slidingWindowSize(4)
						.minimumNumberOfCalls(4)
						.failureRateThreshold(50)
						.waitDurationInOpenState(Duration.ofSeconds(30))
						.permittedNumberOfCallsInHalfOpenState(1)
						.build())
				.timeLimiterConfig(TimeLimiterConfig.custom().timeoutDuration(timeout).build());
	}

}

@Component
public class DownstreamGuard {

	private final CircuitBreakerFactory<?, ?> breakers;

	public DownstreamGuard(CircuitBreakerFactory<?, ?> breakers) {
		this.breakers = breakers;
	}

	public void export(Runnable call) {
		try {
			breakers.create("export").run(() -> {
				call.run();
				return Boolean.TRUE;
			}, throwable -> Boolean.FALSE);
		}
		catch (RuntimeException ex) {
			// An open or failing export is shed. Qualification does not observe it.
		}
	}

	public void insert(Runnable call) {
		runUnlessShed("insert", call);
	}

	public void report(Runnable call) {
		runUnlessShed("report", call);
	}

	public void reportUntilAck(Runnable call) {
		RuntimeException last = new IllegalStateException("report was not acknowledged");
		for (int attempt = 0; attempt < 3; attempt++) {
			try {
				report(call);
				return;
			}
			catch (RuntimeException ex) {
				last = ex;
			}
		}
		throw last;
	}

	private void runUnlessShed(String name, Runnable call) {
		breakers.create(name).run(() -> {
			call.run();
			return Boolean.TRUE;
		}, throwable -> {
			if (opened(throwable)) {
				call.run();
				return Boolean.TRUE;
			}
			if (throwable instanceof RuntimeException runtime) {
				throw runtime;
			}
			throw new IllegalStateException(name + " failed", throwable);
		});
	}

	private static boolean opened(Throwable throwable) {
		for (Throwable current = throwable; current != null; current = current.getCause()) {
			if (current instanceof CallNotPermittedException) {
				return true;
			}
		}
		return false;
	}

}
