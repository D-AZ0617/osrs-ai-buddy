package com.osrsaibuddy;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import net.runelite.client.util.Filepath;
import org.junit.Test;
import static org.junit.Assert.*;

/** Reflection is confined to this test fixture, to create a scoped Filepath under a temporary directory. */
public class ChatGptRegistrationStoreTest
{
	private static Filepath scoped(Path directory) throws Exception
	{
		java.lang.reflect.Constructor<Filepath> constructor = Filepath.class.getDeclaredConstructor(Path.class, Path.class);
		constructor.setAccessible(true);
		return constructor.newInstance(directory, directory);
	}

    private static JsonObject session()
    {
        JsonObject value = new JsonObject(); value.addProperty("client_id", "oaiapp_test");
        value.addProperty("access_token", "dummy-access-credential"); value.addProperty("refresh_token", "dummy-refresh-credential");
        value.addProperty("id_token", "dummy-id-credential"); value.addProperty("scope", ChatGptAuth.DIRECT);
        value.addProperty("expires_at", System.currentTimeMillis() / 1000 + 3600); return value;
    }

    @Test public void windowsEncryptedSessionSurvivesStoreRestartWithoutPlaintextTokens() throws Exception
    {
        org.junit.Assume.assumeTrue(SessionProtection.windows().available());
        Path directory = Files.createTempDirectory("buddy-protected-session"); Filepath folder = scoped(directory);
        try (StoreCloser ignored = new StoreCloser(new ChatGptRegistrationStore(() -> folder, new Gson())))
        { ignored.store.open(); ignored.store.save(session()); }
        String disk = Files.readString(directory.resolve("registration.json"));
        assertFalse(disk.contains("dummy-access-credential")); assertFalse(disk.contains("dummy-refresh-credential"));
        assertFalse(disk.contains("dummy-id-credential")); assertTrue(disk.contains("windowsDpapiSession"));
        ChatGptRegistrationStore restored = new ChatGptRegistrationStore(() -> folder, new Gson());
        try { assertEquals("dummy-refresh-credential", ChatGptAuth.text(restored.open(), "refresh_token")); }
        finally { restored.close(); }
    }

    private static final class StoreCloser implements AutoCloseable
    {
        final ChatGptRegistrationStore store;
        StoreCloser(ChatGptRegistrationStore store) { this.store = store; }
        public void close() throws Exception { store.close(); }
    }

    @Test public void optingOutRemovesSavedTokensWhileKeepingRegistration() throws Exception
    {
        org.junit.Assume.assumeTrue(SessionProtection.windows().available());
        Path directory = Files.createTempDirectory("buddy-session-optout"); Filepath folder = scoped(directory);
        java.util.concurrent.atomic.AtomicBoolean remember = new java.util.concurrent.atomic.AtomicBoolean(true);
        ChatGptRegistrationStore store = new ChatGptRegistrationStore(() -> folder, new Gson(), remember::get);
        try
        {
            store.open(); store.save(session()); remember.set(false); store.save(session());
            assertFalse(Files.readString(directory.resolve("registration.json")).contains("windowsDpapiSession"));
        } finally { store.close(); }
        remember.set(true);
        try { JsonObject restored = store.open(); assertFalse(restored.has("access_token")); assertEquals("oaiapp_test", ChatGptAuth.text(restored, "client_id")); }
        finally { store.close(); }
    }

    @Test public void forgedOrDamagedProtectedSessionIsNotRestored() throws Exception
    {
        org.junit.Assume.assumeTrue(SessionProtection.windows().available());
        Path directory = Files.createTempDirectory("buddy-session-damaged"); Filepath folder = scoped(directory);
        JsonObject file = ChatGptAuth.registrationOnly(session()); file.addProperty("windowsDpapiSession", "broken");
        Files.writeString(directory.resolve("registration.json"), file.toString());
        ChatGptRegistrationStore store = new ChatGptRegistrationStore(() -> folder, new Gson());
        try { assertFalse(store.open().has("access_token")); assertNotNull(store.notice()); }
        finally { store.close(); }
    }

