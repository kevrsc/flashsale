package com.flashsale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

import javax.sql.DataSource;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.flashsale.inventory.Admission;
import com.flashsale.inventory.Attempt;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
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

	@BeforeEach
	void resetQuantity() {
		jdbc.update("UPDATE item SET quantity = 10 WHERE id = 'item-1'");
	}

	@Test
	void oneOrderReducesThePoolAndSendsTheEvent() throws Exception {
		String userId = "user-" + UUID.randomUUID();
		try (KafkaConsumer<String, String> consumer = consumer()) {
			seekToEnd(consumer);
			String body = mockMvc.perform(post("/").param("userId", userId).param("quantity", "1"))
					.andExpect(status().isOk())
					.andReturn()
					.getResponse()
					.getContentAsString();

			assertTrue(body.contains("The order is in process"));
			assertFalse(body.contains("name=\"itemId\""));
			assertFalse(body.contains("item-1"));
			assertEquals(9, quantity());
			assertEquals(0, customerRows());

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
			assertEquals(1, ((Number) event.get("unitsDeducted")).intValue());
			assertEquals(event.get("orderId"), record.key());
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
				assertEquals(0, customerRows());
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
			String body = mockMvc.perform(post("/").param("userId", userId).param("quantity", "1"))
					.andExpect(status().isOk())
					.andReturn()
					.getResponse()
					.getContentAsString();

			assertFalse(body.contains("in process"));
			assertEquals(0, quantity());
			assertEquals(outboxBefore, outboxRows());

			List<ConsumerRecord<String, String>> records = poll(consumer, Duration.ofSeconds(4), null);
			assertTrue(records.stream().noneMatch(record -> record.value().contains(userId)));
			assertEquals(outboxBefore, outboxRows());
		}
	}

	private String postOrder() throws Exception {
		return mockMvc.perform(post("/").param("userId", "user-" + UUID.randomUUID()).param("quantity", "1"))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString();
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
				  AND query LIKE '%UPDATE item%'
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

}
