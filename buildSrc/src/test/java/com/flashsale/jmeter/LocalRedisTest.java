package com.flashsale.jmeter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

class LocalRedisTest {
	@Test
	void encodesACommandAsARespArray() {
		assertEquals("*2\r\n$4\r\nHLEN\r\n$7\r\npending\r\n",
				new String(LocalRedis.encode("HLEN", "pending"), StandardCharsets.UTF_8));
	}

	@Test
	void readsAnIntegerReply() throws Exception {
		assertEquals(3L, reply(":3\r\n", "HLEN", "pending"));
	}

	@Test
	void rejectsAnErrorReply() {
		IOException error = assertThrows(IOException.class, () -> reply("-WRONGTYPE\r\n", "HLEN", "sale"));
		assertEquals("Redis HLEN did not return an integer: -WRONGTYPE", error.getMessage());
	}

	private static long reply(String response, String... args) throws Exception {
		try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			CompletableFuture<String> received = CompletableFuture.supplyAsync(() -> {
				try (Socket client = server.accept()) {
					InputStream in = client.getInputStream();
					byte[] expected = LocalRedis.encode(args);
					byte[] request = in.readNBytes(expected.length);
					OutputStream out = client.getOutputStream();
					out.write(response.getBytes(StandardCharsets.UTF_8));
					out.flush();
					return new String(request, StandardCharsets.UTF_8);
				} catch (IOException ex) {
					throw new IllegalStateException(ex);
				}
			});
			try {
				return LocalRedis.integer("127.0.0.1", server.getLocalPort(), args);
			} finally {
				assertEquals(new String(LocalRedis.encode(args), StandardCharsets.UTF_8), received.get());
			}
		}
	}
}
