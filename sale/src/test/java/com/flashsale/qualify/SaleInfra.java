package com.flashsale.qualify;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

final class SaleInfra {

	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:18.6"))
			.withDatabaseName("flashsale")
			.withUsername("flashsale")
			.withPassword("flashsale");

	static final KafkaContainer KAFKA = new KafkaContainer(
			DockerImageName.parse("apache/kafka:4.3.1").asCompatibleSubstituteFor("apache/kafka-native"));

	static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:8.10.2"))
			.withExposedPorts(6379);

	static {
		POSTGRES.start();
		KAFKA.start();
		REDIS.start();
	}

	private SaleInfra() {
	}

	static String[] properties(int collectorPort) {
		return new String[] {
				"spring.datasource.url=" + POSTGRES.getJdbcUrl(),
				"spring.datasource.username=" + POSTGRES.getUsername(),
				"spring.datasource.password=" + POSTGRES.getPassword(),
				"spring.kafka.bootstrap-servers=" + KAFKA.getBootstrapServers(),
				"spring.data.redis.host=" + REDIS.getHost(),
				"spring.data.redis.port=" + REDIS.getMappedPort(6379),
				"spring.data.redis.timeout=2s",
				"spring.data.redis.connect-timeout=2s",
				"sale.relay-delay=50ms",
				"sale.report-delay=50ms",
				"management.opentelemetry.tracing.export.otlp.endpoint=http://127.0.0.1:" + collectorPort + "/v1/traces",
				"management.otlp.metrics.export.url=http://127.0.0.1:" + collectorPort + "/v1/metrics",
				"management.opentelemetry.logging.export.otlp.endpoint=http://127.0.0.1:" + collectorPort + "/v1/logs",
				"management.opentelemetry.tracing.export.otlp.connect-timeout=200ms",
				"management.opentelemetry.tracing.export.otlp.timeout=500ms",
				"management.opentelemetry.logging.export.otlp.connect-timeout=200ms",
				"management.opentelemetry.logging.export.otlp.timeout=500ms"
		};
	}

}
