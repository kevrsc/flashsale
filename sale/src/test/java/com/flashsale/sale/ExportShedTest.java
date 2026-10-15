package com.flashsale.sale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;

class ExportShedTest {

	@Test
	void wrapShedsFailedSpanAndLogExport() {
		DownstreamGuard guard = TestBreakers.guard();
		successfulExports(guard);
		DownstreamGuard failing = TestBreakers.guard();
		failedExportIsShed(failing);
	}

	private static void successfulExports(DownstreamGuard guard) {
		AtomicInteger spans = new AtomicInteger();
		SpanExporter spanExporter = new SpanExporter() {
			@Override
			public CompletableResultCode export(Collection<SpanData> data) {
				spans.incrementAndGet();
				return CompletableResultCode.ofSuccess();
			}

			@Override
			public CompletableResultCode flush() {
				return CompletableResultCode.ofSuccess();
			}

			@Override
			public CompletableResultCode shutdown() {
				return CompletableResultCode.ofSuccess();
			}
		};
		AtomicInteger logs = new AtomicInteger();
		LogRecordExporter logExporter = new LogRecordExporter() {
			@Override
			public CompletableResultCode export(Collection<LogRecordData> data) {
				logs.incrementAndGet();
				return CompletableResultCode.ofSuccess();
			}

			@Override
			public CompletableResultCode flush() {
				return CompletableResultCode.ofSuccess();
			}

			@Override
			public CompletableResultCode shutdown() {
				return CompletableResultCode.ofSuccess();
			}
		};
		Object wrappedSpan = ExportShed.wrap(spanExporter, guard);
		Object wrappedLog = ExportShed.wrap(logExporter, guard);
		assertSame(wrappedSpan, ExportShed.wrap(wrappedSpan, guard));
		assertSame("other", ExportShed.wrap("other", guard));
		SpanExporter sheddingSpans = (SpanExporter) wrappedSpan;
		sheddingSpans.export(List.of());
		sheddingSpans.export(List.of());
		assertEquals(2, spans.get());
		assertTrue(sheddingSpans.flush().isSuccess());
		assertTrue(sheddingSpans.shutdown().isSuccess());
		LogRecordExporter sheddingLogs = (LogRecordExporter) wrappedLog;
		sheddingLogs.export(List.of());
		assertEquals(1, logs.get());
		assertTrue(sheddingLogs.flush().isSuccess());
		assertTrue(sheddingLogs.shutdown().isSuccess());
	}

	private static void failedExportIsShed(DownstreamGuard guard) {
		AtomicInteger spans = new AtomicInteger();
		SpanExporter exporter = new SpanExporter() {
			@Override
			public CompletableResultCode export(Collection<SpanData> data) {
				spans.incrementAndGet();
				return CompletableResultCode.ofFailure();
			}

			@Override
			public CompletableResultCode flush() {
				return CompletableResultCode.ofSuccess();
			}

			@Override
			public CompletableResultCode shutdown() {
				return CompletableResultCode.ofSuccess();
			}
		};
		SpanExporter shedding = (SpanExporter) ExportShed.wrap(exporter, guard);
		shedding.export(List.of());
		shedding.export(List.of());
		assertEquals(1, spans.get());
	}

	@Test
	void processorWrapsExportersAndLeavesOtherBeans() {
		DownstreamGuard guard = TestBreakers.guard();
		BeanPostProcessor processor = ExportShed.exportShedProcessor(new ObjectProvider<>() {
			@Override
			public DownstreamGuard getObject() {
				return guard;
			}
		});
		SpanExporter spans = (SpanExporter) processor.postProcessAfterInitialization(successSpans(), "spans");
		LogRecordExporter logs = (LogRecordExporter) processor.postProcessAfterInitialization(successLogs(), "logs");
		assertSame("other", processor.postProcessAfterInitialization("other", "other"));
		assertSame(spans, processor.postProcessAfterInitialization(spans, "spans"));
		assertSame(logs, processor.postProcessAfterInitialization(logs, "logs"));
		spans.export(List.of());
		logs.export(List.of());
	}

	private static SpanExporter successSpans() {
		return new SpanExporter() {
			@Override
			public CompletableResultCode export(Collection<SpanData> data) {
				return CompletableResultCode.ofSuccess();
			}

			@Override
			public CompletableResultCode flush() {
				return CompletableResultCode.ofSuccess();
			}

			@Override
			public CompletableResultCode shutdown() {
				return CompletableResultCode.ofSuccess();
			}
		};
	}

	private static LogRecordExporter successLogs() {
		return new LogRecordExporter() {
			@Override
			public CompletableResultCode export(Collection<LogRecordData> data) {
				return CompletableResultCode.ofSuccess();
			}

			@Override
			public CompletableResultCode flush() {
				return CompletableResultCode.ofSuccess();
			}

			@Override
			public CompletableResultCode shutdown() {
				return CompletableResultCode.ofSuccess();
			}
		};
	}

}
