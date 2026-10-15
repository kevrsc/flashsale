package com.flashsale.sale;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {
		"com.flashsale.sale",
		"com.flashsale.qualify",
		"com.flashsale.order",
		"com.flashsale.report"
})
@EnableScheduling
@EnableKafka
public class SaleApplication {

	public static void main(String[] args) {
		SpringApplication.run(SaleApplication.class, args);
	}

}
