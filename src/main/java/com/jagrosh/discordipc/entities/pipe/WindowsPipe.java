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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;

public class WindowsPipe extends Pipe {

	private static final Logger LOGGER = LoggerFactory.getLogger(WindowsPipe.class);

	private final RandomAccessFile file;

	WindowsPipe(IPCClient ipcClient, String location) throws IOException {
		super(ipcClient);
		this.file = new RandomAccessFile(location, "rw");
	}

	@Override
	public void write(ByteBuffer bytes) throws IOException {
		file.write(bytes.array());
	}

	@Override
	public Packet read() throws IOException, JsonParseException {
		// Should check if we're connected before reading the file.
		// When we don't do this, it results in an IOException because the
		// read stream had closed for the RandomAccessFile#length() call.
		while (true) {
			if (status == PipeStatus.DISCONNECTED)
				throw new IOException("Disconnected!");

			if (status == PipeStatus.CLOSED)
				return new Packet(Packet.OpCode.CLOSE, null);

			if (file.length() != 0)
				break;

			try {
				Thread.sleep(50);
			} catch (InterruptedException ignored) {
			}
		}

		Packet.OpCode op = Packet.OpCode.values()[Integer.reverseBytes(file.readInt())];

		byte[] d = new byte[Integer.reverseBytes(file.readInt())];
		file.readFully(d);

		Packet p = new Packet(op, new JsonParser().parse(new String(d)).getAsJsonObject());
		LOGGER.debug("Received packet: {}", p.toString());

		if (listener != null)
			listener.onPacketReceived(ipcClient, p);

		return p;
	}

	@Override
	public void close() throws IOException {
		LOGGER.debug("Closing IPC pipe...");
		status = PipeStatus.CLOSING; // start closing pipe
		send(Packet.OpCode.CLOSE, new JsonObject(), null);
		status = PipeStatus.CLOSED; // finish closing pipe

		file.close();
	}

}
