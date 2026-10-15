package com.flashsale.sale;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;

import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class OpenTelemetryLogs {

	OpenTelemetryLogs(OpenTelemetry openTelemetry) {
		OpenTelemetryAppender.install(openTelemetry);
	}

}
