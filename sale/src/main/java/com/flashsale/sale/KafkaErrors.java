package com.flashsale.sale;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration(proxyBeanMethods = false)
class KafkaErrors {

	@Bean
	DefaultErrorHandler saleKafkaErrorHandler() {
		return new DefaultErrorHandler(new FixedBackOff(200L, FixedBackOff.UNLIMITED_ATTEMPTS));
	}

}
