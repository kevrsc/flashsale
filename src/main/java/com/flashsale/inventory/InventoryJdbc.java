package com.flashsale.inventory;

import javax.sql.DataSource;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration(proxyBeanMethods = false)
class InventoryJdbc {

	@Bean
	@Primary
	NamedParameterJdbcTemplate namedParameterJdbcTemplate(JdbcTemplate jdbcTemplate, SaleTotals totals) {
		return new CountingNamedParameterJdbcTemplate(jdbcTemplate, totals);
	}

	@Bean(name = "outboxJdbcClient", autowireCandidate = false)
	JdbcClient outboxJdbcClient(DataSource dataSource, SaleTotals totals) {
		return new CountingJdbcClient(JdbcClient.create(dataSource), totals);
	}

}
