package com.flashsale.qualify;

import java.nio.charset.StandardCharsets;
import java.util.List;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class SaleScript {

	private final StringRedisTemplate redis;
	private final JsonMapper json;
	private final DefaultRedisScript<String> script;

	public SaleScript(StringRedisTemplate redis, JsonMapper json) {
		this.redis = redis;
		this.json = json;
		this.script = new DefaultRedisScript<>();
		this.script.setResultType(String.class);
		try {
			this.script.setScriptText(new ClassPathResource("sale.lua").getContentAsString(StandardCharsets.UTF_8));
		}
		catch (Exception ex) {
			throw new IllegalStateException("sale script is missing", ex);
		}
	}

	public boolean load(String itemId, int allotment) {
		return applied(eval("load", itemId, Integer.toString(allotment)));
	}

	public boolean start() {
		return applied(eval("start"));
	}

	public String attempt(String userId) {
		return eval("attempt", userId == null ? "" : userId);
	}

	public String close() {
		return eval("close");
	}

	public void ack(String qualificationId) {
		eval("ack", qualificationId);
	}

	public String pending(String qualificationId) {
		Object value = redis.opsForHash().get("pending", qualificationId);
		if (value == null) {
			throw new IllegalStateException("missing qualification document " + qualificationId);
		}
		return value.toString();
	}

	private boolean applied(String payload) {
		JsonNode node = json.readTree(payload);
		return node.path("applied").asBoolean(false);
	}

	private String eval(String... args) {
		String result = redis.execute(script, List.of(), (Object[]) args);
		if (result == null) {
			throw new IllegalStateException("sale script returned nothing");
		}
		return result;
	}

}
