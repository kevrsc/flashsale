package com.flashsale.order;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class QualificationOrderGate {

	private final JdbcTemplate jdbc;

	public QualificationOrderGate(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public void require(String qualificationId) {
		Integer count = jdbc.queryForObject(
				"SELECT COUNT(*) FROM orders WHERE qualification_id = ?",
				Integer.class,
				qualificationId);
		if (count == null || count != 1) {
			throw new IllegalStateException("qualification id has no order row: " + qualificationId);
		}
	}

}
