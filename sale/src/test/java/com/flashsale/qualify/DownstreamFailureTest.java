package com.flashsale.qualify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import tools.jackson.databind.json.JsonMapper;

import org.junit.jupiter.api.Test;

import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import com.flashsale.sale.DownstreamGuard;
import com.flashsale.sale.TestBreakers;

class DownstreamFailureTest {

	@Test
	void closeReportIsDeferredWhenKafkaDoesNotAck() {
		CloseReport report = new CloseReport(kafkaThrowing(new IllegalStateException("down")), TestBreakers.guard());
		report.publish("{\"saleId\":\"sale\"}");

		CloseReport interrupted = new CloseReport(kafkaInterrupted(), TestBreakers.guard());
		interrupted.publish("{\"saleId\":\"sale\"}");
		assertTrue(Thread.interrupted());

		CloseReport refused = new CloseReport(kafkaFailed(), TestBreakers.guard());
		refused.publish("{\"saleId\":\"sale\"}");
	}

	@Test
	void relayDefersWhenThePendingDocumentCannotBePublished() {
		QualificationRelay runtime = relay(kafkaThrowing(new IllegalStateException("down")));
		runtime.republish();

		QualificationRelay interrupted = relay(kafkaInterrupted());
		interrupted.republish();
		assertTrue(Thread.interrupted());

		QualificationRelay refused = relay(kafkaFailed());
		refused.republish();
	}

	@Test
	void scriptRejectsAnEmptyEval() {
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		SaleScript script = new SaleScript(redis, JsonMapper.builder().build());
		IllegalStateException error = assertThrows(IllegalStateException.class, script::start);
		assertEquals("sale script returned nothing", error.getMessage());
		assertThrows(IllegalStateException.class, () -> script.attempt(null));
	}

	@Test
	void userIdReaderRejectsBlankAndNonTextIds() {
		assertEquals("", UserIds.read(null));
		assertEquals("", UserIds.read("  "));
		assertEquals("", UserIds.read("{"));
		assertEquals("", UserIds.read("{}"));
		assertEquals("", UserIds.read("{\"userId\":null}"));
		assertEquals("", UserIds.read("{\"userId\":4}"));
		assertEquals("ada", UserIds.read("{\"userId\":\"ada\"}"));
	}

	private static QualificationRelay relay(KafkaTemplate<String, String> kafka) {
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		@SuppressWarnings("unchecked")
		HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
		when(redis.opsForHash()).thenReturn(hashes);
		when(hashes.entries("pending")).thenReturn(Map.of("id", "{\"qualificationId\":\"abc\"}"));
		return new QualificationRelay(redis, kafka, JsonMapper.builder().build());
	}

	@SuppressWarnings("unchecked")
	private static KafkaTemplate<String, String> kafkaThrowing(RuntimeException error) {
		KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
		when(kafka.send(anyString(), any(), any())).thenThrow(error);
		return kafka;
	}

	@SuppressWarnings("unchecked")
	private static KafkaTemplate<String, String> kafkaInterrupted() {
		KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
		when(kafka.send(anyString(), any(), any())).thenReturn(new CompletableFuture<>() {
			@Override
			public SendResult<String, String> get(long timeout, TimeUnit unit) throws InterruptedException {
				throw new InterruptedException("stopped");
			}
		});
		return kafka;
	}

	@SuppressWarnings("unchecked")
	private static KafkaTemplate<String, String> kafkaFailed() {
		KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
		CompletableFuture<SendResult<String, String>> future = new CompletableFuture<>();
		future.completeExceptionally(new IllegalStateException("down"));
		when(kafka.send(anyString(), any(), any())).thenReturn(future);
		return kafka;
	}

}
