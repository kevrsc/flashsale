package com.flashsale.jmeter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public final class LocalRedis {
	private static final int TIMEOUT_MS = 2000;

	private LocalRedis() {
	}

	public static long integer(String host, int port, String... args) throws IOException {
		try (Socket socket = new Socket()) {
			socket.connect(new InetSocketAddress(host, port), TIMEOUT_MS);
			socket.setSoTimeout(TIMEOUT_MS);
			OutputStream out = socket.getOutputStream();
			out.write(encode(args));
			out.flush();
			BufferedReader in = new BufferedReader(
					new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
			String reply = in.readLine();
			if (reply == null || !reply.startsWith(":")) {
				throw new IOException("Redis " + args[0] + " did not return an integer: " + reply);
			}
			return Long.parseLong(reply.substring(1));
		}
	}

	static byte[] encode(String... args) {
		StringBuilder resp = new StringBuilder("*").append(args.length).append("\r\n");
		for (String arg : args) {
			int length = arg.getBytes(StandardCharsets.UTF_8).length;
			resp.append('$').append(length).append("\r\n").append(arg).append("\r\n");
		}
		return resp.toString().getBytes(StandardCharsets.UTF_8);
	}
}
