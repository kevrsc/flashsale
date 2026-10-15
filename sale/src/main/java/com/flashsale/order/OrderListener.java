package com.flashsale.order;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
class OrderListener {

	private final OrderInsert orders;

	OrderListener(OrderInsert orders) {
		this.orders = orders;
	}

	@KafkaListener(topics = "sale.qualification", groupId = "sale-qualification")
	public void onQualification(String document, Acknowledgment acknowledgment) {
		orders.accept(document);
		acknowledgment.acknowledge();
	}

}
