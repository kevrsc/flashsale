package com.flashsale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

	@BeforeEach
	void resetPool() {
		jdbc.update("UPDATE item SET pool = 10 WHERE id = 'item-1'");
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
			assertEquals(9, pool());
			assertEquals(0, customerRows());

			List<ConsumerRecord<String, String>> records = poll(consumer, Duration.ofSeconds(8), true);
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
	void zeroRowUpdateLeavesThePoolAndSendsNothing() throws Exception {
		jdbc.update("UPDATE item SET pool = 0 WHERE id = 'item-1'");
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
			assertEquals(0, pool());
			assertEquals(outboxBefore, outboxRows());

			List<ConsumerRecord<String, String>> records = poll(consumer, Duration.ofSeconds(4), false);
			assertTrue(records.stream().noneMatch(record -> record.value().contains(userId)));
			assertEquals(outboxBefore, outboxRows());
		}
	}

	private int pool() {
		return jdbc.queryForObject("SELECT pool FROM item WHERE id = 'item-1'", Integer.class);
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
			boolean stopWhenAnyRecordArrives) {
		List<ConsumerRecord<String, String>> found = new ArrayList<>();
		long deadline = System.nanoTime() + budget.toNanos();
		while (System.nanoTime() < deadline) {
			Duration remaining = Duration.ofNanos(deadline - System.nanoTime());
			Duration slice = remaining.compareTo(Duration.ofMillis(200)) > 0 ? Duration.ofMillis(200) : remaining;
			consumer.poll(slice).forEach(found::add);
			if (stopWhenAnyRecordArrives && !found.isEmpty()) {
				break;
			}
		}
		return found;
	}

}
