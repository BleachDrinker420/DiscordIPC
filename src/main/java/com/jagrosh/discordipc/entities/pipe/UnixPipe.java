/*
 * Copyright 2017 John Grosh (john.a.grosh@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.jagrosh.discordipc.entities.pipe;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.jagrosh.discordipc.IPCClient;
import com.jagrosh.discordipc.entities.Callback;
import com.jagrosh.discordipc.entities.Packet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Map;

public class UnixPipe extends Pipe {

	private static final Logger LOGGER = LoggerFactory.getLogger(UnixPipe.class);
	private final Socket socket;

	UnixPipe(IPCClient ipcClient, Map<String, Callback> callbacks, String location) throws IOException {
		super(ipcClient, callbacks);

		socket = new Socket();
		socket.connect(UnixDomainSocketAddress.of(location));
	}

	@Override
	public Packet read() throws IOException, JsonParseException {
		InputStream is = socket.getInputStream();

		while ((status == PipeStatus.CONNECTED || status == PipeStatus.CLOSING) && is.available() == 0) {
			try {
				Thread.sleep(50);
			} catch (InterruptedException ignored) {
			}
		}

		/*
		 * byte[] buf = new byte[is.available()]; is.read(buf, 0, buf.length);
		 * LOGGER.info(new String(buf));
		 * 
		 * if (true) return null;
		 */

		if (status == PipeStatus.DISCONNECTED)
			throw new IOException("Disconnected!");

		if (status == PipeStatus.CLOSED)
			return new Packet(Packet.OpCode.CLOSE, null);

		// Read the op and length. Both are signed ints
		byte[] d = new byte[8];
		is.read(d);
		ByteBuffer bb = ByteBuffer.wrap(d);

		Packet.OpCode op = Packet.OpCode.values()[Integer.reverseBytes(bb.getInt())];
		d = new byte[Integer.reverseBytes(bb.getInt())];

		is.read(d);

		// @SuppressWarnings("deprecation")
		Packet p = new Packet(op, new JsonParser().parse(new String(d)).getAsJsonObject());
		LOGGER.debug("Received packet: {}", p.toString());
		if (listener != null)
			listener.onPacketReceived(ipcClient, p);
		return p;
	}

	@Override
	public void write(byte[] b) throws IOException {
		socket.getOutputStream().write(b);
	}

	@Override
	public void close() throws IOException {
		LOGGER.debug("Closing IPC pipe...");
		status = PipeStatus.CLOSING;
		send(Packet.OpCode.CLOSE, new JsonObject(), null);
		status = PipeStatus.CLOSED;
		socket.close();
	}
}
