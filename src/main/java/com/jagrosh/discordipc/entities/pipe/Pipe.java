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
import com.jagrosh.discordipc.IPCClient;
import com.jagrosh.discordipc.IPCListener;
import com.jagrosh.discordipc.entities.Callback;
import com.jagrosh.discordipc.entities.DiscordBuild;
import com.jagrosh.discordipc.entities.Packet;
import com.jagrosh.discordipc.exceptions.NoDiscordClientException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.UUID;

public abstract class Pipe implements Closeable {

	private static final Logger LOGGER = LoggerFactory.getLogger(Pipe.class);
	private static final String UNIX_TEMP_PATH = getUnixTempPath();
	private static final int VERSION = 1;

	protected final IPCClient ipcClient;
	protected IPCListener listener;
	protected PipeStatus status = PipeStatus.CONNECTING;
	private DiscordBuild build;

	Pipe(IPCClient ipcClient) {
		this.ipcClient = ipcClient;
	}

	public static Pipe openPipe(IPCClient ipcClient, long clientId, DiscordBuild... preferredOrder) throws NoDiscordClientException {

		// Store the most preferred pipe
		int preference = Integer.MAX_VALUE;
		Pipe bestPipe = null;
		for (int i = 0; i < 10; i++) {
			try {
				String location = getPipeLocation(i);
				LOGGER.debug("Searching for IPC: {}", location);
				Pipe pipe = createPipe(ipcClient, location);

				JsonObject dataJson = new JsonObject();
				dataJson.addProperty("v", VERSION);
				dataJson.addProperty("client_id", Long.toString(clientId));

				pipe.send(Packet.OpCode.HANDSHAKE, dataJson, null);

				Packet p = pipe.read(); // this is a valid client at this point
				pipe.build = DiscordBuild.from(p.getJson().getAsJsonObject("data").getAsJsonObject("config").get("api_endpoint").getAsString());
				pipe.status = PipeStatus.CONNECTED;

				LOGGER.debug("Found a valid client ({}) with packet: {}", pipe.build.name(), p.toString());

				// Find out which preference index this client is at
				int newPref = preferredOrder.length;
				for (int j = 0; j < preferredOrder.length; j++) {
					if (pipe.build == preferredOrder[j]) {
						newPref = j;
						break;
					}
				}

				if (newPref < preference) {
					if (bestPipe != null)
						bestPipe.close();

					// We found a optimal client
					if (newPref == 0) {
						LOGGER.info("Found preferred client: {}", pipe.build.name());
						return pipe;
					}

					preference = newPref;
					bestPipe = pipe;
				}
			} catch (IOException | JsonParseException e) {
				e.printStackTrace();
			}
		}

		if (bestPipe != null)
			return bestPipe;

		throw new NoDiscordClientException();
	}

	private static Pipe createPipe(IPCClient ipcClient, String location) throws IOException {
		String osName = System.getProperty("os.name").toLowerCase();

		if (osName.contains("win")) {
			return new WindowsPipe(ipcClient, location);
		} else if (osName.contains("linux") || osName.contains("mac")) {
			return new UnixPipe(ipcClient, location);
		}

		throw new RuntimeException("Unsupported OS: " + osName);
	}

	/**
	 * Sends json with the given {@link Packet.OpCode}.
	 *
	 * @param op       The {@link Packet.OpCode} to send data with.
	 * @param data     The data to send.
	 * @param callback callback for the response.
	 */
	public void send(Packet.OpCode op, JsonObject data, Callback callback) {
		try {
			String nonce = generateNonce();
			data.addProperty("nonce", nonce);

			Packet p = new Packet(op, data);

			write(p.toBytes());
			LOGGER.debug("Sent packet: {}", p.toString());
			
			if (callback != null)
				callback.succeed(p);
			if (listener != null)
				listener.onPacketSent(ipcClient, p);
		} catch (IOException ex) {
			LOGGER.error("Encountered an IOException while sending a packet and disconnected!", ex);
			status = PipeStatus.DISCONNECTED;
			
			if (callback != null)
				callback.fail(ex.getMessage());
		}
	}

	/**
	 * Blocks until reading a {@link Packet} or until the read thread encounters bad
	 * data.
	 *
	 * @return A valid {@link Packet}.
	 *
	 * @throws IOException        If the pipe breaks.
	 * @throws JsonParseException If the read thread receives bad data.
	 */
	public abstract Packet read() throws IOException, JsonParseException;

	public abstract void write(ByteBuffer bytes) throws IOException;

	/**
	 * Generates a nonce.
	 *
	 * @return A random {@link UUID}.
	 */
	private static String generateNonce() {
		return UUID.randomUUID().toString();
	}

	public PipeStatus getStatus() {
		return status;
	}

	public void setStatus(PipeStatus status) {
		this.status = status;
	}

	public void setListener(IPCListener listener) {
		this.listener = listener;
	}

	public DiscordBuild getDiscordBuild() {
		return build;
	}

	/**
	 * Finds the IPC location in the current system.
	 *
	 * @param i Index to try getting the IPC at.
	 *
	 * @return The IPC location.
	 */
	private static String getPipeLocation(int i) {
		if (System.getProperty("os.name").contains("Win"))
			return "\\\\?\\pipe\\discord-ipc-" + i;

		return UNIX_TEMP_PATH + "/discord-ipc-" + i;
	}

	private static String getUnixTempPath() {
		for (String str : new String[] { "XDG_RUNTIME_DIR", "TMPDIR", "TMP", "TEMP" }) {
			String path = System.getenv(str);
			if (path != null)
				return path;
		}

		return "/tmp";
	}
}
