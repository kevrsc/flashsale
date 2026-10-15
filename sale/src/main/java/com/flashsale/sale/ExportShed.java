package com.flashsale.sale;

import java.util.Collection;
import java.util.concurrent.TimeUnit;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class ExportShed {

	@Bean
	static BeanPostProcessor exportShedProcessor(org.springframework.beans.factory.ObjectProvider<DownstreamGuard> guards) {
		return new BeanPostProcessor() {
			@Override
			public Object postProcessAfterInitialization(Object bean, String beanName) {
				if (!(bean instanceof SpanExporter) && !(bean instanceof LogRecordExporter)) {
					return bean;
				}
				return wrap(bean, guards.getObject());
			}
		};
	}

	static Object wrap(Object bean, DownstreamGuard guard) {
		if (bean instanceof SheddingSpanExporter || bean instanceof SheddingLogExporter) {
			return bean;
		}
		if (bean instanceof SpanExporter exporter) {
			return new SheddingSpanExporter(exporter, guard);
		}
		if (bean instanceof LogRecordExporter exporter) {
			return new SheddingLogExporter(exporter, guard);
		}
		return bean;
	}

	static final class SheddingSpanExporter implements SpanExporter {

		private final SpanExporter delegate;
		private final DownstreamGuard guard;

		SheddingSpanExporter(SpanExporter delegate, DownstreamGuard guard) {
			this.delegate = delegate;
			this.guard = guard;
		}

		@Override
		public CompletableResultCode export(Collection<SpanData> spans) {
			guard.export(() -> join(delegate.export(spans)));
			return CompletableResultCode.ofSuccess();
		}

		@Override
		public CompletableResultCode flush() {
			return delegate.flush();
		}

		@Override
		public CompletableResultCode shutdown() {
			return delegate.shutdown();
		}

	}

	static final class SheddingLogExporter implements LogRecordExporter {

		private final LogRecordExporter delegate;
		private final DownstreamGuard guard;

		SheddingLogExporter(LogRecordExporter delegate, DownstreamGuard guard) {
			this.delegate = delegate;
			this.guard = guard;
		}

		@Override
		public CompletableResultCode export(Collection<LogRecordData> logs) {
			guard.export(() -> join(delegate.export(logs)));
			return CompletableResultCode.ofSuccess();
		}

		@Override
		public CompletableResultCode flush() {
			return delegate.flush();
		}

		@Override
		public CompletableResultCode shutdown() {
			return delegate.shutdown();
		}

	}

	private static void join(CompletableResultCode result) {
		CompletableResultCode finished = result.join(2, TimeUnit.SECONDS);
		if (!finished.isSuccess()) {
			throw new IllegalStateException("collector refused export");
		}
	}

}
