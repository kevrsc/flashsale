package com.flashsale.qualify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import tools.jackson.databind.json.JsonMapper;

import org.junit.jupiter.api.Test;

import com.flashsale.sale.SaleApplication;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(classes = SaleApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ExportDownTest {

	private static final CollectorStub COLLECTOR = start();

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

	@Autowired
	private Environment environment;

	@Autowired
	private StringRedisTemplate redis;

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		for (String property : SaleInfra.properties(COLLECTOR.port())) {
			int split = property.indexOf('=');
			String key = property.substring(0, split);
			String value = property.substring(split + 1);
			registry.add(key, () -> value);
		}
	}

	@Test
	void exportDownStillQualifies() throws Exception {
		assertEquals(200, post("/sales/load", "{\"itemId\":\"sku-1\",\"allotment\":1}", Duration.ofSeconds(30)).statusCode());
		assertEquals(200, post("/sales/start", "{}", Duration.ofSeconds(30)).statusCode());
		long started = System.nanoTime();
		HttpResponse<String> response = post("/attempts", "{\"userId\":\"ada\"}", Duration.ofSeconds(3));
		long elapsed = System.nanoTime() - started;
		assertEquals(200, response.statusCode());
		assertEquals("qualified", JsonMapper.builder().build().readTree(response.body()).get("answer").asString());
		assertTrue(elapsed < Duration.ofSeconds(3).toNanos(), "attempt waited " + elapsed);
		assertEquals("0", redis.opsForHash().get("sale", "remaining"));
	}

	private HttpResponse<String> post(String path, String json, Duration timeout) throws Exception {
		int port = Integer.parseInt(environment.getProperty("local.server.port"));
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("http://127.0.0.1:" + port + path))
				.timeout(timeout)
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(json))
				.build();
		return http.send(request, HttpResponse.BodyHandlers.ofString());
	}

	private static CollectorStub start() {
		try {
			return CollectorStub.refusing();
		}
		catch (Exception ex) {
			throw new ExceptionInInitializerError(ex);
		}
	}

}
