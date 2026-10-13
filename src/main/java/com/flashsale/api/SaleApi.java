package com.flashsale.api;

import java.util.concurrent.ThreadLocalRandom;

import com.flashsale.inventory.Admission;
import com.flashsale.inventory.Attempt;
import com.flashsale.inventory.ItemPool;
import com.flashsale.inventory.OrderOutcome;
import com.flashsale.inventory.Orders;
import com.flashsale.inventory.ReleaseResult;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SaleApi {

	private final Admission admission;
	private final Orders orders;

	public SaleApi(Admission admission, Orders orders) {
		this.admission = admission;
		this.orders = orders;
	}

	@PostMapping("/orders")
	public PlaceResponse orders(@RequestBody PlaceRequest request) {
		String userId = request.userId() == null ? "" : request.userId();
		int quantity = request.quantity() == null ? 0 : request.quantity();
		OrderOutcome outcome = shopperOutcome(userId, quantity);
		return new PlaceResponse(outcome.message(), resultOf(outcome));
	}

	@PostMapping("/sale")
	public ResponseEntity<ItemPool> open(@RequestBody OpenRequest request) {
		int quantity = request.quantity() == null ? -1 : request.quantity();
		ItemPool pool = orders.open(request.itemId(), quantity);
		if (pool == null) {
			return ResponseEntity.badRequest().build();
		}
		return ResponseEntity.ok(pool);
	}

	@GetMapping("/sale")
	public ItemPool read() {
		return orders.read();
	}

	@PostMapping("/orders/{orderId}/release")
	public ReleaseResult release(@PathVariable String orderId) {
		return orders.release(orderId);
	}

	private OrderOutcome shopperOutcome(String userId, int quantity) {
		while (true) {
			Attempt attempt = admission.admit(userId, quantity);
			if (attempt instanceof Attempt.Shopper shopper) {
				return shopper.outcome();
			}
			jitter();
		}
	}

	private static void jitter() {
		try {
			Thread.sleep(ThreadLocalRandom.current().nextLong(10, 50));
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("order retry interrupted", ex);
		}
	}

	static String resultOf(OrderOutcome outcome) {
		if (OrderOutcome.inProcess().equals(outcome)) {
			return "in_process";
		}
		if (OrderOutcome.waitOneHour().equals(outcome)) {
			return "wait";
		}
		if (OrderOutcome.refused().equals(outcome)) {
			return "refused";
		}
		if (OrderOutcome.soldOut().equals(outcome)) {
			return "sold_out";
		}
		if (OrderOutcome.notAccepted().equals(outcome)) {
			return "not_accepted";
		}
		if (OrderOutcome.unmatched().equals(outcome)) {
			return "unmatched";
		}
		throw new IllegalStateException("no sale result for " + outcome);
	}

	public record PlaceRequest(String userId, Integer quantity) {
	}

	public record OpenRequest(String itemId, Integer quantity) {
	}

	public record PlaceResponse(String outcome, String result) {
	}

}
