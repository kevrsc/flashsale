package com.flashsale.inventory;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

@Service
public class Orders {

	private static final int CUSTOMER_LIMIT = 3;

	private final AtomicBoolean soldOutHint = new AtomicBoolean();

	private final NamedParameterJdbcTemplate jdbc;
	private final ApplicationEventPublisher events;

	public Orders(NamedParameterJdbcTemplate jdbc, ApplicationEventPublisher events) {
		this.jdbc = jdbc;
		this.events = events;
	}

	@Transactional
	public OrderOutcome place(String userId, int units) {
		jdbc.update("""
				INSERT INTO customer (user_id)
				VALUES (:userId)
				ON CONFLICT (user_id) DO NOTHING
				""", Map.of("userId", userId));

		CustomerHold customer = Objects.requireNonNull(jdbc.queryForObject("""
				SELECT units_ordered,
				       next_allowed > clock_timestamp() AS waiting
				FROM customer
				WHERE user_id = :userId
				FOR UPDATE
				""", Map.of("userId", userId), (rs, row) -> new CustomerHold(
						rs.getInt("units_ordered"),
						rs.getBoolean("waiting"))));

		ItemHold item = Objects.requireNonNull(jdbc.queryForObject("""
				SELECT id, quantity
				FROM item
				WHERE id = 'item-1'
				FOR UPDATE
				""", Map.of(), (rs, row) -> new ItemHold(rs.getString("id"), rs.getInt("quantity"))));

		if (item.quantity() <= 0) {
			return soldOut();
		}
		int unitsLeft = CUSTOMER_LIMIT - customer.unitsOrdered();
		if (customer.waiting() && unitsLeft <= 0) {
			markRollbackOnly();
			return OrderOutcome.waitOneHour();
		}
		if (unitsLeft <= 0) {
			markRollbackOnly();
			return OrderOutcome.refused();
		}

		int quantity = item.quantity();
		while (quantity > 0 && unitsLeft > 0) {
			int deducted = Math.min(units, Math.min(quantity, unitsLeft));
			if (deducted < 1) {
				break;
			}
			int updated = jdbc.update("""
					UPDATE item
					SET quantity = quantity - :units
					WHERE id = :itemId AND quantity >= :units
					""", Map.of("units", deducted, "itemId", item.id()));
			if (updated > 0) {
				jdbc.update("""
						UPDATE customer
						SET units_ordered = units_ordered + :units,
						    next_allowed = clock_timestamp() + interval '1 hour'
						WHERE user_id = :userId
						""", Map.of("units", deducted, "userId", userId));
				String orderId = UUID.randomUUID().toString();
				events.publishEvent(new OrderAccepted(orderId, userId, item.id(), deducted));
				return OrderOutcome.inProcess();
			}
			int reread = lockedQuantity();
			if (reread <= 0) {
				return soldOut();
			}
			int again = Math.min(units, Math.min(reread, unitsLeft));
			if (again < 1 || reread == quantity) {
				break;
			}
			quantity = reread;
		}
		markRollbackOnly();
		return OrderOutcome.unmatched();
	}

	public boolean soldOutHint() {
		return soldOutHint.get();
	}

	// Tests clear this after restoring a quantity above zero. The product does not.
	public void clearSoldOutHint() {
		soldOutHint.set(false);
	}

	private OrderOutcome soldOut() {
		soldOutHint.set(true);
		markRollbackOnly();
		return OrderOutcome.soldOut();
	}

	private int lockedQuantity() {
		return Objects.requireNonNull(jdbc.queryForObject("""
				SELECT quantity
				FROM item
				WHERE id = 'item-1'
				""", Map.of(), Integer.class));
	}

	private static void markRollbackOnly() {
		TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
	}

	private record CustomerHold(int unitsOrdered, boolean waiting) {
	}

	private record ItemHold(String id, int quantity) {
	}

}
