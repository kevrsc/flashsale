package com.flashsale.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

class SaleApiRoutesTest {

	@Test
	void rootToolchainIsJava17AndSaleApiHasNoSeckillRoute() {
		assertEquals(17, Runtime.version().feature());
		for (Method method : SaleApi.class.getDeclaredMethods()) {
			assertPaths(method.getAnnotation(RequestMapping.class));
			assertPaths(method.getAnnotation(GetMapping.class));
			assertPaths(method.getAnnotation(PostMapping.class));
			assertPaths(method.getAnnotation(PutMapping.class));
			assertPaths(method.getAnnotation(DeleteMapping.class));
		}
	}

	private static void assertPaths(RequestMapping mapping) {
		if (mapping == null) {
			return;
		}
		assertNoSeckill(mapping.value());
		assertNoSeckill(mapping.path());
	}

	private static void assertPaths(GetMapping mapping) {
		if (mapping == null) {
			return;
		}
		assertNoSeckill(mapping.value());
		assertNoSeckill(mapping.path());
	}

	private static void assertPaths(PostMapping mapping) {
		if (mapping == null) {
			return;
		}
		assertNoSeckill(mapping.value());
		assertNoSeckill(mapping.path());
	}

	private static void assertPaths(PutMapping mapping) {
		if (mapping == null) {
			return;
		}
		assertNoSeckill(mapping.value());
		assertNoSeckill(mapping.path());
	}

	private static void assertPaths(DeleteMapping mapping) {
		if (mapping == null) {
			return;
		}
		assertNoSeckill(mapping.value());
		assertNoSeckill(mapping.path());
	}

	private static void assertNoSeckill(String[] paths) {
		if (paths == null) {
			return;
		}
		for (String path : paths) {
			assertFalse(path.contains("attempt") || path.startsWith("/sales"), path);
		}
	}

}
