package com.flashsale.inventory;

import java.util.Map;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

@Service
public class Orders {

	private final NamedParameterJdbcTemplate jdbc;
	private final ApplicationEventPublisher events;

	public Orders(NamedParameterJdbcTemplate jdbc, ApplicationEventPublisher events) {
		this.jdbc = jdbc;
		this.events = events;
	}

	@Transactional
	public OrderOutcome place(String userId, int units) {
		String itemId = jdbc.queryForObject("SELECT id FROM item", Map.of(), String.class);
		int updated = jdbc.update("""
				UPDATE item
				SET quantity = quantity - :units
				WHERE id = :itemId AND quantity >= :units
				""", Map.of("units", units, "itemId", itemId));
		if (updated == 0) {
			TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
			return OrderOutcome.unmatched();
		}
		String orderId = UUID.randomUUID().toString();
		events.publishEvent(new OrderAccepted(orderId, userId, itemId, units));
		return OrderOutcome.inProcess();
	}

}
