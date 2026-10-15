package com.flashsale.order;

import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.flashsale.qualify.SaleScript;
import com.flashsale.sale.DownstreamGuard;

@Component
public class OrderInsert {

	private static final Logger log = LoggerFactory.getLogger(OrderInsert.class);

	private final JdbcTemplate jdbc;
	private final TransactionTemplate transactions;
	private final SaleScript script;
	private final JsonMapper json;
	private final DownstreamGuard guard;
	private final AtomicReference<Runnable> beforeInsert = new AtomicReference<>(() -> {
	});

	public OrderInsert(JdbcTemplate jdbc, PlatformTransactionManager transactions, SaleScript script, JsonMapper json,
			DownstreamGuard guard) {
		this.jdbc = jdbc;
		this.transactions = new TransactionTemplate(transactions);
		this.script = script;
		this.json = json;
		this.guard = guard;
	}

	public void beforeInsert(Runnable hook) {
		beforeInsert.set(hook == null ? () -> {
		} : hook);
	}

	public void accept(String document) {
		JsonNode node = json.readTree(document);
		String qualificationId = text(node, "qualificationId");
		String saleId = text(node, "saleId");
		String userId = text(node, "userId");
		String itemId = text(node, "itemId");
		beforeInsert.get().run();
		guard.insert(() -> transactions.executeWithoutResult(status -> {
			jdbc.update("""
					INSERT INTO orders (qualification_id, sale_id, user_id, item_id)
					VALUES (?, ?, ?, ?)
					ON CONFLICT (qualification_id) DO NOTHING
					""", qualificationId, saleId, userId, itemId);
			log.info("order insert qualificationId={}", qualificationId);
		}));
		script.ack(qualificationId);
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = node.get(field);
		if (value == null || value.isNull()) {
			return null;
		}
		return value.asString();
	}

}
