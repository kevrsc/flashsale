package com.flashsale.inventory;

import org.springframework.modulith.events.Externalized;

@Externalized("inventory.order-accepted::#{orderId()}")
public record OrderAccepted(String orderId, String userId, String itemId, int unitsDeducted) {
}
