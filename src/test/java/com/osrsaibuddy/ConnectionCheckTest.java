package com.osrsaibuddy;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * The provider's model list: which models the picker offers, what Test says about the model that's set, and the
 * picker's tooltip.
 */
public class ConnectionCheckTest
{
	private static final String SETUP = ConnectionCheck.setupKey("profile-a", null, "sk-ant-1");

	@Test
	public void onlyChatModelsAreOffered()
	{
		List<String> ids = Arrays.asList("gpt-6-luna", "text-embedding-3-small", "tts-1-hd", "whisper-1", "dall-e-3",
			"omni-moderation-latest", "gpt-image-1", "gpt-4o-audio-preview", "gpt-4o-transcribe", "gpt-realtime",
			"gpt-4o-search-preview", "nomic-embed-text", "GPT-6.1-sol", "gpt-6-luna", "", null);
		assertEquals(Arrays.asList("gpt-6-luna", "GPT-6.1-sol"), ConnectionCheck.chatModels(ids, true));
		// Anthropic's own order (newest first) is kept.
		assertEquals(Arrays.asList("claude-opus-5-5", "claude-haiku-4-5"),
			ConnectionCheck.chatModels(Arrays.asList("claude-opus-5-5", "claude-haiku-4-5"), false));
	}

	@Test
	public void theResultIsReadAgainstTheModelSetNow()
	{
		ConnectionCheck check = ConnectionCheck.listed(SETUP, Arrays.asList("claude-opus-5-5", "claude-haiku-4-5"), false);

		ConnectionCheck.Note ok = check.note("Anthropic", "claude-opus-5-5", true, false);
		assertEquals(ConnectionCheck.Kind.OK, ok.kind);
		assertEquals("Connected to Anthropic. claude-opus-5-5 is available.", ok.text);

		ConnectionCheck.Note missing = check.note("Anthropic", "claude-opus-9", true, false);
		assertEquals(ConnectionCheck.Kind.WARNING, missing.kind);
		assertEquals("Connected, but claude-opus-9 isn't in the list your account can use. Pick another next to Send.",
			missing.text);
	}

	@Test
	public void compatibleServicesNameTheirModelsTheirOwnWay()
	{
		ConnectionCheck check = ConnectionCheck.listed(SETUP, Arrays.asList("llama3.2:latest", "qwen3:8b"), true);
		assertEquals(ConnectionCheck.Kind.OK, check.note("localhost:11434", "llama3.2", false, false).kind);
		assertEquals("Connected to localhost:11434, but qwen3 isn't one of its models. Pick another next to Send.",
			check.note("localhost:11434", "qwen3", false, false).text);
		assertEquals("Connected to localhost:11434, but it listed no models to chat with.",
			ConnectionCheck.listed(SETUP, Arrays.asList("nomic-embed-text"), true).note("localhost:11434", "x", false, false).text);
	}

	@Test
	public void problemsAndServicesWithoutAList()
	{
		ConnectionCheck.Note testing = ConnectionCheck.testing(SETUP).note("Anthropic", "claude-opus-5-5", true, false);
		assertEquals(ConnectionCheck.Kind.TESTING, testing.kind);
		assertEquals("Testing the connection\u2026", testing.text);

		String bad = "Anthropic didn't accept your API key. Check the API key in the Claude section of the OSRS AI Buddy settings.";
		ConnectionCheck.Note error = ConnectionCheck.failed(SETUP, bad).note("Anthropic", "claude-opus-5-5", true, false);
		assertEquals(ConnectionCheck.Kind.ERROR, error.kind);
		assertEquals(bad, error.text);

		// Any web server can say "not found": that's no sign the URL is right.
		ConnectionCheck.Note noList = ConnectionCheck.failed(SETUP, ConnectionCheck.NO_MODEL_LIST).note("localhost:11434", "m", false, false);
		assertEquals(ConnectionCheck.Kind.WARNING, noList.kind);
		assertEquals("localhost:11434 answered, but not with a list of models, so Test can't check the URL or the model "
			+ "name. Check both on the service's website.", noList.text);
		// Without a model set, it still says where one goes.
		ConnectionCheck.Note noListNoModel = ConnectionCheck.failed(SETUP, ConnectionCheck.NO_MODEL_LIST).note("localhost:11434", " ", false, false);
		assertEquals(ConnectionCheck.Kind.WARNING, noListNoModel.kind);
		assertEquals("localhost:11434 answered, but not with a list of models, so Test can't check the URL or offer a "
			+ "model. Type the model's name next to Send, as the service's website gives it.", noListNoModel.text);
	}

