package com.flashsale.gateway;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.function.ServerResponse;

import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class AttemptEdge {

	private static final Logger log = LoggerFactory.getLogger(AttemptEdge.class);

	private static final List<Object> FIELDS = List.of("open", "closed", "soldOut", "remaining");

	private final StringRedisTemplate redis;
	private final JsonMapper json;
	private final MeterRegistry meters;

	public AttemptEdge(StringRedisTemplate redis, JsonMapper json, MeterRegistry meters) {
		this.redis = redis;
		this.json = json;
		this.meters = meters;
	}

	public Optional<String> answer(String userId) {
		if (userId == null || userId.isBlank()) {
			return Optional.of("unidentified");
		}
		Map<String, String> sale = read();
		if ("1".equals(sale.get("closed"))) {
			return Optional.of("closed");
		}
		if ("0".equals(sale.get("open"))) {
			return Optional.of("not_open");
		}
		if ("1".equals(sale.get("soldOut")) || "0".equals(sale.get("remaining"))) {
			return Optional.of("sold_out");
		}
		return Optional.empty();
	}

	public Map<String, String> read() {
		List<Object> values = redis.opsForHash().multiGet("sale", FIELDS);
		Map<String, String> body = new LinkedHashMap<>();
		for (int i = 0; i < FIELDS.size(); i++) {
			body.put(FIELDS.get(i).toString(), field(values.get(i)));
		}
		return body;
	}

	public void logAnswer(String answer) {
		meters.counter("sale.qualification", "answer", answer).increment();
		log.info("answer={}", answer);
	}

	public String userId(String body) {
		if (body == null || body.isBlank()) {
			return "";
		}
		try {
			JsonNode node = json.readTree(body);
			JsonNode userId = node.get("userId");
			if (userId == null || userId.isNull() || !userId.isString()) {
				return "";
			}
			return userId.asString();
		} catch (RuntimeException ex) {
			return "";
		}
	}

	public ServerResponse answered(String answer) {
		logAnswer(answer);
		return ServerResponse.ok()
				.contentType(MediaType.APPLICATION_JSON)
				.body("{\"answer\":\"" + answer + "\"}");
	}

	private static String field(Object value) {
		return value == null ? "0" : value.toString();
	}

}
