package com.flashsale.inventory;

public record OrderOutcome(String message) {

	public static OrderOutcome inProcess() {
		return new OrderOutcome("The order is in process");
	}

	public static OrderOutcome unmatched() {
		return new OrderOutcome(null);
	}

}
