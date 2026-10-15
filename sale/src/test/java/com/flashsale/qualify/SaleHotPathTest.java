package com.flashsale.qualify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.flashsale.order.OrderInsert;
import com.flashsale.order.QualificationOrderGate;
import com.flashsale.sale.SaleApplication;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.junit.jupiter.api.extension.ExtendWith;

@SpringBootTest(classes = SaleApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class SaleHotPathTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final CollectorStub COLLECTOR = startCollector(false);

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

	@Autowired
	private org.springframework.core.env.Environment environment;

	@Autowired
	private StringRedisTemplate redis;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private OrderInsert orders;

	@Autowired
	private QualificationOrderGate gate;

	@MockitoSpyBean
	private SaleScript script;

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		for (String property : SaleInfra.properties(COLLECTOR.port())) {
			int split = property.indexOf('=');
			String key = property.substring(0, split);
			String value = property.substring(split + 1);
			registry.add(key, () -> value);
		}
	}

	@BeforeEach
	void resetPool() {
		orders.beforeInsert(null);
		reset(script);
		redis.execute((RedisCallback<Void>) connection -> {
			connection.serverCommands().flushAll();
			return null;
		});
		jdbc.update("TRUNCATE qualification_report_sent, orders");
	}

	@AfterEach
	void releaseInsertHook() {
		orders.beforeInsert(null);
	}

	@Test
	void runsOnJava21WithoutForbiddenStacks() {
		assertEquals(21, Runtime.version().feature());
		assertThrows(ClassNotFoundException.class,
				() -> Class.forName("org.springframework.web.reactive.DispatcherHandler"));
		assertThrows(ClassNotFoundException.class,
				() -> Class.forName("io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator"));
		assertThrows(ClassNotFoundException.class,
				() -> Class.forName("org.springframework.modulith.core.ApplicationModules"));
	}

	@Test
	void qualifyReturnsBeforeKafkaPostgresAndInventory(CapturedOutput output) throws Exception {
		load("sku-1", 2);
		start();
		CountDownLatch release = new CountDownLatch(1);
		orders.beforeInsert(() -> {
			try {
				if (!release.await(15, TimeUnit.SECONDS)) {
					throw new IllegalStateException("insert stayed paused");
				}
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("insert pause interrupted", ex);
			}
		});
		try {
			long started = System.nanoTime();
			HttpResponse<String> response = post("/attempts", "{\"userId\":\"ada\"}");
			long elapsed = System.nanoTime() - started;
			assertTrue(elapsed < Duration.ofSeconds(3).toNanos(), "attempt waited " + elapsed);
			assertEquals(200, response.statusCode());
			JsonNode body = JSON.readTree(response.body());
			assertEquals(Set.of("answer", "qualificationId"), Set.copyOf(body.propertyNames()));
			assertEquals("qualified", body.get("answer").asString());
			String qualificationId = body.get("qualificationId").asString();
			assertTrue(qualificationId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));
			assertEquals("1", hash("remaining"));
			assertEquals(0, orders());
			assertTrue(output.getOut().contains("answer=qualified qualificationId=" + qualificationId));
			release.countDown();
			await(() -> orders() == 1);
			gate.require(qualificationId);
			await(() -> reported(qualificationId) == 1);
			assertTrue(output.getOut().contains("order insert qualificationId=" + qualificationId));
		}
		finally {
			release.countDown();
		}
	}

	@Test
	void cannotQualifyLeavesThePool() throws Exception {
		load("sku-1", 2);
		assertEquals("not_open", attempt("ada").get("answer").asString());
		assertEquals("unidentified", attempt("  ").get("answer").asString());
		assertEquals("2", hash("remaining"));
		assertEquals(0, orders());
		start();
		assertEquals("unidentified", attempt("").get("answer").asString());
		assertEquals("2", hash("remaining"));
		String first = attempt("ada").get("qualificationId").asString();
		String second = attempt("ada").get("qualificationId").asString();
		assertFalse(first.equals(second));
		await(() -> orders() == 2);
		int soldOutBaseline = orders();
		assertEquals("sold_out", attempt("bea").get("answer").asString());
		assertEquals("unidentified", attempt(" ").get("answer").asString());
		assertEquals("0", hash("remaining"));
		assertEquals(soldOutBaseline, orders());
		String closedBody = close();
		assertEquals("closed", attempt("cy").get("answer").asString());
		assertEquals("unidentified", attempt(null).get("answer").asString());
		assertEquals("0", hash("remaining"));
		assertEquals(soldOutBaseline, orders());
		assertEquals(closedBody, close());
		gate.require(first);
		gate.require(second);
	}

	@Test
	void lastUnitAcrossTwoSaleInstances() throws Exception {
		load("sku-1", 1);
		start();
		resetCommandStats();
		try (ConfigurableApplicationContext second = secondSale()) {
			int otherPort = ((ServletWebServerApplicationContext) second).getWebServer().getPort();
			ExecutorService pool = Executors.newFixedThreadPool(2);
			try {
				Future<HttpResponse<String>> left = pool.submit(() -> post(port(), "/attempts", "{\"userId\":\"left\"}"));
				Future<HttpResponse<String>> right = pool.submit(() -> post(otherPort, "/attempts", "{\"userId\":\"right\"}"));
				JsonNode a = JSON.readTree(left.get(10, TimeUnit.SECONDS).body());
				JsonNode b = JSON.readTree(right.get(10, TimeUnit.SECONDS).body());
				List<String> answers = List.of(a.get("answer").asString(), b.get("answer").asString());
				assertEquals(1, answers.stream().filter("qualified"::equals).count());
				assertEquals(1, answers.stream().filter("sold_out"::equals).count());
			}
			finally {
				pool.shutdownNow();
			}
		}
		assertEquals("0", hash("remaining"));
		assertEquals("1", hash("soldOut"));
		assertEquals(0, commandCalls("hincrby"));
		assertEquals(2, commandCalls("hset"));
		assertTrue(commandCalls("eval") + commandCalls("evalsha") > 0);
		await(() -> orders() == 1);
	}

	@Test
	void retriedQualificationDocumentInsertsOneOrder() throws Exception {
		AtomicReference<AssertionError> ackError = new AtomicReference<>();
		doAnswer(invocation -> {
			String id = invocation.getArgument(0);
			Integer count = jdbc.queryForObject(
					"SELECT COUNT(*) FROM orders WHERE qualification_id = ?",
					Integer.class,
					id);
			if (count == null || count != 1) {
				ackError.set(new AssertionError("ack ran before the order committed: " + id));
			}
			return invocation.callRealMethod();
		}).when(script).ack(anyString());

		String missing = "dddddddd-dddd-dddd-dddd-dddddddddddd";
		assertThrows(RuntimeException.class, () -> orders.accept(
				"{\"qualificationId\":\"" + missing + "\",\"saleId\":\"sale\",\"userId\":null,\"itemId\":\"item-1\"}"));
		assertEquals(0, count(missing));
		assertNull(ackError.get());

		load("sku-1", 1);
		start();
		JsonNode qualified = attempt("ada");
		String id = qualified.get("qualificationId").asString();
		String document = script.pending(id);
		await(() -> count(id) == 1);
		orders.accept(document);
		assertEquals(1, count(id));
		assertNull(ackError.get());
		gate.require(id);
		assertThrows(IllegalStateException.class, () -> gate.require(UUID.randomUUID().toString()));
		assertThrows(IllegalStateException.class, () -> script.pending("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee"));
	}

	@Test
	void lifecycleHonorsTheLastLoadAndCloseRemaining() throws Exception {
		assertEquals(400, post("/sales/load", "{\"itemId\":\" \",\"allotment\":1}").statusCode());
		assertEquals(400, post("/sales/load", "{\"itemId\":\"sku\",\"allotment\":-1}").statusCode());
		assertEquals(400, post("/sales/load", "{}").statusCode());
		assertEquals(400, post("/sales/load", "{\"itemId\":\"sku\"}").statusCode());
		assertEquals(409, post("/sales/start", "{}").statusCode());
		load("first", 1);
		load("second", 4);
		start();
		assertEquals(409, post("/sales/load", "{\"itemId\":\"third\",\"allotment\":9}").statusCode());
		assertEquals(409, post("/sales/start", "{}").statusCode());
		assertEquals("4", hash("remaining"));
		assertEquals("second", hash("itemId"));
		attempt("ada");
		await(() -> orders() == 1);
		String firstClose = close();
		JsonNode report = JSON.readTree(firstClose);
		assertEquals(Set.of("saleId", "itemId", "remaining"), Set.copyOf(report.propertyNames()));
		assertEquals("sale", report.get("saleId").asString());
		assertEquals("second", report.get("itemId").asString());
		assertEquals(3, report.get("remaining").asInt());
		assertEquals(firstClose, close());
		assertEquals("3", hash("remaining"));
		assertEquals("closed", attempt("bea").get("answer").asString());
		redis.opsForHash().put("pending", "bad", "not-json");
		Thread.sleep(200);
	}

	@Test
	void quotedUserIdIsStoredOnce() throws Exception {
		load("sku-1", 1);
		start();
		JsonNode body = attempt("a\"b");
		String id = body.get("qualificationId").asString();
		await(() -> count(id) == 1);
		String userId = jdbc.queryForObject("SELECT user_id FROM orders WHERE qualification_id = ?", String.class, id);
		assertEquals("a\"b", userId);
		gate.require(id);
	}

	private JsonNode attempt(String userId) throws Exception {
		String json = userId == null ? "{}" : JSON.writeValueAsString(java.util.Map.of("userId", userId));
		HttpResponse<String> response = post("/attempts", json);
		assertEquals(200, response.statusCode());
		JsonNode body = JSON.readTree(response.body());
		if (!"qualified".equals(body.path("answer").asString())) {
			assertEquals(Set.of("answer"), Set.copyOf(body.propertyNames()));
		}
		return body;
	}

	private void load(String itemId, int allotment) throws Exception {
		String json = "{\"itemId\":\"" + itemId + "\",\"allotment\":" + allotment + "}";
		assertEquals(200, post("/sales/load", json).statusCode());
	}

	private void start() throws Exception {
		assertEquals(200, post("/sales/start", "{}").statusCode());
	}

	private String close() throws Exception {
		HttpResponse<String> response = post("/sales/close", "{}");
		assertEquals(200, response.statusCode());
		return response.body();
	}

	private HttpResponse<String> post(String path, String json) throws Exception {
		return post(port(), path, json);
	}

	private HttpResponse<String> post(int targetPort, String path, String json) throws Exception {
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("http://127.0.0.1:" + targetPort + path))
				.timeout(Duration.ofSeconds(5))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(json))
				.build();
		return http.send(request, HttpResponse.BodyHandlers.ofString());
	}

	private int port() {
		return Integer.parseInt(environment.getProperty("local.server.port"));
	}

	private ConfigurableApplicationContext secondSale() {
		String[] configured = SaleInfra.properties(COLLECTOR.port());
		String[] args = new String[configured.length + 1];
		args[0] = "--server.port=0";
		for (int index = 0; index < configured.length; index++) {
			args[index + 1] = "--" + configured[index];
		}
		return new SpringApplicationBuilder(SaleApplication.class).run(args);
	}

	private String hash(String field) {
		Object value = redis.opsForHash().get("sale", field);
		return value == null ? null : value.toString();
	}

	private int orders() {
		return jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class);
	}

	private int count(String qualificationId) {
		return jdbc.queryForObject(
				"SELECT COUNT(*) FROM orders WHERE qualification_id = ?",
				Integer.class,
				qualificationId);
	}

	private int reported(String qualificationId) {
		return jdbc.queryForObject(
				"SELECT COUNT(*) FROM qualification_report_sent WHERE qualification_id = ?",
				Integer.class,
				qualificationId);
	}

	private void resetCommandStats() {
		redis.execute((RedisCallback<Object>) connection -> connection.execute("CONFIG",
				"RESETSTAT".getBytes(StandardCharsets.UTF_8)));
	}

	private int commandCalls(String command) {
		return commandStatCalls(redis, command);
	}

	private static int commandStatCalls(StringRedisTemplate template, String command) {
		Object raw = template.execute((RedisCallback<Object>) connection -> connection.execute("INFO",
				"commandstats".getBytes(StandardCharsets.UTF_8)));
		String text = raw instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : String.valueOf(raw);
		String prefix = "cmdstat_" + command + ":";
		for (String line : text.split("\\R")) {
			if (line.regionMatches(true, 0, prefix, 0, prefix.length())) {
				String stats = line.substring(prefix.length());
				for (String part : stats.split(",")) {
					if (part.startsWith("calls=")) {
						return Integer.parseInt(part.substring("calls=".length()));
					}
				}
			}
		}
		return 0;
	}

	private static void await(BooleanSupplier condition) throws InterruptedException {
		long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
		while (System.nanoTime() < deadline) {
			if (condition.getAsBoolean()) {
				return;
			}
			Thread.sleep(50);
		}
		fail("timed out");
	}

	private static CollectorStub startCollector(boolean refuse) {
		try {
			return refuse ? CollectorStub.refusing() : CollectorStub.accepting();
		}
		catch (Exception ex) {
			throw new ExceptionInInitializerError(ex);
		}
	}

}
