package com.flashsale.inventory;

import java.util.List;
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

		ItemHold item = lockItem();

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
				jdbc.update("""
						INSERT INTO accepted_order (order_id, user_id, item_id, units_deducted, released)
						VALUES (:orderId, :userId, :itemId, :units, false)
						""", Map.of("orderId", orderId, "userId", userId, "itemId", item.id(), "units", deducted));
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

	@Transactional
	public ItemPool open(String itemId, int quantity) {
		if (itemId == null || itemId.isBlank() || quantity < 0) {
			return null;
		}
		// Customers are locked before the item, matching place. A different item id deletes them.
		lockCustomers();
		ItemHold current = lockItem();
		if (!current.id().equals(itemId)) {
			jdbc.update("DELETE FROM customer", Map.of());
		}
		jdbc.update("""
				UPDATE item
				SET id = :itemId, quantity = :quantity
				WHERE id = :currentId
				""", Map.of("itemId", itemId, "quantity", quantity, "currentId", current.id()));
		soldOutHint.set(quantity == 0);
		return new ItemPool(itemId, quantity);
	}

	public ItemPool read() {
		return Objects.requireNonNull(jdbc.queryForObject("""
				SELECT id, quantity
				FROM item
				""", Map.of(), (rs, row) -> new ItemPool(rs.getString("id"), rs.getInt("quantity"))));
	}

	@Transactional
	public ReleaseResult release(String orderId) {
		List<Acceptance> found = jdbc.query("""
				SELECT user_id, item_id, units_deducted, released
				FROM accepted_order
				WHERE order_id = :orderId
				FOR UPDATE
				""", Map.of("orderId", orderId), (rs, row) -> new Acceptance(
						rs.getString("user_id"),
						rs.getString("item_id"),
						rs.getInt("units_deducted"),
						rs.getBoolean("released")));
		if (found.isEmpty()) {
			ItemPool pool = read();
			return new ReleaseResult(pool.itemId(), pool.quantity(), false);
		}
		Acceptance acceptance = found.get(0);
		// Acceptance, then the customer, then the item.
		List<Integer> held = jdbc.query("""
				SELECT units_ordered
				FROM customer
				WHERE user_id = :userId
				FOR UPDATE
				""", Map.of("userId", acceptance.userId()), (rs, row) -> rs.getInt("units_ordered"));
		ItemHold item = lockItem();
		Integer customerUnits = held.isEmpty() ? null : held.get(0);
		if (acceptance.released()
				|| !acceptance.itemId().equals(item.id())
				|| customerUnits == null
				|| customerUnits < acceptance.unitsDeducted()) {
			return new ReleaseResult(item.id(), item.quantity(), false);
		}
		jdbc.update("""
				UPDATE customer
				SET units_ordered = units_ordered - :units
				WHERE user_id = :userId
				""", Map.of("units", acceptance.unitsDeducted(), "userId", acceptance.userId()));
		jdbc.update("""
				UPDATE item
				SET quantity = quantity + :units
				WHERE id = :itemId
				""", Map.of("units", acceptance.unitsDeducted(), "itemId", item.id()));
		jdbc.update("""
				UPDATE accepted_order
				SET released = true
				WHERE order_id = :orderId
				""", Map.of("orderId", orderId));
		int quantity = item.quantity() + acceptance.unitsDeducted();
		if (quantity > 0) {
			soldOutHint.set(false);
		}
		return new ReleaseResult(item.id(), quantity, true);
	}

	public boolean soldOutHint() {
		return soldOutHint.get();
	}

	// Tests clear this after restoring a quantity above zero.
	// Opening the pool and releasing units clear it when the pool is left above zero.
	public void clearSoldOutHint() {
		soldOutHint.set(false);
	}

	private OrderOutcome soldOut() {
		soldOutHint.set(true);
		markRollbackOnly();
		return OrderOutcome.soldOut();
	}

	private void lockCustomers() {
		jdbc.query("""
				SELECT user_id
				FROM customer
				ORDER BY user_id
				FOR UPDATE
				""", Map.of(), (rs, row) -> rs.getString("user_id"));
	}

	private ItemHold lockItem() {
		return Objects.requireNonNull(jdbc.queryForObject("""
				SELECT id, quantity
				FROM item
				FOR UPDATE
				""", Map.of(), (rs, row) -> new ItemHold(rs.getString("id"), rs.getInt("quantity"))));
	}

	private int lockedQuantity() {
		return Objects.requireNonNull(jdbc.queryForObject("""
				SELECT quantity
				FROM item
				""", Map.of(), Integer.class));
	}

	private static void markRollbackOnly() {
		TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
	}

	private record CustomerHold(int unitsOrdered, boolean waiting) {
	}

	private record ItemHold(String id, int quantity) {
	}

	private record Acceptance(String userId, String itemId, int unitsDeducted, boolean released) {
	}

}
