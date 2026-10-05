package com.flashsale.inventory;

public record OrderOutcome(String message) {

	public static OrderOutcome inProcess() {
		return new OrderOutcome("The order is in process");
	}

	public static OrderOutcome unmatched() {
		return new OrderOutcome(null);
	}

	public static OrderOutcome waitOneHour() {
		return new OrderOutcome("Wait one hour before ordering again");
	}

	public static OrderOutcome refused() {
		return new OrderOutcome("The order is refused");
	}

	public static OrderOutcome soldOut() {
		return new OrderOutcome("The item has sold out");
	}

}
