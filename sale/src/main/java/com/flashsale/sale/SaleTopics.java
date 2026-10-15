package com.flashsale.sale;

import org.apache.kafka.clients.admin.NewTopic;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration(proxyBeanMethods = false)
class SaleTopics {

	@Bean
	NewTopic qualificationTopic() {
		return topic("sale.qualification");
	}

	@Bean
	NewTopic qualificationReportTopic() {
		return topic("sale.qualification-report");
	}

	@Bean
	NewTopic closeReportTopic() {
		return topic("sale.close-report");
	}

	private static NewTopic topic(String name) {
		return TopicBuilder.name(name).partitions(1).replicas(1).build();
	}

}
