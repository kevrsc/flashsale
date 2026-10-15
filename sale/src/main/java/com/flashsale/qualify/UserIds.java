package com.flashsale.qualify;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

final class UserIds {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private UserIds() {
	}

	static String read(String body) {
		if (body == null || body.isBlank()) {
			return "";
		}
		try {
			JsonNode node = JSON.readTree(body);
			JsonNode userId = node.get("userId");
			if (userId == null || userId.isNull() || !userId.isString()) {
				return "";
			}
			String value = userId.asString();
			return value.isBlank() ? "" : value;
		}
		catch (RuntimeException ex) {
			return "";
		}
	}

}
