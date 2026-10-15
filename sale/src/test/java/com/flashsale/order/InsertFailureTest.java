package com.flashsale.order;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import tools.jackson.databind.json.JsonMapper;

import org.junit.jupiter.api.Test;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import com.flashsale.qualify.SaleScript;
import com.flashsale.sale.TestBreakers;

class InsertFailureTest {

	@Test
	void missingDocumentFieldDoesNotAck() {
		JdbcTemplate jdbc = mock(JdbcTemplate.class);
		when(jdbc.update(anyString(), any(Object[].class)))
				.thenThrow(new DataIntegrityViolationException("null column"));
		PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
		when(transactions.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
		SaleScript script = mock(SaleScript.class);
		OrderInsert orders = new OrderInsert(jdbc, transactions, script, JsonMapper.builder().build(),
				TestBreakers.guard());
		orders.beforeInsert(null);
		assertThrows(IllegalStateException.class, () -> orders.accept(
				"{\"qualificationId\":\"q-1\",\"saleId\":\"sale\",\"userId\":\"ada\"}"));
		verify(script, never()).ack(any());
	}

	@Test
	void gateRejectsAMissingCount() {
		JdbcTemplate jdbc = mock(JdbcTemplate.class);
		when(jdbc.queryForObject(anyString(), any(Class.class), any())).thenReturn(null);
		QualificationOrderGate gate = new QualificationOrderGate(jdbc);
		assertThrows(IllegalStateException.class, () -> gate.require("q-1"));
	}

}
