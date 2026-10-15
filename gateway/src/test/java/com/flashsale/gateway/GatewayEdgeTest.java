package com.flashsale.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.junit.jupiter.api.extension.ExtendWith;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
class GatewayEdgeTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:8.10.2"))
			.withExposedPorts(6379);

	private static final HttpServer SALE = sale();

	private static final HttpServer COLLECTOR = collector();

	private static final AtomicInteger FORWARDS = new AtomicInteger();

	private static final AtomicReference<String> FORWARDED = new AtomicReference<>();

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

	@Autowired
	private Environment environment;

	@Autowired
	private StringRedisTemplate redis;

	@Autowired
	private AttemptEdge edge;

	static {
		REDIS.start();
	}

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("spring.data.redis.host", REDIS::getHost);
		registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
		registry.add("gateway.sale-url", () -> "http://127.0.0.1:" + SALE.getAddress().getPort());
		int collector = COLLECTOR.getAddress().getPort();
		registry.add("management.opentelemetry.tracing.export.otlp.endpoint",
				() -> "http://127.0.0.1:" + collector + "/v1/traces");
		registry.add("management.otlp.metrics.export.url", () -> "http://127.0.0.1:" + collector + "/v1/metrics");
		registry.add("management.opentelemetry.logging.export.otlp.endpoint",
				() -> "http://127.0.0.1:" + collector + "/v1/logs");
	}

	@BeforeEach
	void reset() {
		FORWARDS.set(0);
		FORWARDED.set(null);
		redis.execute((RedisCallback<Void>) connection -> {
			connection.serverCommands().flushAll();
			return null;
		});
	}

	@AfterAll
	static void stop() {
		SALE.stop(0);
		COLLECTOR.stop(0);
	}

	@Test
	void runsOnJava21WithoutTheReactorBreaker() {
		assertEquals(21, Runtime.version().feature());
		assertThrows(ClassNotFoundException.class,
				() -> Class.forName("io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator"));
		assertThrows(ClassNotFoundException.class,
				() -> Class.forName("org.springframework.web.reactive.DispatcherHandler"));
	}

	@Test
	void cannotQualifyIsAnsweredWithoutAForward(CapturedOutput output) throws Exception {
		redis.opsForHash().put("sale", "closed", "1");
		redis.opsForHash().put("sale", "open", "1");
		redis.opsForHash().put("sale", "soldOut", "1");
		redis.opsForHash().put("sale", "remaining", "0");
		JsonNode unidentified = attempt("   ");
		assertEquals("unidentified", unidentified.get("answer").asString());
		assertEquals(Set.of("answer"), Set.copyOf(unidentified.propertyNames()));
		assertEquals(0, FORWARDS.get());
		assertTrue(output.getOut().contains("answer=unidentified"));

		redis.opsForHash().put("sale", "closed", "0");
		redis.opsForHash().put("sale", "open", "0");
		assertEquals("not_open", attempt("ada").get("answer").asString());
		redis.opsForHash().put("sale", "open", "1");
		redis.opsForHash().put("sale", "closed", "1");
		assertEquals("closed", attempt("ada").get("answer").asString());
		redis.opsForHash().put("sale", "closed", "0");
		redis.opsForHash().put("sale", "soldOut", "0");
		redis.opsForHash().put("sale", "remaining", "0");
		assertEquals("sold_out", attempt("ada").get("answer").asString());
		assertEquals(0, FORWARDS.get());
		assertEquals("0", redis.opsForHash().get("sale", "remaining"));
	}

	@Test
	void qualifyForwardsOnlyWhenThePoolCanAnswer() throws Exception {
		redis.opsForHash().put("sale", "open", "1");
		redis.opsForHash().put("sale", "closed", "0");
		redis.opsForHash().put("sale", "soldOut", "0");
		redis.opsForHash().put("sale", "remaining", "2");
		HttpResponse<String> response = post("/attempts", "{\"userId\":\"ada\"}");
		assertEquals(200, response.statusCode());
		assertEquals(1, FORWARDS.get());
		assertTrue(FORWARDED.get().contains("\"userId\":\"ada\""));
		JsonNode body = JSON.readTree(response.body());
		assertEquals("qualified", body.get("answer").asString());
		assertEquals("11111111-1111-1111-1111-111111111111", body.get("qualificationId").asString());
		assertEquals("2", redis.opsForHash().get("sale", "remaining"));
	}

	@Test
	void readDoesNotTakeTheQualificationPath() throws Exception {
		redis.opsForHash().put("sale", "open", "1");
		redis.opsForHash().put("sale", "closed", "0");
		redis.opsForHash().put("sale", "soldOut", "0");
		redis.opsForHash().put("sale", "remaining", "4");
		resetStats();
		HttpResponse<String> response = get("/sale");
		assertEquals(200, response.statusCode());
		JsonNode body = JSON.readTree(response.body());
		assertEquals("1", body.get("open").asString());
		assertEquals("0", body.get("soldOut").asString());
		assertEquals("4", body.get("remaining").asString());
		assertEquals("4", redis.opsForHash().get("sale", "remaining"));
		assertEquals(0, FORWARDS.get());
		assertEquals(0, calls("eval") + calls("evalsha"));
		assertTrue(calls("hmget") + calls("hget") > 0);
	}

	@Test
	void missingFlagsAndBlankBodiesStayOnTheEdge() throws Exception {
		assertEquals("", edge.userId(null));
		assertEquals("", edge.userId("   "));
		assertEquals("", edge.userId("{}"));
		assertEquals("", edge.userId("{\"userId\":null}"));
		assertEquals(Optional.of("unidentified"), edge.answer(null));
		redis.opsForHash().put("sale", "open", "1");
		redis.opsForHash().put("sale", "closed", "0");
		redis.opsForHash().put("sale", "soldOut", "0");
		assertEquals("sold_out", attempt("ada").get("answer").asString());
		JsonNode partial = JSON.readTree(get("/sale").body());
		assertEquals("0", partial.get("remaining").asString());
		redis.delete("sale");
		JsonNode absent = JSON.readTree(get("/sale").body());
		assertEquals("0", absent.get("open").asString());
		assertEquals("0", absent.get("closed").asString());
		assertEquals("0", absent.get("soldOut").asString());
		assertEquals("0", absent.get("remaining").asString());
		assertEquals(0, FORWARDS.get());
	}

	@Test
	void malformedUserIdIsUnidentified() throws Exception {
		redis.opsForHash().put("sale", "open", "1");
		redis.opsForHash().put("sale", "closed", "0");
		redis.opsForHash().put("sale", "soldOut", "0");
		redis.opsForHash().put("sale", "remaining", "3");
		assertEquals("unidentified", attemptBody("{").get("answer").asString());
		assertEquals("unidentified", attemptBody("{\"userId\":12}").get("answer").asString());
		assertEquals(0, FORWARDS.get());
		assertEquals("3", redis.opsForHash().get("sale", "remaining"));
	}

	private JsonNode attempt(String userId) throws Exception {
		return attemptBody(JSON.writeValueAsString(java.util.Map.of("userId", userId)));
	}

	private JsonNode attemptBody(String json) throws Exception {
		HttpResponse<String> response = post("/attempts", json);
		assertEquals(200, response.statusCode());
		return JSON.readTree(response.body());
	}

	private HttpResponse<String> post(String path, String json) throws Exception {
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("http://127.0.0.1:" + port() + path))
				.timeout(Duration.ofSeconds(5))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(json))
				.build();
		return http.send(request, HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<String> get(String path) throws Exception {
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("http://127.0.0.1:" + port() + path))
				.timeout(Duration.ofSeconds(5))
				.GET()
				.build();
		return http.send(request, HttpResponse.BodyHandlers.ofString());
	}

	private int port() {
		return Integer.parseInt(environment.getProperty("local.server.port"));
	}

	private void resetStats() {
		redis.execute((RedisCallback<Object>) connection -> connection.execute("CONFIG",
				"RESETSTAT".getBytes(StandardCharsets.UTF_8)));
	}

	private int calls(String command) {
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

	private static HttpServer sale() {
		try {
			HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.createContext("/attempts", exchange -> {
				FORWARDS.incrementAndGet();
				FORWARDED.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
				byte[] response = "{\"answer\":\"qualified\",\"qualificationId\":\"11111111-1111-1111-1111-111111111111\"}"
						.getBytes(StandardCharsets.UTF_8);
				exchange.getResponseHeaders().add("Content-Type", "application/json");
				exchange.sendResponseHeaders(200, response.length);
				try (OutputStream out = exchange.getResponseBody()) {
					out.write(response);
				}
			});
			server.start();
			return server;
		}
		catch (IOException ex) {
			throw new ExceptionInInitializerError(ex);
		}
	}

	private static HttpServer collector() {
		try {
			HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.createContext("/", exchange -> {
				exchange.sendResponseHeaders(500, -1);
				exchange.close();
			});
			server.start();
			return server;
		}
		catch (IOException ex) {
			throw new ExceptionInInitializerError(ex);
		}
	}

}
