package com.flashsale.inventory;

import java.util.function.Consumer;

import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.PreparedStatementCreatorFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

class CountingNamedParameterJdbcTemplate extends NamedParameterJdbcTemplate {

	private final SaleTotals totals;

	CountingNamedParameterJdbcTemplate(JdbcOperations classicJdbcTemplate, SaleTotals totals) {
		super(classicJdbcTemplate);
		this.totals = totals;
	}

	@Override
	protected PreparedStatementCreator getPreparedStatementCreator(String sql, SqlParameterSource paramSource,
			Consumer<PreparedStatementCreatorFactory> customizer) {
		totals.inventoryStatement(sql);
		return super.getPreparedStatementCreator(sql, paramSource, customizer);
	}

}
