package com.flashsale.jmeter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.flashsale.jmeter.BurstTally.Outcome;

class BurstTallyTest {
	@Test
	void missingOrderWaitsTwoMinutesThenFails() throws Exception {
		Set<String> ids = ids(100);
		Set<String> orders = new LinkedHashSet<>(ids);
		String missing = orders.iterator().next();
		orders.remove(missing);
		long[] slept = {0L};
		Outcome outcome = await(ids, true, () -> orders, slept);
		assertFalse(outcome.matched());
		assertNull(outcome.error());
		assertEquals(99, outcome.orders());
		assertEquals(BurstTally.ORDER_WAIT_MS, slept[0]);
	}

	@Test
	void extraOrderWaitsTwoMinutesThenFails() throws Exception {
		Set<String> ids = ids(100);
		Set<String> orders = new LinkedHashSet<>(ids);
		orders.add("extra-order");
		long[] slept = {0L};
		Outcome outcome = await(ids, true, () -> orders, slept);
		assertFalse(outcome.matched());
		assertEquals(101, outcome.orders());
		assertEquals(BurstTally.ORDER_WAIT_MS, slept[0]);
	}

	@Test
	void qualifiedCountOtherThan100WaitsTwoMinutesThenFails() throws Exception {
		Set<String> ids = ids(99);
		long[] slept = {0L};
		Outcome outcome = await(ids, false, () -> new LinkedHashSet<>(ids), slept);
		assertFalse(outcome.matched());
		assertEquals(BurstTally.ORDER_WAIT_MS, slept[0]);
	}

	@Test
	void matchingOrdersReturnWithoutWaiting() throws Exception {
		Set<String> ids = ids(100);
		long[] slept = {0L};
		Outcome outcome = await(ids, true, () -> new LinkedHashSet<>(ids), slept);
		assertTrue(outcome.matched());
		assertEquals(0L, slept[0]);
	}

	@Test
	void orderQueryFailureWaitsTwoMinutesThenFails() throws Exception {
		Set<String> ids = ids(100);
		long[] slept = {0L};
		Outcome outcome = await(ids, true, () -> {
			throw new IOException("down");
		}, slept);
		assertFalse(outcome.matched());
		assertEquals("down", outcome.error().getMessage());
		assertEquals(BurstTally.ORDER_WAIT_MS, slept[0]);
	}

	private static Outcome await(Set<String> ids, boolean qualifiedOk, java.util.concurrent.Callable<Set<String>> read,
			long[] slept) throws Exception {
		long[] now = {0L};
		return BurstTally.await(read, ids, qualifiedOk, () -> now[0], millis -> {
			slept[0] += millis;
			now[0] += millis;
		});
	}

	private static Set<String> ids(int count) {
		Set<String> ids = new LinkedHashSet<>();
		for (int index = 0; index < count; index++) {
			ids.add("id-" + index);
		}
		return ids;
	}
}
