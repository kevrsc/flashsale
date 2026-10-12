package com.flashsale;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;

public class TotalsLogCapture extends AppenderBase<ILoggingEvent> {

	static final List<String> LINES = new CopyOnWriteArrayList<>();

	static final List<ILoggingEvent> EVENTS = new CopyOnWriteArrayList<>();

	@Override
	protected void append(ILoggingEvent event) {
		event.prepareForDeferredProcessing();
		LINES.add(event.getFormattedMessage());
		EVENTS.add(event);
	}

}