    @Test public void legacyPlaintextTokensAreNeverRestored() throws Exception
    {
        Path directory = Files.createTempDirectory("buddy-session-legacy"); Filepath folder = scoped(directory);
        Files.writeString(directory.resolve("registration.json"), session().toString());
        ChatGptRegistrationStore store = new ChatGptRegistrationStore(() -> folder, new Gson());
        try { assertFalse(store.open().has("access_token")); }
        finally { store.close(); }
    }

    @Test public void protectedTokensCannotBeMixedWithAnotherRegistration() throws Exception
    {
        org.junit.Assume.assumeTrue(SessionProtection.windows().available());
        Path directory = Files.createTempDirectory("buddy-session-binding"); Filepath folder = scoped(directory);
        ChatGptRegistrationStore store = new ChatGptRegistrationStore(() -> folder, new Gson());
        try { store.open(); store.save(session()); } finally { store.close(); }
        Path file = directory.resolve("registration.json");
        JsonObject record = new Gson().fromJson(Files.readString(file), JsonObject.class);
        record.addProperty("client_id", "different-registration"); Files.writeString(file, record.toString());
        try { assertFalse(store.open().has("access_token")); assertNotNull(store.notice()); }
        finally { store.close(); }
    }

    @Test public void encryptionFailureDoesNotWritePlaintextOrReplaceExistingSession() throws Exception
    {
        Path directory = Files.createTempDirectory("buddy-session-failure"); Filepath folder = scoped(directory);
        SessionProtection broken = new SessionProtection() {
            public boolean available() { return true; }
            public byte[] protect(byte[] bytes) throws java.io.IOException { throw new java.io.IOException("test"); }
            public byte[] unprotect(byte[] bytes) throws java.io.IOException { throw new java.io.IOException("test"); }
        };
        ChatGptRegistrationStore store = new ChatGptRegistrationStore(() -> folder, new Gson(), () -> true, broken);
        try
        {
            store.open(); store.save(ChatGptAuth.registrationOnly(session()));
            String before = Files.readString(directory.resolve("registration.json"));
            try { store.save(session()); fail(); } catch (java.io.IOException expected) { }
            assertEquals(before, Files.readString(directory.resolve("registration.json")));
        } finally { store.close(); }
    }
	@Test public void scopedRegistrationRoundTripsAndReleasesItsLock() throws Exception
	{
		Path directory = Files.createTempDirectory("buddy-registration-test");
		Filepath folder = scoped(directory);
		ChatGptRegistrationStore first = new ChatGptRegistrationStore(() -> folder, new Gson());
		ChatGptRegistrationStore second = new ChatGptRegistrationStore(() -> folder, new Gson());
		try
		{
			assertTrue(first.open().entrySet().isEmpty());
			JsonObject metadata = new JsonObject();
			metadata.addProperty("client_id", "oaiapp_test");
			first.save(metadata);
			assertTrue(Files.readString(directory.resolve("registration.json")).contains("oaiapp_test"));
			try { second.open(); fail("Concurrent registration writer was allowed"); }
			catch (java.nio.channels.OverlappingFileLockException | java.io.IOException expected) { }
			first.close();
			second.close();
			assertEquals("oaiapp_test", ChatGptAuth.text(second.open(), "client_id"));
		}
		finally { first.close(); second.close(); }
	}
	@Test public void metadataAllowlistNeverRestoresOrSavesTokens()
	{
		JsonObject source = new JsonObject();
		source.addProperty("client_id", "oaiapp_test");
		source.addProperty("access_token", "test-access");
		source.addProperty("refresh_token", "test-refresh");
		source.addProperty("id_token", "test-id");
		source.addProperty("scope", ChatGptAuth.DIRECT);
		JsonObject metadata = ChatGptAuth.registrationOnly(source);
		assertEquals(1, metadata.entrySet().size());
		assertEquals("oaiapp_test", ChatGptAuth.text(metadata, "client_id"));
		assertFalse(ChatGptAuth.hasPermission(metadata));
	}
}
