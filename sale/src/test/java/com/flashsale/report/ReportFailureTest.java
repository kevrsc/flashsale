package com.flashsale.report;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import tools.jackson.databind.json.JsonMapper;

import org.junit.jupiter.api.Test;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import com.flashsale.sale.TestBreakers;

class ReportFailureTest {

	@Test
	void failedReportLeavesTheOrderEligible() {
		JdbcTemplate jdbc = rows();
		QualificationReportPublisher publisher = new QualificationReportPublisher(jdbc, kafkaThrowing(),
				JsonMapper.builder().build(), TestBreakers.guard());
		publisher.publishPending();
		verify(jdbc).queryForList(anyString());
		verifyNoMoreInteractions(jdbc);

		QualificationReportPublisher interrupted = new QualificationReportPublisher(jdbc, kafkaInterrupted(),
				JsonMapper.builder().build(), TestBreakers.guard());
		interrupted.publishPending();
		assertTrue(Thread.interrupted());

		QualificationReportPublisher refused = new QualificationReportPublisher(jdbc, kafkaFailed(),
				JsonMapper.builder().build(), TestBreakers.guard());
		refused.publishPending();
		verify(jdbc, org.mockito.Mockito.times(3)).queryForList(anyString());
		verifyNoMoreInteractions(jdbc);
	}

	private static JdbcTemplate rows() {
		JdbcTemplate jdbc = mock(JdbcTemplate.class);
		when(jdbc.queryForList(anyString())).thenReturn(List.of(Map.of(
				"qualification_id", "q-1",
				"sale_id", "sale",
				"user_id", "ada",
				"item_id", "sku-1")));
		return jdbc;
	}

	@SuppressWarnings("unchecked")
	private static KafkaTemplate<String, String> kafkaThrowing() {
		KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
		when(kafka.send(anyString(), any(), any())).thenThrow(new IllegalStateException("down"));
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
