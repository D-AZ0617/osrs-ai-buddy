package com.osrsaibuddy;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.Callable;
import net.runelite.client.util.Filepath;

/** Non-secret registration metadata only. Every filesystem operation uses RuneLite's scoped Filepath API. */
final class ChatGptRegistrationStore implements ChatGptAuth.RegistrationStore
{
	private final Callable<Filepath> directory;
	private final Gson gson;
	private FileChannel channel;
	private FileLock lock;
	private Filepath folder;

	ChatGptRegistrationStore(Callable<Filepath> directory, Gson gson) { this.directory = directory; this.gson = gson; }

	@Override public JsonObject open() throws Exception
	{
		folder = directory.call();
		folder.createDirectories();
		channel = folder.joinSegment("registration.lock").openFileChannel(StandardOpenOption.CREATE, StandardOpenOption.WRITE);
		lock = channel.tryLock();
		if (lock == null) { throw new IOException("Another window owns the registration"); }
		Filepath file = folder.joinSegment("registration.json");
		if (!file.exists()) { return new JsonObject(); }
		if (file.size() > 16384) { throw new IOException("Invalid registration"); }
		try (BufferedReader reader = file.openBufferedReader())
		{
			StringBuilder text = new StringBuilder();
			String line;
			while ((line = reader.readLine()) != null) { text.append(line); }
			JsonObject record = ChatApi.fromJson(gson, text.toString(), JsonObject.class);
			return record == null ? new JsonObject() : record;
		}
	}

	@Override public void save(JsonObject metadata) throws IOException
	{
		if (lock == null || !lock.isValid()) { throw new IOException("Registration storage unavailable"); }
		Filepath temporary = folder.createTempFile("registration", ".tmp");
		try
		{
			temporary.write(gson.toJson(metadata));
			temporary.moveTo(folder.joinSegment("registration.json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		}
		finally { temporary.deleteIfExists(); }
	}

	@Override public void close() throws IOException
	{
		if (lock != null) { lock.release(); lock = null; }
		if (channel != null) { channel.close(); channel = null; }
	}
}
