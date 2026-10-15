package com.flashsale.qualify;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AttemptApi {

	public static final String DOCUMENT = "sale.qualification.document";

	private static final Logger log = LoggerFactory.getLogger(AttemptApi.class);

	private final SaleScript script;
	private final JsonMapper json;
	private final MeterRegistry meters;

	public AttemptApi(SaleScript script, JsonMapper json, MeterRegistry meters) {
		this.script = script;
		this.json = json;
		this.meters = meters;
	}

	@PostMapping(value = "/attempts", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<String> attempt(@RequestBody(required = false) String body, HttpServletRequest request) {
		String result = script.attempt(userId(body));
		JsonNode node = json.readTree(result);
		String answer = node.path("answer").asString("");
		meters.counter("sale.qualification", "answer", answer).increment();
		if ("qualified".equals(answer)) {
			String qualificationId = node.path("qualificationId").asString();
			attach(request, qualificationId);
			log.info("answer=qualified qualificationId={}", qualificationId);
		}
		else {
			log.info("answer={}", answer);
		}
		return ResponseEntity.ok(result);
	}

	static String userId(String body) {
		return UserIds.read(body);
	}

	private void attach(HttpServletRequest request, String qualificationId) {
		try {
			request.setAttribute(DOCUMENT, script.pending(qualificationId));
		}
		catch (RuntimeException ex) {
			log.warn("qualification document unavailable qualificationId={}", qualificationId);
		}
	}

}
