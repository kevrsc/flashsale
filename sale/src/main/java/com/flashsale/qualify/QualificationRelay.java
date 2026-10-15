package com.flashsale.qualify;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Component
class QualificationRelay implements HandlerInterceptor, WebMvcConfigurer {

	private static final Logger log = LoggerFactory.getLogger(QualificationRelay.class);

	private final StringRedisTemplate redis;
	private final KafkaTemplate<String, String> kafka;
	private final JsonMapper json;

	QualificationRelay(StringRedisTemplate redis, KafkaTemplate<String, String> kafka, JsonMapper json) {
		this.redis = redis;
		this.kafka = kafka;
		this.json = json;
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(this);
	}

	@Override
	public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
		Object document = request.getAttribute(AttemptApi.DOCUMENT);
		if (document instanceof String json) {
			Thread.startVirtualThread(() -> publishQuietly(json));
		}
	}

	@Scheduled(fixedDelayString = "${sale.relay-delay:200ms}")
	public void republish() {
		Map<Object, Object> pending = redis.opsForHash().entries("pending");
		for (Object value : pending.values()) {
			publishQuietly(String.valueOf(value));
		}
	}

	private void publishQuietly(String document) {
		try {
			JsonNode node = json.readTree(document);
			String qualificationId = node.path("qualificationId").asString();
			kafka.send("sale.qualification", qualificationId, document).get(2, TimeUnit.SECONDS);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			log.warn("qualification relay interrupted");
		}
		catch (RuntimeException ex) {
			log.warn("qualification relay deferred");
		}
		catch (Exception ex) {
			log.warn("qualification relay deferred");
		}
	}

}
