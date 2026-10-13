package com.flashsale.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.JdbcClient.MappedQuerySpec;
import org.springframework.jdbc.core.simple.JdbcClient.ResultQuerySpec;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;
import org.springframework.jdbc.support.KeyHolder;

class InventoryCoverageTest {

	@Test
	void statementKindsCountLeadingSpaceAndANonQuery() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		SaleTotals totals = new SaleTotals(registry);

		totals.inventoryStatement(" SELECT 1");
		totals.inventoryStatement("");
		totals.inventoryStatement(" 1");
		totals.publisherStatement("UPDATE t");

		assertEquals(1, registry.counter("sale.statements", "kind", "query").count());
		assertEquals(3, registry.counter("sale.statements", "kind", "update").count());
	}

	@Test
	void admitCountsEachResultPlaceCanReturn() {
		AtomicInteger hints = new AtomicInteger();
		Orders hintAfterPermit = new Orders(null, null) {
			@Override
			public boolean soldOutHint() {
				return hints.getAndIncrement() > 0;
			}
		};
		Attempt hinted = new Admission(1, hintAfterPermit, new SaleTotals(new SimpleMeterRegistry())).admit("user", 1);
		assertEquals(OrderOutcome.soldOut(), ((Attempt.Shopper) hinted).outcome());

		Orders notAccepted = new Orders(null, null) {
			@Override
			public boolean soldOutHint() {
				return false;
			}

			@Override
			public OrderOutcome place(String userId, int units) {
				return OrderOutcome.notAccepted();
			}
		};
		Attempt rejected = new Admission(1, notAccepted, new SaleTotals(new SimpleMeterRegistry())).admit("user", 1);
		assertEquals(OrderOutcome.notAccepted(), ((Attempt.Shopper) rejected).outcome());

		Orders unknown = new Orders(null, null) {
			@Override
			public boolean soldOutHint() {
				return false;
			}

			@Override
			public OrderOutcome place(String userId, int units) {
				return new OrderOutcome("no such sentence");
			}
		};
		assertThrows(IllegalStateException.class,
				() -> new Admission(1, unknown, new SaleTotals(new SimpleMeterRegistry())).admit("user", 1));

		Orders failing = new Orders(null, null) {
			@Override
			public boolean soldOutHint() {
				return false;
			}

			@Override
			public OrderOutcome place(String userId, int units) {
				throw new IllegalStateException("place failed");
			}
		};
		assertThrows(IllegalStateException.class,
				() -> new Admission(1, failing, new SaleTotals(new SimpleMeterRegistry())).admit("user", 1));
	}

	@Test
	void publisherProxyCountsEachStatementShape() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		SaleTotals totals = new SaleTotals(registry);
		JdbcClient delegate = mock(JdbcClient.class);
		StatementSpec spec = mock(StatementSpec.class);
		ResultQuerySpec rows = mock(ResultQuerySpec.class);
		@SuppressWarnings("unchecked")
		MappedQuerySpec<String> mapped = mock(MappedQuerySpec.class);
		when(delegate.sql(anyString())).thenReturn(spec);
		when(spec.withFetchSize(anyInt())).thenReturn(spec);
		when(spec.query()).thenReturn(rows);
		when(spec.query(any(Class.class))).thenReturn(mapped);
		when(spec.query(any(RowMapper.class))).thenReturn(mapped);
		when(spec.query(any(ResultSetExtractor.class))).thenReturn(7);
		when(spec.update()).thenReturn(1).thenThrow(new IllegalStateException("update failed"));
		when(rows.listOfRows()).thenReturn(List.of());
		when(rows.singleColumn()).thenReturn(List.of("cell"));
		when(mapped.list()).thenReturn(List.of("row"));
		when(spec.update(any(KeyHolder.class))).thenThrow(new IllegalStateException("keys"));

		CountingJdbcClient client = new CountingJdbcClient(delegate, totals);
		StatementSpec statement = client.sql("SELECT 1");

		assertInstanceOf(StatementSpec.class, statement.withFetchSize(1));
		statement.toString();
		statement.query().listOfRows();
		assertEquals("cell", statement.query().singleValue());
		assertEquals(List.of("row"), statement.query(String.class).list());
		assertEquals(Set.of("row"), statement.query(String.class).set());
		statement.query((RowCallbackHandler) resultSet -> {
		});
		assertEquals(7, statement.query((ResultSetExtractor<Integer>) resultSet -> 7));
		statement.query((RowMapper<String>) (resultSet, row) -> "x").list();
		assertEquals(1, statement.update());
		assertThrows(IllegalStateException.class, () -> statement.update());
		assertThrows(IllegalStateException.class, () -> statement.update(mock(KeyHolder.class)));

		assertEquals(10, registry.counter("sale.statements", "kind", "query").count());
	}

}
