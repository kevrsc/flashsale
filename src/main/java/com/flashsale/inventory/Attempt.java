package com.flashsale.inventory;

public sealed interface Attempt permits Attempt.Shopper, Attempt.TurnedAway {

	record Shopper(OrderOutcome outcome) implements Attempt {
	}

	record TurnedAway() implements Attempt {
	}

}
