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
import java.util.function.BooleanSupplier;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import net.runelite.client.util.Filepath;

/** Scoped registration and optional Windows-user-protected session, atomically saved under one process lock. */
final class ChatGptRegistrationStore implements ChatGptAuth.RegistrationStore
{
	private final Callable<Filepath> directory;
	private final Gson gson;
	private FileChannel channel;
	private FileLock lock;
	private Filepath folder;
    private final BooleanSupplier remember;
    private final SessionProtection protection;
    private String notice;

    ChatGptRegistrationStore(Callable<Filepath> directory, Gson gson)
    { this(directory, gson, () -> true, SessionProtection.windows()); }

    ChatGptRegistrationStore(Callable<Filepath> directory, Gson gson, BooleanSupplier remember)
    { this(directory, gson, remember, SessionProtection.windows()); }

    ChatGptRegistrationStore(Callable<Filepath> directory, Gson gson, BooleanSupplier remember, SessionProtection protection)
    { this.directory = directory; this.gson = gson; this.remember = remember; this.protection = protection; }

    @Override public String notice()
    {
        return notice != null ? notice : remember.getAsBoolean() && !protection.available()
            ? "Persistent ChatGPT sign-in currently requires Windows. Your session will last only while Buddy stays enabled." : null;
    }

	@Override public JsonObject open() throws Exception
	{
		folder = directory.call();
		folder.createDirectories();
		channel = folder.joinSegment("registration.lock").openFileChannel(StandardOpenOption.CREATE, StandardOpenOption.WRITE);
		lock = channel.tryLock();
		if (lock == null) { throw new IOException("Another window owns the registration"); }
		Filepath file = folder.joinSegment("registration.json");
		if (!file.exists()) { return new JsonObject(); }
		if (file.size() > 131072) { throw new IOException("Invalid registration"); }
		try (BufferedReader reader = file.openBufferedReader())
		{
			StringBuilder text = new StringBuilder();
			String line;
			while ((line = reader.readLine()) != null) { text.append(line); }
			JsonObject record = ChatApi.fromJson(gson, text.toString(), JsonObject.class);
            if (record == null) { return new JsonObject(); }
            // Legacy plaintext token fields are deliberately ignored.
            JsonObject metadata = ChatGptAuth.registrationOnly(record);
            if (remember.getAsBoolean() && protection.available() && record.has("windowsDpapiSession"))
            {
                byte[] plain = null;
                try
                {
                    plain = protection.unprotect(Base64.getDecoder().decode(record.get("windowsDpapiSession").getAsString()));
                    JsonObject session = ChatApi.fromJson(gson, new String(plain, StandardCharsets.UTF_8), JsonObject.class);
                    if (session == null || !ChatGptAuth.registrationOnly(session).equals(metadata)) { throw new IOException("Session identity mismatch"); }
                    return ChatGptAuth.sessionRecord(session);
                }
                catch (IOException | RuntimeException e) { notice = "The saved ChatGPT session couldn't be unlocked. Continue with ChatGPT to reconnect."; }
                finally { if (plain != null) { Arrays.fill(plain, (byte) 0); } }
            }
            return metadata;
		}
	}

	@Override public void save(JsonObject value) throws IOException
	{
		if (lock == null || !lock.isValid()) { throw new IOException("Registration storage unavailable"); }
        JsonObject metadata = ChatGptAuth.registrationOnly(value);
        if (remember.getAsBoolean() && protection.available() && !ChatGptAuth.text(value, "access_token").isEmpty())
        {
            byte[] plain = gson.toJson(ChatGptAuth.sessionRecord(value)).getBytes(StandardCharsets.UTF_8);
            try { metadata.addProperty("windowsDpapiSession", Base64.getEncoder().encodeToString(protection.protect(plain))); }
            finally { Arrays.fill(plain, (byte) 0); }
        }
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
