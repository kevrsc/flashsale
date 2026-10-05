package com.flashsale.inventory;

import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;

@Component
class SaleTotals {

	private static final Logger log = LoggerFactory.getLogger(SaleTotals.class);

	private final Object lock = new Object();

	private long requests;

	private long queries;

	private long updates;

	void inventoryStatement(String sql) {
		synchronized (lock) {
			count(sql);
		}
	}

	void publisherStatement(String sql) {
		synchronized (lock) {
			count(sql);
			write();
		}
	}

	void request() {
		synchronized (lock) {
			requests++;
			write();
		}
	}

	private void count(String sql) {
		if ("SELECT".equals(keyword(sql))) {
			queries++;
		}
		else {
			updates++;
		}
	}

	private void write() {
		log.info("requests={} queries={} updates={}", requests, queries, updates);
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
