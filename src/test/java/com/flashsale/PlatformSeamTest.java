package com.flashsale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.flashsale.inventory.Admission;
import com.flashsale.inventory.OrderOutcome;
import com.flashsale.inventory.Orders;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class PlatformSeamTest {

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

	@BeforeEach
	void resetPool() {
		restoreSeededItem();
	}

	@AfterEach
	void restoreSeededItem() {
		jdbc.update("DELETE FROM accepted_order");
		jdbc.update("UPDATE item SET id = 'item-1', quantity = 10");
		orders.clearSoldOutHint();
		jdbc.update("DELETE FROM customer");
	}

	@Test
	void openedPoolIsWhatTheStorefrontOrders() throws Exception {
		open("item-1", 0);
		assertTrue(orders.soldOutHint());
		long attempts = attempts();
		int outbox = outboxRows();

		Map<String, Object> opened = open("sku-9", 4);
		assertEquals(Set.of("itemId", "quantity"), opened.keySet());
		assertEquals("sku-9", opened.get("itemId"));
		assertEquals(4, number(opened.get("quantity")));
		assertEquals("sku-9", itemId());
		assertEquals(4, quantity());
		assertFalse(orders.soldOutHint());
		assertEquals(attempts, attempts());
		assertEquals(outbox, outboxRows());

		Map<String, Object> read = readSale();
		assertEquals("sku-9", read.get("itemId"));
		assertEquals(4, number(read.get("quantity")));
		assertEquals(attempts, attempts());

		String userId = "user-" + UUID.randomUUID();
		try (KafkaConsumer<String, String> consumer = consumer()) {
			seekToEnd(consumer);
			Map<String, Object> placed = placeOrder(userId, 1);
			assertEquals(Set.of("outcome", "result"), placed.keySet());
			assertEquals("The order is in process", placed.get("outcome"));
			assertEquals("in_process", placed.get("result"));
			assertEquals(3, quantity());
			assertEquals(1, unitsOrdered(userId));
			String orderId = assertOneEvent(consumer, userId, "sku-9", 1);
			assertAcceptance(orderId, userId, "sku-9", 1, false);
			assertEquals(1, acceptanceRows());

			Map<String, Object> left = readSale();
			assertEquals("sku-9", left.get("itemId"));
			assertEquals(3, number(left.get("quantity")));
		}
	}

	@Test
	void storefrontRepeatsTheFormSentences() throws Exception {
		String userId = "user-" + UUID.randomUUID();
		try (KafkaConsumer<String, String> consumer = consumer()) {
			seekToEnd(consumer);
			Map<String, Object> accepted = placeOrder(userId, 3);
			assertEquals("The order is in process", accepted.get("outcome"));
			assertEquals("in_process", accepted.get("result"));
			assertOneEvent(consumer, userId, "item-1", 3);

			assertSameSentence(userId, 1, "Wait one hour before ordering again", "wait");
			elapseTheHour(userId);
			assertSameSentence(userId, 1, "The order is refused", "refused");

			jdbc.update("UPDATE item SET quantity = 0 WHERE id = 'item-1'");
			assertSameSentence("user-" + UUID.randomUUID(), 1, "The item has sold out", "sold_out");
			assertSameSentence("", 1, "The order was not accepted", "not_accepted");
			assertSameSentence("user-" + UUID.randomUUID(), 0, "The order was not accepted", "not_accepted");
			assertSameSentence("user-" + UUID.randomUUID(), -1, "The order was not accepted", "not_accepted");

			Map<String, Object> missingUser = parse(postJson("/orders", "{\"quantity\":1}", 200));
			assertEquals("The order was not accepted", missingUser.get("outcome"));
			assertEquals("not_accepted", missingUser.get("result"));
			Map<String, Object> missingQuantity = parse(postJson("/orders", "{\"userId\":\"user-x\"}", 200));
			assertEquals("The order was not accepted", missingQuantity.get("outcome"));
			assertEquals("not_accepted", missingQuantity.get("result"));

			assertEquals(1, acceptanceRows());
			assertEquals(1, customerRows());
			assertTrue(poll(consumer, Duration.ofSeconds(4), null).isEmpty());
		}
	}

	@Test
	void rejectedOpenChangesNothing() throws Exception {
		open("item-1", 0);
		long attempts = attempts();
		int outbox = outboxRows();
		reject("{\"itemId\":\"\",\"quantity\":1}");
		reject("{\"itemId\":\"   \",\"quantity\":1}");
		reject("{\"quantity\":1}");
		reject("{\"itemId\":\"sku-9\",\"quantity\":-1}");
		reject("{\"itemId\":\"sku-9\"}");
		assertEquals("item-1", itemId());
		assertEquals(0, quantity());
		assertTrue(orders.soldOutHint());
		assertEquals(attempts, attempts());
		assertEquals(outbox, outboxRows());
		assertEquals(0, customerRows());
		assertEquals(0, acceptanceRows());

		open("sku-9", 4);
		assertFalse(orders.soldOutHint());
		reject("{\"itemId\":\" \",\"quantity\":4}");
		reject("{\"itemId\":\"other\",\"quantity\":-2}");
		assertEquals("sku-9", itemId());
		assertEquals(4, quantity());
		assertFalse(orders.soldOutHint());
	}

	@Test
	void openAtZeroSellsOutWithoutPublishing() throws Exception {
		long attempts = attempts();
		int outbox = outboxRows();
		Map<String, Object> opened = open("item-1", 0);
		assertEquals("item-1", opened.get("itemId"));
		assertEquals(0, number(opened.get("quantity")));
		assertTrue(orders.soldOutHint());
		assertEquals(attempts, attempts());
		assertEquals(outbox, outboxRows());

		String userId = "user-" + UUID.randomUUID();
		try (KafkaConsumer<String, String> consumer = consumer()) {
			seekToEnd(consumer);
			Map<String, Object> placed = placeOrder(userId, 1);
			assertEquals("The item has sold out", placed.get("outcome"));
			assertEquals("sold_out", placed.get("result"));
			assertEquals(0, quantity());
			assertEquals(0, acceptanceRows());
			assertEquals(0, customerRows());
			assertNothingPublished(consumer, userId, outbox);
		}
	}

	@Test
	void sameItemOpenKeepsCustomersAndReleaseAddsOnTop() throws Exception {
		String userId = "user-" + UUID.randomUUID();
		placeOrder(userId, 1);
		String deadline = nextAllowedText(userId);
		String orderId = orderIdFor(userId, "item-1");
		long attempts = attempts();
		int outbox = outboxRows();

		Map<String, Object> opened = open("item-1", 4);
		assertEquals("item-1", opened.get("itemId"));
		assertEquals(4, number(opened.get("quantity")));
		assertEquals(1, customerRows());
		assertEquals(1, unitsOrdered(userId));
		assertEquals(deadline, nextAllowedText(userId));
		assertAcceptance(orderId, userId, "item-1", 1, false);
		assertEquals(attempts, attempts());
		assertEquals(outbox, outboxRows());

		Map<String, Object> released = release(orderId);
		assertEquals(Set.of("itemId", "quantity", "released"), released.keySet());
		assertEquals(true, released.get("released"));
		assertEquals("item-1", released.get("itemId"));
		assertEquals(5, number(released.get("quantity")));
		assertEquals(5, quantity());
		assertEquals(0, unitsOrdered(userId));
		assertEquals(deadline, nextAllowedText(userId));
		assertFalse(orders.soldOutHint());
		assertAcceptance(orderId, userId, "item-1", 1, true);
		assertEquals(attempts, attempts());
		assertEquals(outbox, outboxRows());
	}

	@Test
	void otherItemOpenDropsCustomersAndTheOldReleaseDoesNothing() throws Exception {
		String userId = "user-" + UUID.randomUUID();
		try (KafkaConsumer<String, String> consumer = consumer()) {
			seekToEnd(consumer);
			placeOrder(userId, 3);
			String first = assertOneEvent(consumer, userId, "item-1", 3);
			assertEquals(3, unitsOrdered(userId));

			long attempts = attempts();
			Map<String, Object> opened = open("sku-9", 4);
			assertEquals("sku-9", opened.get("itemId"));
			assertEquals(4, number(opened.get("quantity")));
			assertEquals(0, customerRows());
			assertAcceptance(first, userId, "item-1", 3, false);
			assertEquals(attempts, attempts());

			Map<String, Object> again = placeOrder(userId, 3);
			assertEquals("The order is in process", again.get("outcome"));
			assertEquals("in_process", again.get("result"));
			String second = assertOneEvent(consumer, userId, "sku-9", 3);
			assertEquals(1, quantity());
			assertEquals(3, unitsOrdered(userId));

			int pool = quantity();
			String deadline = nextAllowedText(userId);
			int outbox = outboxRows();
			Map<String, Object> released = release(first);
			assertEquals(false, released.get("released"));
			assertEquals("sku-9", released.get("itemId"));
			assertEquals(pool, number(released.get("quantity")));
			assertEquals(pool, quantity());
			assertEquals(3, unitsOrdered(userId));
			assertEquals(deadline, nextAllowedText(userId));
			assertFalse(acceptanceReleased(first));
			assertFalse(acceptanceReleased(second));
			assertEquals(outbox, outboxRows());
		}
	}

	@Test
	void readReturnsTheOpenRowWithoutAnAttempt() throws Exception {
		long attempts = attempts();
		int outbox = outboxRows();
		open("sku-9", 4);
		assertEquals(attempts, attempts());
		assertEquals(outbox, outboxRows());

		Map<String, Object> body = readSale();
		assertEquals(Set.of("itemId", "quantity"), body.keySet());
		assertEquals("sku-9", body.get("itemId"));
		assertEquals(4, number(body.get("quantity")));
		assertEquals(4, quantity());
		assertEquals("sku-9", itemId());
		assertEquals(0, acceptanceRows());
		assertEquals(0, customerRows());
		assertEquals(attempts, attempts());
		assertEquals(outbox, outboxRows());
	}

	@Test
	void releaseOnceAgainUnknownAndShort() throws Exception {
		open("item-1", 5);
		String first = "user-" + UUID.randomUUID();
		placeOrder(first, 1);
		assertEquals(4, quantity());
		assertEquals(1, unitsOrdered(first));
		String deadline = nextAllowedText(first);
		String firstOrder = orderIdFor(first, "item-1");
		long attempts = attempts();
		int outbox = outboxRows();

		Map<String, Object> once = release(firstOrder);
		assertEquals(Set.of("itemId", "quantity", "released"), once.keySet());
		assertEquals(true, once.get("released"));
		assertEquals("item-1", once.get("itemId"));
		assertEquals(5, number(once.get("quantity")));
		assertEquals(5, quantity());
		assertEquals(0, unitsOrdered(first));
		assertEquals(deadline, nextAllowedText(first));
		assertFalse(orders.soldOutHint());
		assertTrue(acceptanceReleased(firstOrder));
		assertEquals(attempts, attempts());
		assertEquals(outbox, outboxRows());

		String second = "user-" + UUID.randomUUID();
		placeOrder(second, 1);
		assertEquals(4, quantity());
		String secondOrder = orderIdFor(second, "item-1");
		String secondDeadline = nextAllowedText(second);
		jdbc.update("UPDATE customer SET units_ordered = 0 WHERE user_id = ?", second);
		setSoldOutHint();
		attempts = attempts();
		outbox = outboxRows();

		Map<String, Object> shortRelease = release(secondOrder);
		assertEquals(false, shortRelease.get("released"));
		assertEquals(4, number(shortRelease.get("quantity")));
		assertEquals(4, quantity());
		assertEquals(0, unitsOrdered(second));
		assertEquals(secondDeadline, nextAllowedText(second));
		assertFalse(acceptanceReleased(secondOrder));
		assertTrue(orders.soldOutHint());
		assertEquals(attempts, attempts());
		assertEquals(outbox, outboxRows());

		Map<String, Object> again = release(firstOrder);
		assertEquals(false, again.get("released"));
		assertEquals(4, number(again.get("quantity")));
		assertEquals(0, unitsOrdered(first));
		assertEquals(deadline, nextAllowedText(first));
		assertTrue(acceptanceReleased(firstOrder));
		assertTrue(orders.soldOutHint());
		assertEquals(4, quantity());

		Map<String, Object> unknown = release("missing-order");
		assertEquals(false, unknown.get("released"));
		assertEquals("item-1", unknown.get("itemId"));
		assertEquals(4, number(unknown.get("quantity")));
		assertEquals(4, quantity());
		assertTrue(orders.soldOutHint());
		assertEquals(2, acceptanceRows());
	}

	@Test
	void releaseWhenTheCustomerRowIsGoneChangesNothing() throws Exception {
		String userId = "user-" + UUID.randomUUID();
		placeOrder(userId, 1);
		String orderId = orderIdFor(userId, "item-1");
		int pool = quantity();
		jdbc.update("DELETE FROM customer");
		setSoldOutHint();
		Map<String, Object> body = release(orderId);
		assertEquals(false, body.get("released"));
		assertEquals("item-1", body.get("itemId"));
		assertEquals(pool, number(body.get("quantity")));
		assertEquals(pool, quantity());
		assertFalse(acceptanceReleased(orderId));
		assertTrue(orders.soldOutHint());
		assertEquals(0, customerRows());
	}

	@Test
	void releaseClearsTheHintWhenUnitsReturn() throws Exception {
		jdbc.update("UPDATE item SET quantity = 1 WHERE id = 'item-1'");
		String userId = "user-" + UUID.randomUUID();
		placeOrder(userId, 1);
		assertEquals(0, quantity());
		assertFalse(orders.soldOutHint());
		String deadline = nextAllowedText(userId);
		String orderId = orderIdFor(userId, "item-1");

		Map<String, Object> sold = placeOrder("user-" + UUID.randomUUID(), 1);
		assertEquals("The item has sold out", sold.get("outcome"));
		assertEquals("sold_out", sold.get("result"));
		assertTrue(orders.soldOutHint());
		assertEquals(1, customerRows());
		assertEquals(1, unitsOrdered(userId));

		Map<String, Object> released = release(orderId);
		assertEquals(true, released.get("released"));
		assertEquals(1, number(released.get("quantity")));
		assertEquals(1, quantity());
		assertEquals(0, unitsOrdered(userId));
		assertEquals(deadline, nextAllowedText(userId));
		assertFalse(orders.soldOutHint());

		Map<String, Object> placed = placeOrder("user-" + UUID.randomUUID(), 1);
		assertEquals("The order is in process", placed.get("outcome"));
		assertEquals("in_process", placed.get("result"));
		assertEquals(0, quantity());
	}

	@Test
	void releaseOfZeroUnitsLeavesTheSoldOutHint() throws Exception {
		open("item-1", 0);
		assertTrue(orders.soldOutHint());
		jdbc.update("INSERT INTO customer (user_id, units_ordered) VALUES ('user-zero', 0)");
		jdbc.update("""
				INSERT INTO accepted_order (order_id, user_id, item_id, units_deducted, released)
				VALUES ('order-zero', 'user-zero', 'item-1', 0, false)
				""");
		long attempts = attempts();
		Map<String, Object> body = release("order-zero");
		assertEquals(true, body.get("released"));
		assertEquals("item-1", body.get("itemId"));
		assertEquals(0, number(body.get("quantity")));
		assertEquals(0, quantity());
		assertEquals(0, unitsOrdered("user-zero"));
		assertTrue(acceptanceReleased("order-zero"));
		assertTrue(orders.soldOutHint());
		assertEquals(attempts, attempts());

		Map<String, Object> sold = placeOrder("user-" + UUID.randomUUID(), 1);
		assertEquals("sold_out", sold.get("result"));
		assertEquals(0, quantity());
	}

	@Test
	void fullGateRetriesInsideTheOrder() throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(3);
		try (Connection lock = dataSource.getConnection()) {
			lock.setAutoCommit(false);
			try (Statement statement = lock.createStatement()) {
				statement.execute("SELECT quantity FROM item WHERE id = 'item-1' FOR UPDATE");
			}
			try {
				Future<String> holderOne = pool.submit(() -> page("user-" + UUID.randomUUID(), 1));
				Future<String> holderTwo = pool.submit(() -> page("user-" + UUID.randomUUID(), 1));
				awaitItemUpdates(2);
				long turnedAway = attempts("turned_away");
				long inProcess = attempts("in_process");
				String userId = "user-" + UUID.randomUUID();
				Future<String> posted = pool.submit(() -> postJson("/orders",
						"{\"userId\":\"" + userId + "\",\"quantity\":1}", 200));
				long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
				while (System.nanoTime() < deadline && attempts("turned_away") == turnedAway) {
					if (posted.isDone()) {
						throw new AssertionError("order finished before a turn-away");
					}
					Thread.sleep(20);
				}
				assertTrue(attempts("turned_away") > turnedAway);
				lock.rollback();
				Map<String, Object> body = parse(posted.get(20, TimeUnit.SECONDS));
				assertEquals("The order is in process", body.get("outcome"));
				assertEquals("in_process", body.get("result"));
				assertTrue(holderOne.get(20, TimeUnit.SECONDS).contains("The order is in process"));
				assertTrue(holderTwo.get(20, TimeUnit.SECONDS).contains("The order is in process"));
				assertEquals(inProcess + 3, attempts("in_process"));
				assertEquals(7, quantity());
				assertEquals(3, acceptanceRows());
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
	void interruptedRetryDoesNotPlace() throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(3);
		String userId = "user-" + UUID.randomUUID();
		try (Connection lock = dataSource.getConnection()) {
			lock.setAutoCommit(false);
			try (Statement statement = lock.createStatement()) {
				statement.execute("SELECT quantity FROM item WHERE id = 'item-1' FOR UPDATE");
			}
			try {
				Future<String> holderOne = pool.submit(() -> page("user-" + UUID.randomUUID(), 1));
				Future<String> holderTwo = pool.submit(() -> page("user-" + UUID.randomUUID(), 1));
				awaitItemUpdates(2);
				AtomicReference<Thread> worker = new AtomicReference<>();
				AtomicReference<Throwable> failure = new AtomicReference<>();
				Future<MvcResult> posted = pool.submit(() -> {
					worker.set(Thread.currentThread());
					try {
						return mockMvc.perform(post("/orders")
								.contentType(MediaType.APPLICATION_JSON)
								.content("{\"userId\":\"" + userId + "\",\"quantity\":1}"))
								.andReturn();
					} catch (Throwable ex) {
						failure.set(ex);
						return null;
					}
				});
				long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
				while (worker.get() == null && System.nanoTime() < deadline) {
					Thread.sleep(10);
				}
				assertNotNull(worker.get());
				deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
				while (!posted.isDone() && System.nanoTime() < deadline) {
					worker.get().interrupt();
					Thread.sleep(10);
				}
				MvcResult result = posted.get(5, TimeUnit.SECONDS);
				Throwable error = failure.get();
				if (error == null && result != null) {
					error = result.getResolvedException();
				}
				assertNotNull(error);
				assertInterrupted(error);
				if (result != null) {
					assertTrue(result.getResponse().getStatus() >= 500);
				}
				lock.rollback();
				assertTrue(holderOne.get(20, TimeUnit.SECONDS).contains("The order is in process"));
				assertTrue(holderTwo.get(20, TimeUnit.SECONDS).contains("The order is in process"));
				assertEquals(0, count("SELECT count(*) FROM customer WHERE user_id = ?", userId));
				assertEquals(0, count("SELECT count(*) FROM accepted_order WHERE user_id = ?", userId));
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
	void unmatchedOutcomeIsAShopperResult() throws Exception {
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
			Map<String, Object> body = placeOrder("user-" + UUID.randomUUID(), 1);
			assertEquals("unmatched", body.get("result"));
			assertNull(body.get("outcome"));
		} finally {
			ordersField.set(target, previous);
		}
	}

	private void assertSameSentence(String userId, int quantity, String sentence, String result) throws Exception {
		int pool = quantity();
		int acceptances = acceptanceRows();
		String page = page(userId, quantity);
		Map<String, Object> api = placeOrder(userId, quantity);
		assertTrue(page.contains(sentence), page);
		assertEquals(sentence, api.get("outcome"));
		assertEquals(result, api.get("result"));
		assertEquals(Set.of("outcome", "result"), api.keySet());
		assertEquals(pool, quantity());
		assertEquals(acceptances, acceptanceRows());
	}

	private void assertAcceptance(String orderId, String userId, String itemId, int units, boolean released) {
		Map<String, Object> row = jdbc.queryForMap(
				"SELECT user_id, item_id, units_deducted, released FROM accepted_order WHERE order_id = ?",
				orderId);
		assertEquals(userId, row.get("user_id"));
		assertEquals(itemId, row.get("item_id"));
		assertEquals(units, ((Number) row.get("units_deducted")).intValue());
		assertEquals(released, Boolean.TRUE.equals(row.get("released")));
	}

	private static void assertInterrupted(Throwable thrown) {
		Throwable current = thrown;
		while (current != null) {
			if (current instanceof IllegalStateException && "order retry interrupted".equals(current.getMessage())) {
				return;
			}
			Throwable next = current.getCause();
			if (next == current) {
				break;
			}
			current = next;
		}
		throw new AssertionError("missing interrupted retry", thrown);
	}

	private Map<String, Object> placeOrder(String userId, int quantity) throws Exception {
		return parse(postJson("/orders",
				"{\"userId\":" + jsonString(userId) + ",\"quantity\":" + quantity + "}", 200));
	}

	private Map<String, Object> open(String itemId, int quantity) throws Exception {
		return parse(postJson("/sale",
				"{\"itemId\":" + jsonString(itemId) + ",\"quantity\":" + quantity + "}", 200));
	}

	private void reject(String json) throws Exception {
		postJson("/sale", json, 400);
	}

	private Map<String, Object> readSale() throws Exception {
		String body = mockMvc.perform(get("/sale")).andExpect(status().isOk()).andReturn().getResponse()
				.getContentAsString();
		assertFalse(body.isBlank());
		return parse(body);
	}

	private Map<String, Object> release(String orderId) throws Exception {
		String body = mockMvc.perform(post("/orders/" + orderId + "/release"))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString();
		assertFalse(body.isBlank());
		return parse(body);
	}

	private String page(String userId, int quantity) throws Exception {
		return mockMvc.perform(post("/").param("userId", userId).param("quantity", Integer.toString(quantity)))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString();
	}

	private String postJson(String path, String json, int expected) throws Exception {
		return mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json))
				.andExpect(status().is(expected))
				.andReturn()
				.getResponse()
				.getContentAsString();
	}

	private static Map<String, Object> parse(String json) {
		return JsonParserFactory.getJsonParser().parseMap(json);
	}

	private static int number(Object value) {
		return ((Number) value).intValue();
	}

	private static String jsonString(String value) {
		return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
	}

	private void setSoldOutHint() throws Exception {
		Orders target = AopTestUtils.getUltimateTargetObject(orders);
		Field field = Orders.class.getDeclaredField("soldOutHint");
		field.setAccessible(true);
		((AtomicBoolean) field.get(target)).set(true);
	}

	private void elapseTheHour(String userId) {
		jdbc.update(
				"UPDATE customer SET next_allowed = clock_timestamp() - interval '1 second' WHERE user_id = ?",
				userId);
	}

	private String assertOneEvent(KafkaConsumer<String, String> consumer, String userId, String itemId, int units) {
		List<ConsumerRecord<String, String>> matched = poll(consumer, Duration.ofSeconds(8), userId).stream()
				.filter(record -> record.value().contains(userId))
				.toList();
		assertEquals(1, matched.size());
		ConsumerRecord<String, String> record = matched.get(0);
		Map<String, Object> event = parse(record.value());
		assertEquals(Set.of("orderId", "userId", "itemId", "unitsDeducted"), event.keySet());
		assertEquals(userId, event.get("userId"));
		assertEquals(itemId, event.get("itemId"));
		assertEquals(units, number(event.get("unitsDeducted")));
		assertEquals(event.get("orderId"), record.key());
		return (String) event.get("orderId");
	}

	private void assertNothingPublished(KafkaConsumer<String, String> consumer, String userId, int outboxBefore) {
		List<ConsumerRecord<String, String>> records = poll(consumer, Duration.ofSeconds(4), null);
		assertTrue(records.stream().noneMatch(record -> record.value().contains(userId)));
		assertEquals(outboxBefore, outboxRows());
	}

	private String itemId() {
		return jdbc.queryForObject("SELECT id FROM item", String.class);
	}

	private int quantity() {
		return jdbc.queryForObject("SELECT quantity FROM item", Integer.class);
	}

	private int unitsOrdered(String userId) {
		return jdbc.queryForObject("SELECT units_ordered FROM customer WHERE user_id = ?", Integer.class, userId);
	}

	private String nextAllowedText(String userId) {
		return jdbc.queryForObject(
				"SELECT to_char(next_allowed, 'YYYY-MM-DD HH24:MI:SS.US') FROM customer WHERE user_id = ?",
				String.class, userId);
	}

	private String orderIdFor(String userId, String itemId) {
		return jdbc.queryForObject(
				"SELECT order_id FROM accepted_order WHERE user_id = ? AND item_id = ?",
				String.class, userId, itemId);
	}

	private boolean acceptanceReleased(String orderId) {
		return Boolean.TRUE.equals(jdbc.queryForObject(
				"SELECT released FROM accepted_order WHERE order_id = ?", Boolean.class, orderId));
	}

	private int acceptanceRows() {
		return count("SELECT count(*) FROM accepted_order");
	}

	private int customerRows() {
		return count("SELECT count(*) FROM customer");
	}

	private int outboxRows() {
		return count("SELECT count(*) FROM outbox_record");
	}

	private int count(String sql, Object... args) {
		return jdbc.queryForObject(sql, Integer.class, args);
	}

	private KafkaConsumer<String, String> consumer() {
		Properties properties = new Properties();
		properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
		properties.put(ConsumerConfig.GROUP_ID_CONFIG, "platform-seam-test");
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

	private long attempts() {
		return registry.find("sale.attempts").counters().stream().mapToLong(counter -> (long) counter.count()).sum();
	}

	private long attempts(String result) {
		Counter counter = registry.find("sale.attempts").tag("result", result).counter();
		return counter == null ? 0 : (long) counter.count();
	}

}