	@Test
	public void aListAnyoneCanReadVouchesForNoKey()
	{
		// OpenRouter lists its models without looking at the key: a green note mustn't suggest the key was checked.
		ConnectionCheck check = ConnectionCheck.listed(SETUP, Arrays.asList("openai/gpt-6-luna", "qwen/qwen3-32b"), true);
		ConnectionCheck.Note listed = check.note("openrouter.ai", "qwen/qwen3-32b", false, true);
		assertEquals(ConnectionCheck.Kind.OK, listed.kind);
		assertEquals("Connected to openrouter.ai. qwen/qwen3-32b is available. Your key is checked when you send: some "
			+ "services list their models for anyone.", listed.text);
		assertEquals("Connected to openrouter.ai. Your key is checked when you send: some services list their models for "
			+ "anyone. Pick a model next to Send.", check.note("openrouter.ai", "", false, true).text);
		// Without a key there's nothing to say about one.
		assertEquals("Connected to openrouter.ai. qwen/qwen3-32b is available.", check.note("openrouter.ai", "qwen/qwen3-32b", false, false).text);
	}

	@Test
	public void withoutAModelTheListIsOffered()
	{
		ConnectionCheck check = ConnectionCheck.listed(SETUP, Arrays.asList("llama3.2:latest", "qwen3:8b"), true);
		ConnectionCheck.Note note = check.note("localhost:11434", "", false, false);
		assertEquals(ConnectionCheck.Kind.OK, note.kind);
		assertEquals("Connected to localhost:11434. Pick a model next to Send.", note.text);
		assertEquals(Arrays.asList("llama3.2:latest", "qwen3:8b"), ConnectionCheck.choices("", check.models));
	}

	@Test
	public void thePickerOffersTheModelThatsSetAndTheListOnce()
	{
		List<String> claude = Arrays.asList("claude-opus-5-5", "claude-haiku-4-5-20251001", "claude-sonnet-5-5");
		// Set and listed: in its place in the list, by the name it's set as, never twice.
		assertEquals(Arrays.asList("claude-opus-5-5", "claude-haiku-4-5", "claude-sonnet-5-5"),
			ConnectionCheck.choices("claude-haiku-4-5", claude));
		assertEquals(claude, ConnectionCheck.choices(" claude-opus-5-5 ", claude));
		assertEquals(Arrays.asList("llama3.2", "qwen3:8b"),
			ConnectionCheck.choices("llama3.2", Arrays.asList("llama3.2:latest", "qwen3:8b")));
		// Set but not listed (or no list at all): still there, first.
		assertEquals(Arrays.asList("my-fine-tune", "claude-opus-5-5", "claude-haiku-4-5-20251001", "claude-sonnet-5-5"),
			ConnectionCheck.choices("my-fine-tune", claude));
		assertEquals(Arrays.asList("claude-opus-5-5"), ConnectionCheck.choices("claude-opus-5-5", null));
		assertTrue(ConnectionCheck.choices(null, null).isEmpty());
	}

	@Test
	public void aModelIsFoundByTheNameTheProviderListsItUnder()
	{
		assertTrue(ConnectionCheck.sameModel("llama3.2", "llama3.2"));
		assertTrue(ConnectionCheck.sameModel("llama3.2:latest", "llama3.2"));
		assertFalse("another tag", ConnectionCheck.sameModel("llama3.2:1b", "llama3.2"));
		assertFalse(ConnectionCheck.sameModel("qwen3:8b:latest", "qwen3:8b"));
		assertTrue(ConnectionCheck.sameModel("claude-haiku-4-5-20251001", "claude-haiku-4-5"));
		assertFalse("not the other way round", ConnectionCheck.sameModel("claude-haiku-4-5", "claude-haiku-4-5-20251001"));
		assertFalse(ConnectionCheck.sameModel("claude-haiku-4-5-2025100", "claude-haiku-4-5"));
	}

	@Test
	public void thePickersTooltipSaysWhyItsListIsShort()
	{
		String how = "The model new messages go to. Click to pick one, or to type its name.";
		String off = "Turn on \"Enable AI requests\" in the OSRS AI Buddy settings, then choose a provider and add your API key.";
		assertEquals(how + " The list fills in once AI requests are on and the provider is set up.",
			ConnectionCheck.pickerTip(null, off, "Anthropic", ""));
		assertEquals(how + " Looking up the models you can use\u2026",
			ConnectionCheck.pickerTip(ConnectionCheck.testing(SETUP), null, "Anthropic", null));
		assertEquals(how, ConnectionCheck.pickerTip(ConnectionCheck.listed(SETUP, Arrays.asList("claude-opus-5-5"), false),
			null, "Anthropic", " "));
		assertEquals(how + " localhost:11434 listed no models to chat with.", ConnectionCheck.pickerTip(
			ConnectionCheck.listed(SETUP, Arrays.asList("nomic-embed-text"), true), null, "localhost:11434", ""));
		assertEquals(how + " Couldn't list the models: Couldn't reach Anthropic.",
			ConnectionCheck.pickerTip(ConnectionCheck.failed(SETUP, "Couldn't reach Anthropic."), null, "Anthropic", ""));
		assertEquals(how + " localhost:11434 doesn't list its models: type the name its website gives.",
			ConnectionCheck.pickerTip(ConnectionCheck.failed(SETUP, ConnectionCheck.NO_MODEL_LIST), null, "localhost:11434", ""));
		// Never read as HTML: it always starts with OSRS AI Buddy's own words, whatever the provider said.
		assertTrue(ConnectionCheck.pickerTip(ConnectionCheck.failed(SETUP, "<html><b>x"), null, "Anthropic", "")
			.startsWith(how));
	}

