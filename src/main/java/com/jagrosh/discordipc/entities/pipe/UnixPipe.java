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
import com.jagrosh.discordipc.entities.Packet;

import org.newsclub.net.unix.AFUNIXSocket;
import org.newsclub.net.unix.AFUNIXSocketAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class UnixPipe extends Pipe {

	private static final Logger LOGGER = LoggerFactory.getLogger(UnixPipe.class);

	private final AFUNIXSocket socket;

	UnixPipe(IPCClient ipcClient, String location) throws IOException {
		super(ipcClient);

		socket = AFUNIXSocket.connectTo(new AFUNIXSocketAddress(new File(location)));
	}

	@Override
	public Packet read() throws IOException, JsonParseException {
		InputStream is = socket.getInputStream();

		// Search
		while (true) {
			if (status == PipeStatus.DISCONNECTED)
				throw new IOException("Disconnected!");

			if (status == PipeStatus.CLOSED)
				return new Packet(Packet.OpCode.CLOSE, null);

			if (is.available() != 0)
				break;

			try {
				Thread.sleep(50);
			} catch (InterruptedException ignored) {
			}
		}
		
		byte[] metadata = new byte[8];
		is.read(metadata);
		ByteBuffer metadatab = ByteBuffer.wrap(metadata).order(ByteOrder.LITTLE_ENDIAN);

		Packet.OpCode op = Packet.OpCode.values()[metadatab.getInt()];

		byte[] data = new byte[metadatab.getInt()];
		is.read(data);

		Packet p = new Packet(op, new JsonParser().parse(new String(data)).getAsJsonObject());
		LOGGER.debug("Received packet: {}", p.toString());

		if (listener != null)
			listener.onPacketReceived(ipcClient, p);

		return p;
	}

	@Override
	public void write(ByteBuffer bytes) throws IOException {
		socket.getOutputStream().write(bytes.array());
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
