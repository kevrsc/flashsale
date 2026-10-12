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
		String result = null;
		try {
			if (userId.isEmpty() || units < 1) {
				result = "not_accepted";
				return new Attempt.Shopper(OrderOutcome.notAccepted());
			}
			if (orders.soldOutHint()) {
				result = "sold_out";
				return new Attempt.Shopper(OrderOutcome.soldOut());
			}
			if (!permits.tryAcquire()) {
				result = "turned_away";
				return new Attempt.TurnedAway();
			}
			try {
				if (orders.soldOutHint()) {
					result = "sold_out";
					return new Attempt.Shopper(OrderOutcome.soldOut());
				}
				OrderOutcome outcome = orders.place(userId, units);
				result = resultOf(outcome);
				return new Attempt.Shopper(outcome);
			} finally {
				permits.release();
			}
		} finally {
			if (result != null) {
				totals.request(result);
			}
		}
	}

	private static String resultOf(OrderOutcome outcome) {
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

}
