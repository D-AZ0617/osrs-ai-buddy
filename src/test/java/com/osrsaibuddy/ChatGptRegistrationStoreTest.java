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