	@Test
	public void thePickersMenuSaysWhyItsListIsShort()
	{
		String off = "Turn on \"Enable AI requests\" in the OSRS AI Buddy settings, then choose a provider and add your API key.";
		assertEquals("The list fills in once AI requests are on and the provider is set up.",
			ConnectionCheck.listNote(null, off, "Anthropic"));
		assertEquals("Looking up the models you can use\u2026", ConnectionCheck.listNote(ConnectionCheck.testing(SETUP),
			null, "Anthropic"));
		assertEquals("Couldn't list the models: Couldn't reach Anthropic.",
			ConnectionCheck.listNote(ConnectionCheck.failed(SETUP, "Couldn't reach Anthropic."), null, "Anthropic"));
		assertEquals("localhost:11434 listed no models to chat with.", ConnectionCheck.listNote(
			ConnectionCheck.listed(SETUP, Arrays.asList("nomic-embed-text"), true), null, "localhost:11434"));
		// A list that came in: nothing to say.
		assertNull(ConnectionCheck.listNote(ConnectionCheck.listed(SETUP, Arrays.asList("claude-opus-5-5"), false), null,
			"Anthropic"));
	}

	@Test
	public void thePickersTooltipNamesTheModelInFull()
	{
		// The box is narrow: a long name is cut there, and whole here.
		String model = "anthropic/claude-sonnet-5-5:thinking-extended-preview";
		assertEquals("New messages go to " + model + ". Click to pick another, or to type its name.",
			ConnectionCheck.pickerTip(ConnectionCheck.listed(SETUP, Arrays.asList(model), false), null, "OpenRouter",
				" " + model + " "));
		assertEquals("New messages go to llama3.2. Click to pick another, or to type its name. localhost:11434 "
			+ "doesn't list its models: type the name its website gives.", ConnectionCheck.pickerTip(
			ConnectionCheck.failed(SETUP, ConnectionCheck.NO_MODEL_LIST), null, "localhost:11434", "llama3.2"));
		// A name of the player's own, too, comes after OSRS AI Buddy's words: never read as HTML.
		assertTrue(ConnectionCheck.pickerTip(null, null, "Anthropic", "<html><b>x").startsWith("New messages go to "));
	}

	@Test
	public void anAliasIsFoundAsTheDatedModelItPointsTo()
	{
		// Anthropic lists claude-haiku-4-5 only by its full name.
		ConnectionCheck check = ConnectionCheck.listed(SETUP, Arrays.asList("claude-opus-5-5", "claude-haiku-4-5-20251001"), false);
		ConnectionCheck.Note note = check.note("Anthropic", "claude-haiku-4-5", true, false);
		assertEquals(ConnectionCheck.Kind.OK, note.kind);
		assertEquals("Connected to Anthropic. claude-haiku-4-5 is available.", note.text);
		// Only a date: another model that starts the same way isn't it.
		assertEquals(ConnectionCheck.Kind.WARNING, ConnectionCheck.listed(SETUP, Arrays.asList("claude-haiku-4-5-latest-x",
			"claude-haiku-4-5-2025100"), false).note("Anthropic", "claude-haiku-4-5", true, false).kind);
	}

	@Test
	public void aResultBelongsToItsProviderAddressAndKey()
	{
		String claude = ConnectionCheck.setupKey("profile-a", null, "sk-ant-1");
		assertEquals(SETUP, claude);
		assertNotEquals(claude, ConnectionCheck.setupKey("profile-a", null, "sk-ant-2"));
		assertNotEquals(claude, ConnectionCheck.setupKey("profile-b", null, "sk-ant-1"));
		assertNotEquals(ConnectionCheck.setupKey("profile-c", "http://localhost:11434/v1", ""),
			ConnectionCheck.setupKey("profile-c", "http://localhost:1234/v1", ""));
		// The key itself isn't kept.
		assertTrue(!claude.contains("sk-ant-1"));
	}
}

