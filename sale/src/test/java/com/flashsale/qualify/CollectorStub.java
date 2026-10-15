package com.flashsale.qualify;

import java.io.IOException;
import java.net.InetSocketAddress;

import com.sun.net.httpserver.HttpServer;

final class CollectorStub {

	final HttpServer server;

	private CollectorStub(int status) throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			exchange.sendResponseHeaders(status, -1);
			exchange.close();
		});
		server.start();
	}

	static CollectorStub accepting() throws IOException {
		return new CollectorStub(200);
	}

	static CollectorStub refusing() throws IOException {
		return new CollectorStub(500);
	}

	int port() {
		return server.getAddress().getPort();
	}

	void stop() {
		server.stop(0);
	}

}
