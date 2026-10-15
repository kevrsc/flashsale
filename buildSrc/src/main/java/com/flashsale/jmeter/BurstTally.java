package com.flashsale.jmeter;

import java.util.Set;
import java.util.concurrent.Callable;

public final class BurstTally {
	public static final long ORDER_WAIT_MS = 120_000L;
	static final long POLL_MS = 500L;

	private BurstTally() {
	}

	public static Outcome await(Callable<Set<String>> readOrders, Set<String> ids, boolean qualifiedOk,
			Clock clock, Sleep sleep) throws InterruptedException {
		long deadline = clock.millis() + ORDER_WAIT_MS;
		Exception lastError = null;
		int orders = 0;
		while (true) {
			boolean matched = false;
			try {
				Set<String> orderIds = readOrders.call();
				lastError = null;
				orders = orderIds.size();
				matched = qualifiedOk && orders == 100 && orderIds.equals(ids);
			} catch (Exception ex) {
				lastError = ex;
				matched = false;
			}
			if (matched || clock.millis() >= deadline) {
				return new Outcome(matched, lastError, orders);
			}
			sleep.sleep(POLL_MS);
		}
	}

	public record Outcome(boolean matched, Exception error, int orders) {
	}

	@FunctionalInterface
	public interface Clock {
		long millis();
	}

	@FunctionalInterface
	public interface Sleep {
		void sleep(long millis) throws InterruptedException;
	}
}
