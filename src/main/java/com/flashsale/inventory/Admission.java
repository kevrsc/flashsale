package com.flashsale.inventory;

import java.util.concurrent.Semaphore;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class Admission {

	private final Semaphore permits;
	private final Orders orders;
	private final SaleTotals totals;

	public Admission(@Value("${flashsale.admission.bound}") int bound, Orders orders, SaleTotals totals) {
		this.permits = new Semaphore(bound);
		this.orders = orders;
		this.totals = totals;
	}

	public Attempt admit(String userId, int units) {
		boolean succeeded = false;
		try {
			if (userId.isEmpty() || units < 1) {
				return new Attempt.Shopper(OrderOutcome.notAccepted());
			}
			if (orders.soldOutHint()) {
				return new Attempt.Shopper(OrderOutcome.soldOut());
			}
			if (!permits.tryAcquire()) {
				return new Attempt.TurnedAway();
			}
			try {
				if (orders.soldOutHint()) {
					return new Attempt.Shopper(OrderOutcome.soldOut());
				}
				OrderOutcome outcome = orders.place(userId, units);
				succeeded = OrderOutcome.inProcess().equals(outcome);
				return new Attempt.Shopper(outcome);
			} finally {
				permits.release();
			}
		} finally {
			totals.request(succeeded);
		}
	}

}
