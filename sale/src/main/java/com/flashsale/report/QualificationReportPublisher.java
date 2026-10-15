package com.flashsale.report;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import tools.jackson.databind.json.JsonMapper;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.flashsale.sale.DownstreamGuard;

@Component
public class QualificationReportPublisher {

	private final JdbcTemplate jdbc;
	private final KafkaTemplate<String, String> kafka;
	private final JsonMapper json;
	private final DownstreamGuard guard;

	public QualificationReportPublisher(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka, JsonMapper json,
			DownstreamGuard guard) {
		this.jdbc = jdbc;
		this.kafka = kafka;
		this.json = json;
		this.guard = guard;
	}

	@Scheduled(fixedDelayString = "${sale.report-delay:200ms}")
	public void publishPending() {
		List<Map<String, Object>> rows = jdbc.queryForList("""
				SELECT qualification_id, sale_id, user_id, item_id
				FROM orders o
				WHERE NOT EXISTS (
					SELECT 1 FROM qualification_report_sent s
					WHERE s.qualification_id = o.qualification_id
				)
				""");
		for (Map<String, Object> row : rows) {
			publishOne(row);
		}
	}

	private void publishOne(Map<String, Object> row) {
		String qualificationId = String.valueOf(row.get("qualification_id"));
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("qualificationId", qualificationId);
		body.put("saleId", row.get("sale_id"));
		body.put("userId", row.get("user_id"));
		body.put("itemId", row.get("item_id"));
		String payload = json.writeValueAsString(body);
		try {
			guard.reportUntilAck(() -> send(qualificationId, payload));
			jdbc.update(
					"INSERT INTO qualification_report_sent (qualification_id) VALUES (?) ON CONFLICT DO NOTHING",
					qualificationId);
		}
		catch (RuntimeException ex) {
			// The order stays eligible on the next pass.
		}
	}

	private void send(String qualificationId, String payload) {
		try {
			kafka.send("sale.qualification-report", qualificationId, payload).get(3, TimeUnit.SECONDS);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("qualification report interrupted", ex);
		}
		catch (RuntimeException ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw new IllegalStateException("qualification report failed", ex);
		}
	}

}
