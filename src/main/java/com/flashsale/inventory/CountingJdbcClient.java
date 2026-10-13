package com.flashsale.inventory;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.JdbcClient.MappedQuerySpec;
import org.springframework.jdbc.core.simple.JdbcClient.ResultQuerySpec;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;

class CountingJdbcClient implements JdbcClient {

	private final JdbcClient delegate;

	private final SaleTotals totals;

	CountingJdbcClient(JdbcClient delegate, SaleTotals totals) {
		this.delegate = delegate;
		this.totals = totals;
	}

	@Override
	public StatementSpec sql(String sql) {
		return counting(delegate.sql(sql), sql, StatementSpec.class);
	}

	@SuppressWarnings("unchecked")
	private <T> T counting(T target, String sql, Class<T> type) {
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, (proxy, method, args) -> {
			if (method.getDeclaringClass() == Object.class) {
				return invoke(method, target, args);
			}
			if (method.isDefault()) {
				// The default methods on these specs take no arguments.
				return InvocationHandler.invokeDefault(proxy, method);
			}
			if (executes(method)) {
				totals.publisherStatement(sql);
			}
			Object result = invoke(method, target, args);
			if (result instanceof StatementSpec) {
				return proxy;
			}
			if (result instanceof ResultQuerySpec resultQuery) {
				return counting(resultQuery, sql, ResultQuerySpec.class);
			}
			if (result instanceof MappedQuerySpec<?> mapped) {
				return counting(mapped, sql, MappedQuerySpec.class);
			}
			return result;
		});
	}

	private static boolean executes(Method method) {
		Class<?> owner = method.getDeclaringClass();
		if (owner == StatementSpec.class) {
			if ("update".equals(method.getName())) {
				return true;
			}
			if ("query".equals(method.getName()) && method.getParameterCount() == 1) {
				Class<?> parameter = method.getParameterTypes()[0];
				return RowCallbackHandler.class.isAssignableFrom(parameter)
						|| ResultSetExtractor.class.isAssignableFrom(parameter);
			}
			return false;
		}
		return owner == ResultQuerySpec.class || owner == MappedQuerySpec.class;
	}

	private static Object invoke(Method method, Object target, Object[] args) throws Throwable {
		try {
			return method.invoke(target, args);
		}
		catch (InvocationTargetException ex) {
			// Method.invoke always wraps the target exception.
			throw ex.getCause();
		}
	}

}
