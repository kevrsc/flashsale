package com.flashsale.inventory;

import java.util.concurrent.Semaphore;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class Admission {

	private final Semaphore permits;
	private final Orders orders;

	public Admission(@Value("${flashsale.admission.bound}") int bound, Orders orders) {
		this.permits = new Semaphore(bound);
		this.orders = orders;
	}

	public Attempt admit(String userId, int units) {
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
			return new Attempt.Shopper(orders.place(userId, units));
		} finally {
			permits.release();
		}
	}

}
