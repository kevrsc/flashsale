package com.flashsale.qualify;

import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.flashsale.sale.DownstreamGuard;

@Component
public class CloseReport {

	private static final Logger log = LoggerFactory.getLogger(CloseReport.class);

	private final KafkaTemplate<String, String> kafka;
	private final DownstreamGuard guard;

	public CloseReport(KafkaTemplate<String, String> kafka, DownstreamGuard guard) {
		this.kafka = kafka;
		this.guard = guard;
	}

	public void publish(String body) {
		try {
			guard.reportUntilAck(() -> send(body));
		}
		catch (RuntimeException ex) {
			log.warn("close report deferred");
		}
	}

	private void send(String body) {
		try {
			kafka.send("sale.close-report", "sale", body).get(3, TimeUnit.SECONDS);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("close report interrupted", ex);
		}
		catch (RuntimeException ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw new IllegalStateException("close report failed", ex);
		}
	}

}
