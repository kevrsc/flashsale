package com.flashsale;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;

public class TotalsLogCapture extends AppenderBase<ILoggingEvent> {

	static final List<String> LINES = new CopyOnWriteArrayList<>();

	static final List<String> TRACE_IDS = new CopyOnWriteArrayList<>();

	static final List<String> SPAN_IDS = new CopyOnWriteArrayList<>();

	@Override
	protected void append(ILoggingEvent event) {
		LINES.add(event.getFormattedMessage());
		TRACE_IDS.add(event.getMDCPropertyMap().get("traceId"));
		SPAN_IDS.add(event.getMDCPropertyMap().get("spanId"));
	}

}
