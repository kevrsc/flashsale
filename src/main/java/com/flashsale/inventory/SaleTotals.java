package com.flashsale.inventory;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.stereotype.Component;

@Component
class SaleTotals {

	private final MeterRegistry registry;

	private final AtomicLong inventoryStatements = new AtomicLong();

	SaleTotals(MeterRegistry registry) {
		this.registry = registry;
	}

	void inventoryStatement(String sql) {
		inventoryStatements.incrementAndGet();
		statement(sql);
	}

	long inventoryStatementCount() {
		return inventoryStatements.get();
	}

	void publisherStatement(String sql) {
		statement(sql);
	}

	void request(String result) {
		registry.counter("sale.attempts", "result", result).increment();
	}

	private void statement(String sql) {
		String kind = "SELECT".equals(keyword(sql)) ? "query" : "update";
		registry.counter("sale.statements", "kind", kind).increment();
	}

	private static String keyword(String sql) {
		int start = 0;
		int length = sql.length();
		while (start < length && Character.isWhitespace(sql.charAt(start))) {
			start++;
		}
		int end = start;
		while (end < length && Character.isLetter(sql.charAt(end))) {
			end++;
		}
		return sql.substring(start, end).toUpperCase(Locale.ROOT);
	}

}
