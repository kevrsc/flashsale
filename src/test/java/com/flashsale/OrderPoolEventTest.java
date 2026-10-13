package com.flashsale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.flashsale.inventory.Admission;
import com.flashsale.inventory.Attempt;
import com.flashsale.inventory.OrderOutcome;
import com.flashsale.inventory.Orders;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class OrderPoolEventTest {

	private static final String TOPIC = "inventory.order-accepted";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private DataSource dataSource;

	@Autowired
	private Admission admission;

	@Autowired
	private Orders orders;

	@Autowired
	private MeterRegistry registry;

	@Autowired
	private ApplicationContext context;

	@BeforeEach
	void resetQuantity() {
		jdbc.update("UPDATE item SET quantity = 10 WHERE id = 'item-1'");
		orders.clearSoldOutHint();
		jdbc.update("DELETE FROM customer");
	}

	@Test
	void oneOrderReducesThePoolAndSendsTheEvent() throws Exception {
		String userId = "user-" + UUID.randomUUID();
		try (KafkaConsumer<String, String> consumer = consumer()) {
			seekToEnd(consumer);
			String body = order(userId, 1);

			assertTrue(body.contains("The order is in process"));
			assertFalse(body.contains("The order was not accepted"));
			assertFalse(body.toLowerCase(Locale.ROOT).contains("sold out"));
			assertFalse(body.contains("name=\"itemId\""));
			assertFalse(body.contains("item-1"));
			assertEquals(9, quantity());
			assertEquals(1, customerRows());
			assertEquals(1, unitsOrdered(userId));
			assertNextAllowedAboutOneHourOut(userId);

			assertOneEvent(consumer, userId, 1);
		}
	}

	@Test
	void orderEventCarriesTheRequestTrace() throws Exception {
		String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
		String userId = "user-" + UUID.randomUUID();
		try (KafkaConsumer<String, String> consumer = consumer()) {
			seekToEnd(consumer);
			String body = mockMvc.perform(post("/")
					.header("traceparent", "00-" + traceId + "-00f067aa0ba902b7-01")
					.param("userId", userId)
					.param("quantity", "1"))
					.andExpect(status().isOk())
					.andReturn()
					.getResponse()
					.getContentAsString();

			assertTrue(body.contains("The order is in process"));
			List<ConsumerRecord<String, String>> matched = poll(consumer, Duration.ofSeconds(20), userId).stream()
					.filter(record -> record.value().contains(userId))
					.toList();
			assertEquals(1, matched.size());

			ConsumerRecord<String, String> record = matched.get(0);
			Map<String, Object> event = JsonParserFactory.getJsonParser().parseMap(record.value());
			assertEquals(Set.of("orderId", "userId", "itemId", "unitsDeducted"), event.keySet());
			assertEquals(userId, event.get("userId"));
			assertEquals("item-1", event.get("itemId"));
			assertEquals(1, ((Number) event.get("unitsDeducted")).intValue());
			assertEquals(event.get("orderId"), record.key());

			Header header = record.headers().lastHeader("traceparent");
			assertNotNull(header);
			String traceparent = new String(header.value(), StandardCharsets.UTF_8);
			assertTrue(traceparent.startsWith("00-" + traceId + "-"), traceparent);
		}
	}

	@Test
	void attemptPastTheBoundOpensNoTransactionAndIsNotSoldOut() throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(4);
		try (Connection lock = dataSource.getConnection()) {
			lock.setAutoCommit(false);
			try (Statement statement = lock.createStatement()) {
				statement.execute("SELECT quantity FROM item WHERE id = 'item-1' FOR UPDATE");
			}
			try {
				Future<String> holderOne = pool.submit(this::postOrder);
				Future<String> holderTwo = pool.submit(this::postOrder);
				awaitItemUpdates(2);

				Future<Attempt> refused = pool.submit(() -> admission.admit("probe-" + UUID.randomUUID(), 1));
				assertInstanceOf(Attempt.TurnedAway.class, refused.get(2, TimeUnit.SECONDS));
				assertEquals(2, itemUpdatesInFlight(), activeQueries());

				Future<String> extra = pool.submit(this::postOrder);
				long deadline = System.nanoTime() + Duration.ofMillis(400).toNanos();
				while (System.nanoTime() < deadline) {
					assertEquals(2, itemUpdatesInFlight(), activeQueries());
					assertFalse(extra.isDone());
					Thread.sleep(20);
				}

				lock.rollback();
				String body = extra.get(20, TimeUnit.SECONDS);
				assertTrue(body.contains("The order is in process"));
				assertFalse(body.toLowerCase(Locale.ROOT).contains("sold out"));
				assertTrue(holderOne.get(20, TimeUnit.SECONDS).contains("The order is in process"));
				assertTrue(holderTwo.get(20, TimeUnit.SECONDS).contains("The order is in process"));
				assertEquals(7, quantity());
				assertEquals(3, customerRows());
				assertEquals(3, unitsOrderedTotal());
			} finally {
				lock.rollback();
			}
		} finally {
			pool.shutdown();
			if (!pool.awaitTermination(30, TimeUnit.SECONDS)) {
				pool.shutdownNow();
			}
		}
	}

	@Test
	void zeroRowUpdateLeavesThePoolAndSendsNothing() throws Exception {
		jdbc.update("UPDATE item SET quantity = 0 WHERE id = 'item-1'");
		int outboxBefore = outboxRows();
		String userId = "user-" + UUID.randomUUID();
		try (KafkaConsumer<String, String> consumer = consumer()) {
			seekToEnd(consumer);
			String body = order(userId, 1);

			assertTrue(body.contains("The item has sold out"));
			assertFalse(body.contains("in process"));
			assertFalse(body.contains("Wait one hour before ordering again"));
			assertFalse(body.contains("The order is refused"));
			assertEquals(0, quantity());
			assertEquals(0, customerRows());
			assertEquals(outboxBefore, outboxRows());

			assertNothingPublished(consumer, userId, outboxBefore);
		}
	}

	@Test
	void emptyQuantityDuringTheWaitSaysSoldOut() throws Exception {
		String userId = "user-" + UUID.randomUUID();
		try (KafkaConsumer<String, String> consumer = consumer()) {
			seekToEnd(consumer);
			String first = order(userId, 1);
			assertTrue(first.contains("The order is in process"));
			assertOneEvent(consumer, userId, 1);
			String deadline = nextAllowedText(userId);
			int units = unitsOrdered(userId);
			jdbc.update("UPDATE item SET quantity = 0 WHERE id = 'item-1'");
			int outboxBefore = outboxRows();
			String body = order(userId, 1);

			assertTrue(body.contains("The item has sold out"));
			assertFalse(body.contains("Wait one hour before ordering again"));
			assertEquals(0, quantity());
			assertEquals(units, unitsOrdered(userId));
			assertEquals(deadline, nextAllowedText(userId));
			assertEquals(1, customerRows());
			assertNothingPublished(consumer, userId, outboxBefore);
		}
	}

	@Test
	void laterAttemptAnswersFromTheHintWithoutTheItemLock() throws Exception {
		jdbc.update("UPDATE item SET quantity = 0 WHERE id = 'item-1'");
		String first = order("user-" + UUID.randomUUID(), 1);
		assertTrue(first.contains("The item has sold out"));
		int outboxBefore = outboxRows();

		ExecutorService pool = Executors.newSingleThreadExecutor();
		try (Connection lock = dataSource.getConnection()) {
			lock.setAutoCommit(false);
			try (Statement statement = lock.createStatement()) {
				statement.execute("SELECT quantity FROM item WHERE id = 'item-1' FOR UPDATE");
			}
			try {
				Future<String> hinted = pool.submit(() -> order("user-" + UUID.randomUUID(), 1));
				String body = hinted.get(2, TimeUnit.SECONDS);
				assertTrue(body.contains("The item has sold out"));
				assertEquals(0, quantity());
				assertEquals(0, customerRows());
				assertEquals(outboxBefore, outboxRows());
			} finally {
				lock.rollback();
			}
		} finally {
			pool.shutdown();
			if (!pool.awaitTermination(30, TimeUnit.SECONDS)) {
				pool.shutdownNow();
			}
		}
	}

	@Test
	void soldOutHintAnswersFromThePageWhileTheBoundIsFull() throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(3);
		try (Connection lock = dataSource.getConnection()) {
			lock.setAutoCommit(false);
			try (Statement statement = lock.createStatement()) {
				statement.execute("SELECT quantity FROM item WHERE id = 'item-1' FOR UPDATE");
			}
			try {
				Future<String> holderOne = pool.submit(this::postOrder);
				Future<String> holderTwo = pool.submit(this::postOrder);
				awaitItemUpdates(2);
				// Admit will not take a permit once the hint is set, so the bound is filled first.
				setSoldOutHint();

				Future<String> hinted = pool.submit(() -> order("user-" + UUID.randomUUID(), 1));
				String body = hinted.get(2, TimeUnit.SECONDS);
				assertTrue(body.contains("The item has sold out"));
				assertFalse(body.contains("Wait one hour before ordering again"));
				assertEquals(2, itemUpdatesInFlight(), activeQueries());

				lock.rollback();
				assertTrue(holderOne.get(20, TimeUnit.SECONDS).contains("The order is in process"));
				assertTrue(holderTwo.get(20, TimeUnit.SECONDS).contains("The order is in process"));
				assertEquals(8, quantity());
				assertEquals(2, customerRows());
				assertTrue(orders.soldOutHint());
			} finally {
				lock.rollback();
			}
		} finally {
			pool.shutdown();
			if (!pool.awaitTermination(30, TimeUnit.SECONDS)) {
				pool.shutdownNow();
			}
		}
	}

	@Test
	void repeatDuringTheWaitDeductsNothingAndSendsNoEvent() throws Exception {
		String userId = "user-" + UUID.randomUUID();
		try (KafkaConsumer<String, String> consumer = consumer()) {
			seekToEnd(consumer);
			String first = order(userId, 1);
			assertTrue(first.contains("The order is in process"));
			assertOneEvent(consumer, userId, 1);

			String more = order(userId, 2);
			assertTrue(more.contains("The order is in process"));
			assertFalse(more.contains("Wait one hour before ordering again"));
			assertEquals(3, unitsOrdered(userId));
			assertEquals(7, quantity());
			assertOneEvent(consumer, userId, 2);

			int pool = quantity();
			String deadline = nextAllowedText(userId);
			int outboxBefore = outboxRows();
			String body = order(userId, 1);

			assertTrue(body.contains("Wait one hour before ordering again"));
			assertFalse(body.contains("The order is in process"));
			assertEquals(pool, quantity());
			assertEquals(3, unitsOrdered(userId));
			assertEquals(deadline, nextAllowedText(userId));
			assertEquals(1, customerRows());
			assertNothingPublished(consumer, userId, outboxBefore);
		}
	}

	@Test
	void oneOrderMeetsTheLimitOf3() throws Exception {
		String userId = "user-" + UUID.randomUUID();
		try (KafkaConsumer<String, String> consumer = consumer()) {
			seekToEnd(consumer);
			String body = order(userId, 3);

			assertTrue(body.contains("The order is in process"));
			assertEquals(3, unitsOrdered(userId));
			assertEquals(7, quantity());
			assertEquals(1, customerRows());
			assertNextAllowedAboutOneHourOut(userId);
			assertOneEvent(consumer, userId, 3);
		}
	}

	@Test
	void laterOrderMeetsTheLimitOf3() throws Exception {
		String userId = "user-" + UUID.randomUUID();
		try (KafkaConsumer<String, String> consumer = consumer()) {
			seekToEnd(consumer);
			String first = order(userId, 1);
			assertTrue(first.contains("The order is in process"));
			assertOneEvent(consumer, userId, 1);
			elapseTheHour(userId);
			int outboxBefore = outboxRows();
			String body = order(userId, 2);

			assertTrue(body.contains("The order is in process"));
			assertEquals(3, unitsOrdered(userId));
			assertEquals(7, quantity());
			assertEquals(1, customerRows());
			assertNextAllowedAboutOneHourOut(userId);
			assertOneEvent(consumer, userId, 2);
			assertEquals(outboxBefore + 1, outboxRows());
		}
	}

	@Test
	void noneLeftAfterTheHourIsRefused() throws Exception {
		String userId = "user-" + UUID.randomUUID();
		try (KafkaConsumer<String, String> consumer = consumer()) {
			seekToEnd(consumer);
			String first = order(userId, 3);
			assertTrue(first.contains("The order is in process"));
			assertOneEvent(consumer, userId, 3);
			int pool = quantity();
			elapseTheHour(userId);
			int outboxBefore = outboxRows();
			String body = order(userId, 1);

			assertTrue(body.contains("The order is refused"));
			assertFalse(body.contains("The order is in process"));
			assertFalse(body.contains("Wait one hour before ordering again"));
			assertEquals(pool, quantity());
			assertEquals(3, unitsOrdered(userId));
			assertNextAllowedInThePast(userId);
			assertEquals(1, customerRows());
			assertNothingPublished(consumer, userId, outboxBefore);
		}
	}

	@Test
	void otherCustomerOrdersWhileTheFirstWaits() throws Exception {
		String firstUser = "user-" + UUID.randomUUID();
		String secondUser = "user-" + UUID.randomUUID();
		String first = order(firstUser, 1);
		assertTrue(first.contains("The order is in process"));
		String deadline = nextAllowedText(firstUser);
		int firstUnits = unitsOrdered(firstUser);
		try (KafkaConsumer<String, String> consumer = consumer()) {
			seekToEnd(consumer);
			String body = order(secondUser, 1);

			assertTrue(body.contains("The order is in process"));
			assertEquals(deadline, nextAllowedText(firstUser));
			assertEquals(firstUnits, unitsOrdered(firstUser));
			assertEquals(1, unitsOrdered(secondUser));
			assertNextAllowedAboutOneHourOut(secondUser);
			assertEquals(8, quantity());
			assertEquals(2, customerRows());
			assertOneEvent(consumer, secondUser, 1);
		}
	}

	@Test
	void shortQuantityKeepsThatQuantity() throws Exception {
		jdbc.update("UPDATE item SET quantity = 2 WHERE id = 'item-1'");
		String userId = "user-" + UUID.randomUUID();
		try (KafkaConsumer<String, String> consumer = consumer()) {
			seekToEnd(consumer);
			String body = order(userId, 3);

			assertTrue(body.contains("The order is in process"));
			assertEquals(0, quantity());
			assertEquals(2, unitsOrdered(userId));
			assertEquals(1, customerRows());
			assertNextAllowedAboutOneHourOut(userId);
			assertOneEvent(consumer, userId, 2);
		}
	}

	@Test
	void shortLimitAfterTheHourKeepsTheUnitsLeft() throws Exception {
		String userId = "user-" + UUID.randomUUID();
		try (KafkaConsumer<String, String> consumer = consumer()) {
			seekToEnd(consumer);
			String first = order(userId, 2);
			assertTrue(first.contains("The order is in process"));
			assertOneEvent(consumer, userId, 2);
			int pool = quantity();
			elapseTheHour(userId);
			String body = order(userId, 2);

			assertTrue(body.contains("The order is in process"));
			assertEquals(3, unitsOrdered(userId));
			assertEquals(pool - 1, quantity());
			assertEquals(1, customerRows());
			assertNextAllowedAboutOneHourOut(userId);
			assertOneEvent(consumer, userId, 1);
		}
	}

	@Test
	void bothShortKeepsTheSmallerOfQuantityAndUnitsLeft() throws Exception {
		String userId = "user-" + UUID.randomUUID();
		try (KafkaConsumer<String, String> consumer = consumer()) {
			seekToEnd(consumer);
			String first = order(userId, 2);
			assertTrue(first.contains("The order is in process"));
			assertOneEvent(consumer, userId, 2);
			elapseTheHour(userId);
			jdbc.update("UPDATE item SET quantity = 2 WHERE id = 'item-1'");
			String body = order(userId, 4);

			assertTrue(body.contains("The order is in process"));
			assertEquals(1, quantity());
			assertEquals(3, unitsOrdered(userId));
			assertEquals(1, customerRows());
			assertNextAllowedAboutOneHourOut(userId);
			assertOneEvent(consumer, userId, 1);
		}
	}

	@Test
	void emptyUserIdIsNotAcceptedAndDoesNotWaitOnTheItemLock() throws Exception {
		int pool = quantity();
		int outboxBefore = outboxRows();
		ExecutorService poolExecutor = Executors.newSingleThreadExecutor();
		try (Connection lock = dataSource.getConnection()) {
			lock.setAutoCommit(false);
			try (Statement statement = lock.createStatement()) {
				statement.execute("SELECT quantity FROM item WHERE id = 'item-1' FOR UPDATE");
			}
			try {
				Future<String> posted = poolExecutor.submit(() -> order("", 1));
				String body = posted.get(2, TimeUnit.SECONDS);

				assertTrue(body.contains("The order was not accepted"));
				assertFalse(body.contains("The order is in process"));
				assertEquals(pool, quantity());
				assertEquals(0, customerRows());
				assertEquals(outboxBefore, outboxRows());
				assertFalse(orders.soldOutHint());
			} finally {
				lock.rollback();
			}
		} finally {
			poolExecutor.shutdown();
			if (!poolExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
				poolExecutor.shutdownNow();
			}
		}
	}

	@Test
	void quantityOfZeroIsNotAccepted() throws Exception {
		int pool = quantity();
		int outboxBefore = outboxRows();
		String userId = "user-" + UUID.randomUUID();
		try (KafkaConsumer<String, String> consumer = consumer()) {
			seekToEnd(consumer);
			String body = order(userId, 0);

			assertTrue(body.contains("The order was not accepted"));
			assertFalse(body.contains("The order is in process"));
			assertEquals(pool, quantity());
			assertEquals(0, customerRows());
			assertFalse(orders.soldOutHint());
			assertNothingPublished(consumer, userId, outboxBefore);
		}
	}

	@Test
	void successCountsTheAttemptAndIncludesPublisherStatements() throws Exception {
		String userId = "user-" + UUID.randomUUID();
		long inProcess = attempts("in_process");
		long queries = statements("query");
		long updates = statements("update");
		try (KafkaConsumer<String, String> consumer = consumer()) {
			seekToEnd(consumer);
			String body = order(userId, 1);

			assertTrue(body.contains("The order is in process"));
			assertEquals(inProcess + 1, attempts("in_process"));
			long orderQueries = statements("query");
			long orderUpdates = statements("update");
			assertTrue(orderQueries > queries, orderQueries + " queries from " + queries);
			assertTrue(orderUpdates > updates, orderUpdates + " updates from " + updates);

			assertOneEvent(consumer, userId, 1);
			awaitPublisherBeyond(orderQueries, orderUpdates);
		}
	}

	@Test
	void waitCountsTheAttemptAndDatabaseStatements() throws Exception {
		String userId = "user-" + UUID.randomUUID();
		String first = order(userId, 3);
		assertTrue(first.contains("The order is in process"));
		long waiting = attempts("wait");
		long inProcess = attempts("in_process");
		long queries = statements("query");
		long updates = statements("update");
		String body = order(userId, 1);

		assertTrue(body.contains("Wait one hour before ordering again"));
		assertEquals(waiting + 1, attempts("wait"));
		assertEquals(inProcess, attempts("in_process"));
		assertStatementsRose(queries, updates);
	}

	@Test
	void refusalCountsTheAttemptAndDatabaseStatements() throws Exception {
		String userId = "user-" + UUID.randomUUID();
		String first = order(userId, 3);
		assertTrue(first.contains("The order is in process"));
		elapseTheHour(userId);
		long refused = attempts("refused");
		long inProcess = attempts("in_process");
		long queries = statements("query");
		long updates = statements("update");
		String body = order(userId, 1);

		assertTrue(body.contains("The order is refused"));
		assertEquals(refused + 1, attempts("refused"));
		assertEquals(inProcess, attempts("in_process"));
		assertStatementsRose(queries, updates);
	}

	@Test
	void soldOutFromTheLockedReadCountsTheAttemptAndDatabaseStatements() throws Exception {
		jdbc.update("UPDATE item SET quantity = 0 WHERE id = 'item-1'");
		long soldOut = attempts("sold_out");
		long queries = statements("query");
		long updates = statements("update");
		String body = order("user-" + UUID.randomUUID(), 1);

		assertTrue(body.contains("The item has sold out"));
		assertEquals(soldOut + 1, attempts("sold_out"));
		assertStatementsRose(queries, updates);
	}

	@Test
	void soldOutFromTheHintCountsTheAttemptWithoutStatements() throws Exception {
		jdbc.update("UPDATE item SET quantity = 0 WHERE id = 'item-1'");
		String first = order("user-" + UUID.randomUUID(), 1);
		assertTrue(first.contains("The item has sold out"));
		long soldOut = attempts("sold_out");
		long inventory = inventoryStatementCount();
		String body = order("user-" + UUID.randomUUID(), 1);

		assertTrue(body.contains("The item has sold out"));
		assertEquals(soldOut + 1, attempts("sold_out"));
		assertEquals(inventory, inventoryStatementCount());
	}

	@Test
	void notAcceptedCountsTheAttemptWithoutStatements() throws Exception {
		long notAccepted = attempts("not_accepted");
		long inventory = inventoryStatementCount();
		String blank = order("", 1);
		assertTrue(blank.contains("The order was not accepted"));
		assertEquals(notAccepted + 1, attempts("not_accepted"));
		assertEquals(inventory, inventoryStatementCount());

		notAccepted = attempts("not_accepted");
		inventory = inventoryStatementCount();
		String zero = order("user-" + UUID.randomUUID(), 0);
		assertTrue(zero.contains("The order was not accepted"));
		assertEquals(notAccepted + 1, attempts("not_accepted"));
		assertEquals(inventory, inventoryStatementCount());
	}

	@Test
	void turnAwayCountsTheAttemptWithoutStatements() throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try (Connection lock = dataSource.getConnection()) {
			lock.setAutoCommit(false);
			try (Statement statement = lock.createStatement()) {
				statement.execute("SELECT quantity FROM item WHERE id = 'item-1' FOR UPDATE");
			}
			try {
				Future<String> holderOne = pool.submit(this::postOrder);
				Future<String> holderTwo = pool.submit(this::postOrder);
				awaitItemUpdates(2);
				long turnedAway = attempts("turned_away");
				long inventory = inventoryStatementCount();
				assertInstanceOf(Attempt.TurnedAway.class, admission.admit("probe-" + UUID.randomUUID(), 1));
				assertInstanceOf(Attempt.TurnedAway.class, admission.admit("probe-" + UUID.randomUUID(), 1));
				assertEquals(turnedAway + 2, attempts("turned_away"));
				assertEquals(inventory, inventoryStatementCount());
				lock.rollback();
				assertTrue(holderOne.get(20, TimeUnit.SECONDS).contains("The order is in process"));
				assertTrue(holderTwo.get(20, TimeUnit.SECONDS).contains("The order is in process"));
			} finally {
				lock.rollback();
			}
		} finally {
			pool.shutdown();
			if (!pool.awaitTermination(30, TimeUnit.SECONDS)) {
				pool.shutdownNow();
			}
		}
	}

	@Test
	void openingThePageLeavesTheCountersUnchanged() throws Exception {
		long attemptsBefore = attempts();
		long inventory = inventoryStatementCount();
		String body = mockMvc.perform(get("/")).andExpect(status().isOk()).andReturn().getResponse()
				.getContentAsString();
		assertEquals(attemptsBefore, attempts());
		assertEquals(inventory, inventoryStatementCount());
		assertTrue(body.contains("method=\"post\""));
		assertTrue(body.contains("action=\"/\""));
		assertTrue(body.contains("name=\"userId\""));
		assertTrue(body.contains("name=\"quantity\""));
		assertFalse(body.contains("The order is in process"));
		assertFalse(body.contains("Wait one hour before ordering again"));
		assertFalse(body.contains("The item has sold out"));
		assertFalse(body.contains("The order is refused"));
		assertFalse(body.contains("The order was not accepted"));
		assertFalse(body.contains("name=\"itemId\""));
		assertFalse(body.contains("item-1"));
	}

	@Test
	void attemptResultsIgnoreThePageAndCountRejectionAcceptanceAndWait() throws Exception {
		long attemptsBefore = attempts();
		long inProcess = attempts("in_process");
		long notAccepted = attempts("not_accepted");
		long waiting = attempts("wait");

		mockMvc.perform(get("/")).andExpect(status().isOk());
		assertEquals(attemptsBefore, attempts());

		String rejected = order("", 1);
		assertTrue(rejected.contains("The order was not accepted"));
		assertEquals(notAccepted + 1, attempts("not_accepted"));
		assertEquals(inProcess, attempts("in_process"));

		String userId = "user-" + UUID.randomUUID();
		String accepted = order(userId, 3);
		assertTrue(accepted.contains("The order is in process"));
		assertEquals(inProcess + 1, attempts("in_process"));

		String waitingBody = order(userId, 1);
		assertTrue(waitingBody.contains("Wait one hour before ordering again"));
		assertEquals(waiting + 1, attempts("wait"));
		assertEquals(inProcess + 1, attempts("in_process"));
	}

	@Test
	void unmatchedCountsOnceAndIsNotInProcess() throws Exception {
		Orders stub = new Orders(null, null) {
			@Override
			public boolean soldOutHint() {
				return false;
			}

			@Override
			public OrderOutcome place(String userId, int units) {
				return OrderOutcome.unmatched();
			}
		};
		Admission target = AopTestUtils.getUltimateTargetObject(admission);
		Field ordersField = Admission.class.getDeclaredField("orders");
		ordersField.setAccessible(true);
		Object previous = ordersField.get(target);
		ordersField.set(target, stub);
		try {
			long inProcess = attempts("in_process");
			long unmatched = attempts("unmatched");
			long all = attempts();
			Attempt attempt = admission.admit("user-" + UUID.randomUUID(), 1);
			Attempt.Shopper shopper = assertInstanceOf(Attempt.Shopper.class, attempt);
			assertEquals(OrderOutcome.unmatched(), shopper.outcome());
			assertEquals(inProcess, attempts("in_process"));
			assertEquals(unmatched + 1, attempts("unmatched"));
			assertEquals(all + 1, attempts());
		} finally {
			ordersField.set(target, previous);
		}
	}

	@Test
	void negativeQuantityIsNotAccepted() throws Exception {
		int pool = quantity();
		int outboxBefore = outboxRows();
		String userId = "user-" + UUID.randomUUID();
		try (KafkaConsumer<String, String> consumer = consumer()) {
			seekToEnd(consumer);
			String body = order(userId, -1);

			assertTrue(body.contains("The order was not accepted"));
			assertFalse(body.contains("The order is in process"));
			assertEquals(pool, quantity());
			assertEquals(0, customerRows());
			assertFalse(orders.soldOutHint());
			assertNothingPublished(consumer, userId, outboxBefore);
		}
	}

	@Test
	void theSaleChecksRunEndToEnd() throws Exception {
		String alex = "user-" + UUID.randomUUID();
		String second = "user-" + UUID.randomUUID();
		String shortPool = "user-" + UUID.randomUUID();
		long attemptsBefore = attempts();
		try (KafkaConsumer<String, String> consumer = consumer()) {
			seekToEnd(consumer);

			long queriesBeforeFirst = statements("query");
			long updatesBeforeFirst = statements("update");
			String first = order(alex, 1);
			assertTrue(first.contains("The order is in process"));
			assertEquals(9, quantity());
			assertOneEvent(consumer, alex, 1);
			assertStatementsRose(queriesBeforeFirst, updatesBeforeFirst);

			String secondUnit = order(alex, 1);
			assertTrue(secondUnit.contains("The order is in process"));
			assertEquals(8, quantity());
			assertEquals(2, unitsOrdered(alex));
			assertOneEvent(consumer, alex, 1);

			String thirdUnit = order(alex, 1);
			assertTrue(thirdUnit.contains("The order is in process"));
			assertEquals(7, quantity());
			assertEquals(3, unitsOrdered(alex));
			assertOneEvent(consumer, alex, 1);

			int outboxBeforeWait = outboxRows();
			String waiting = order(alex, 1);
			assertTrue(waiting.contains("Wait one hour before ordering again"));
			assertEquals(7, quantity());
			assertEquals(3, unitsOrdered(alex));
			assertNothingPublished(consumer, alex, outboxBeforeWait);

			elapseTheHour(alex);
			int poolAtLimit = quantity();
			int outboxBeforeRefusal = outboxRows();
			String refused = order(alex, 1);
			assertTrue(refused.contains("The order is refused"));
			assertEquals(poolAtLimit, quantity());
			assertEquals(3, unitsOrdered(alex));
			assertNothingPublished(consumer, alex, outboxBeforeRefusal);

			String met = order(second, 3);
			assertTrue(met.contains("The order is in process"));
			assertEquals(3, unitsOrdered(second));
			assertEquals(4, quantity());
			assertOneEvent(consumer, second, 3);

			jdbc.update("UPDATE item SET quantity = 1 WHERE id = 'item-1'");
			String subset = order(shortPool, 3);
			assertTrue(subset.contains("The order is in process"));
			assertEquals(0, quantity());
			assertEquals(1, unitsOrdered(shortPool));
			assertOneEvent(consumer, shortPool, 1);

			int outboxBeforeSoldOut = outboxRows();
			String soldOut = order(shortPool, 1);
			assertTrue(soldOut.contains("The item has sold out"));
			assertFalse(soldOut.contains("Wait one hour before ordering again"));
			assertEquals(0, quantity());
			assertEquals(1, unitsOrdered(shortPool));
			assertNothingPublished(consumer, shortPool, outboxBeforeSoldOut);

			long notAccepted = attempts("not_accepted");
			int quantityBeforeBlank = quantity();
			int outboxBeforeBlank = outboxRows();
			long inventory = inventoryStatementCount();
			String blank = order("", 1);
			assertTrue(blank.contains("The order was not accepted"));
			assertEquals(notAccepted + 1, attempts("not_accepted"));
			assertEquals(inventory, inventoryStatementCount());
			assertEquals(quantityBeforeBlank, quantity());
			assertNothingPublished(consumer, "\"userId\":\"\"", outboxBeforeBlank);

			assertTrue(attempts() > attemptsBefore);
			assertEquals(0, count("SELECT count(*) FROM customer WHERE units_ordered > 3"));
		}
	}

	@Test
	void unmatchedOutcomeReachesThePage() throws Exception {
		Orders stub = new Orders(null, null) {
			@Override
			public boolean soldOutHint() {
				return false;
			}

			@Override
			public OrderOutcome place(String userId, int units) {
				return OrderOutcome.unmatched();
			}
		};
		Admission target = AopTestUtils.getUltimateTargetObject(admission);
		Field ordersField = Admission.class.getDeclaredField("orders");
		ordersField.setAccessible(true);
		Object previous = ordersField.get(target);
		ordersField.set(target, stub);
		try {
			String body = order("user-" + UUID.randomUUID(), 1);
			assertFalse(body.contains("id=\"outcome\""));
		}
		finally {
			ordersField.set(target, previous);
		}
	}

	@Test
	void zeroUnitsLeaveThePoolUnmatched() {
		String userId = "user-" + UUID.randomUUID();
		assertEquals(OrderOutcome.unmatched(), orders.place(userId, 0));
		assertEquals(10, quantity());
		assertEquals(0, customerRows());
	}

	@Test
	void aMissedItemUpdateRereadsTheLockedQuantity() throws Exception {
		Held sold = placeWithItemUpdate(1, 0, 1);
		assertEquals(OrderOutcome.soldOut(), sold.outcome());
		assertEquals(10, sold.quantity());
		Held sameQuantity = placeWithItemUpdate(1, 10, 1);
		assertEquals(OrderOutcome.unmatched(), sameQuantity.outcome());
		assertEquals(10, sameQuantity.quantity());
		Held placed = placeWithItemUpdate(1, 2, 3);
		assertEquals(OrderOutcome.inProcess(), placed.outcome());
		assertEquals(8, placed.quantity());
	}

	private record Held(OrderOutcome outcome, int quantity) {
	}

	private Held placeWithItemUpdate(int missedUpdates, int reread, int units) throws Exception {
		Orders target = AopTestUtils.getUltimateTargetObject(orders);
		Field jdbcField = Orders.class.getDeclaredField("jdbc");
		jdbcField.setAccessible(true);
		NamedParameterJdbcTemplate real = (NamedParameterJdbcTemplate) jdbcField.get(target);
		NamedParameterJdbcTemplate spyTemplate = spy(real);
		AtomicInteger missed = new AtomicInteger();
		doAnswer(invocation -> {
			String sql = invocation.getArgument(0);
			if (sql.contains("SET quantity = quantity") && missed.getAndIncrement() < missedUpdates) {
				return 0;
			}
			return invocation.callRealMethod();
		}).when(spyTemplate).update(anyString(), org.mockito.ArgumentMatchers.<Map<String, ?>>any());
		doAnswer(invocation -> {
			String sql = invocation.getArgument(0);
			if (sql.contains("SELECT quantity") && !sql.contains("FOR UPDATE")) {
				return reread;
			}
			return invocation.callRealMethod();
		}).when(spyTemplate).queryForObject(anyString(), org.mockito.ArgumentMatchers.<Map<String, ?>>any(), eq(Integer.class));
		jdbcField.set(target, spyTemplate);
		try {
			OrderOutcome outcome = orders.place("user-" + UUID.randomUUID(), units);
			return new Held(outcome, quantity());
		}
		finally {
			jdbcField.set(target, real);
			orders.clearSoldOutHint();
			jdbc.update("UPDATE item SET quantity = 10 WHERE id = 'item-1'");
			jdbc.update("DELETE FROM customer");
		}
	}

	private void setSoldOutHint() throws Exception {
		Orders target = AopTestUtils.getUltimateTargetObject(orders);
		var field = Orders.class.getDeclaredField("soldOutHint");
		field.setAccessible(true);
		((AtomicBoolean) field.get(target)).set(true);
	}

	private String postOrder() throws Exception {
		return order("user-" + UUID.randomUUID(), 1);
	}

	private String order(String userId, int quantity) throws Exception {
		return mockMvc.perform(post("/").param("userId", userId).param("quantity", Integer.toString(quantity)))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString();
	}

	private void elapseTheHour(String userId) {
		jdbc.update(
				"UPDATE customer SET next_allowed = clock_timestamp() - interval '1 second' WHERE user_id = ?",
				userId);
	}

	private void assertOneEvent(KafkaConsumer<String, String> consumer, String userId, int unitsDeducted) {
		List<ConsumerRecord<String, String>> records = poll(consumer, Duration.ofSeconds(8), userId);
		List<ConsumerRecord<String, String>> matched = records.stream()
				.filter(record -> record.value().contains(userId))
				.toList();
		assertEquals(1, matched.size());

		ConsumerRecord<String, String> record = matched.get(0);
		Map<String, Object> event = JsonParserFactory.getJsonParser().parseMap(record.value());
		assertEquals(Set.of("orderId", "userId", "itemId", "unitsDeducted"), event.keySet());
		assertEquals(userId, event.get("userId"));
		assertEquals("item-1", event.get("itemId"));
		assertEquals(unitsDeducted, ((Number) event.get("unitsDeducted")).intValue());
		assertEquals(event.get("orderId"), record.key());
	}

	private void assertNothingPublished(KafkaConsumer<String, String> consumer, String userId, int outboxBefore) {
		List<ConsumerRecord<String, String>> records = poll(consumer, Duration.ofSeconds(4), null);
		assertTrue(records.stream().noneMatch(record -> record.value().contains(userId)));
		assertEquals(outboxBefore, outboxRows());
	}

	private void awaitItemUpdates(int expected) throws InterruptedException {
		long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
		while (System.nanoTime() < deadline) {
			if (itemUpdatesInFlight() == expected) {
				return;
			}
			Thread.sleep(20);
		}
		assertEquals(expected, itemUpdatesInFlight(), activeQueries());
	}

	private int itemUpdatesInFlight() {
		return count("""
				SELECT count(*) FROM pg_stat_activity
				WHERE datname = current_database()
				  AND pid <> pg_backend_pid()
				  AND state = 'active'
				  AND query LIKE '%item%'
				  AND (query LIKE '%FOR UPDATE%' OR query LIKE '%UPDATE item%')
				""");
	}

	private String activeQueries() {
		return jdbc.query("""
				SELECT state || ' ' || left(query, 180)
				FROM pg_stat_activity
				WHERE datname = current_database()
				  AND pid <> pg_backend_pid()
				""", (rs, row) -> rs.getString(1)).toString();
	}

	private int quantity() {
		return jdbc.queryForObject("SELECT quantity FROM item WHERE id = 'item-1'", Integer.class);
	}

	private int unitsOrdered(String userId) {
		return jdbc.queryForObject(
				"SELECT units_ordered FROM customer WHERE user_id = ?",
				Integer.class,
				userId);
	}

	private int unitsOrderedTotal() {
		return count("SELECT coalesce(sum(units_ordered), 0) FROM customer");
	}

	private String nextAllowedText(String userId) {
		return jdbc.queryForObject(
				"SELECT to_char(next_allowed, 'YYYY-MM-DD HH24:MI:SS.US') FROM customer WHERE user_id = ?",
				String.class,
				userId);
	}

	private void assertNextAllowedAboutOneHourOut(String userId) {
		Boolean about = jdbc.queryForObject("""
				SELECT next_allowed > clock_timestamp() + interval '50 minutes'
				   AND next_allowed < clock_timestamp() + interval '70 minutes'
				FROM customer
				WHERE user_id = ?
				""", Boolean.class, userId);
		assertTrue(Boolean.TRUE.equals(about));
	}

	private void assertNextAllowedInThePast(String userId) {
		Boolean past = jdbc.queryForObject(
				"SELECT next_allowed < clock_timestamp() FROM customer WHERE user_id = ?",
				Boolean.class,
				userId);
		assertTrue(Boolean.TRUE.equals(past));
	}

	private int customerRows() {
		return count("SELECT count(*) FROM customer");
	}

	private int outboxRows() {
		return count("SELECT count(*) FROM outbox_record");
	}

	private int count(String sql) {
		return jdbc.queryForObject(sql, Number.class).intValue();
	}

	private KafkaConsumer<String, String> consumer() {
		Properties properties = new Properties();
		properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
		properties.put(ConsumerConfig.GROUP_ID_CONFIG, "order-pool-event-test");
		properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
		properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
		properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
		return new KafkaConsumer<>(properties);
	}

	private void seekToEnd(KafkaConsumer<String, String> consumer) {
		List<TopicPartition> partitions = consumer.partitionsFor(TOPIC).stream()
				.map(info -> new TopicPartition(TOPIC, info.partition()))
				.toList();
		consumer.assign(partitions);
		consumer.seekToEnd(partitions);
		consumer.poll(Duration.ofMillis(200));
	}

	private List<ConsumerRecord<String, String>> poll(KafkaConsumer<String, String> consumer, Duration budget,
			String stopWhenValueContains) {
		List<ConsumerRecord<String, String>> found = new ArrayList<>();
		long deadline = System.nanoTime() + budget.toNanos();
		while (System.nanoTime() < deadline) {
			Duration remaining = Duration.ofNanos(deadline - System.nanoTime());
			Duration slice = remaining.compareTo(Duration.ofMillis(200)) > 0 ? Duration.ofMillis(200) : remaining;
			consumer.poll(slice).forEach(found::add);
			if (stopWhenValueContains != null
					&& found.stream().anyMatch(record -> record.value().contains(stopWhenValueContains))) {
				break;
			}
		}
		return found;
	}

	private void assertStatementsRose(long queries, long updates) {
		assertTrue(statements("query") > queries, statements("query") + " queries from " + queries);
		assertTrue(statements("update") > updates, statements("update") + " updates from " + updates);
	}

	private void awaitPublisherBeyond(long queries, long updates) throws InterruptedException {
		long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
		while (System.nanoTime() < deadline) {
			if (statements("query") > queries || statements("update") > updates) {
				return;
			}
			Thread.sleep(50);
		}
		throw new AssertionError("publisher left sale.statements at query " + statements("query") + " and update "
				+ statements("update") + " after query " + queries + " and update " + updates);
	}

	private long inventoryStatementCount() {
		try {
			Class<?> type = Class.forName("com.flashsale.inventory.SaleTotals");
			Object totals = AopTestUtils.getUltimateTargetObject(context.getBean(type));
			Method count = type.getDeclaredMethod("inventoryStatementCount");
			count.setAccessible(true);
			return (long) count.invoke(totals);
		}
		catch (ReflectiveOperationException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private long attempts() {
		return registry.find("sale.attempts").counters().stream().mapToLong(counter -> (long) counter.count()).sum();
	}

	private long attempts(String result) {
		return count("sale.attempts", "result", result);
	}

	private long statements() {
		return registry.find("sale.statements").counters().stream().mapToLong(counter -> (long) counter.count()).sum();
	}

	private long statements(String kind) {
		return count("sale.statements", "kind", kind);
	}

	private long count(String name, String tag, String value) {
		Counter counter = registry.find(name).tag(tag, value).counter();
		return counter == null ? 0 : (long) counter.count();
	}

}
