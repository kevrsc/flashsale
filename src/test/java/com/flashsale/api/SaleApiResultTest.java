package com.flashsale.api;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flashsale.inventory.OrderOutcome;

import org.junit.jupiter.api.Test;

class SaleApiResultTest {

	@Test
	void unknownOutcomeHasNoSaleResult() {
		IllegalStateException ex = assertThrows(IllegalStateException.class,
				() -> SaleApi.resultOf(new OrderOutcome("no such sentence")));
		assertTrue(ex.getMessage().contains("no such sentence"));
	}

}
