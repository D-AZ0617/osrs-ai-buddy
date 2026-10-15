package com.osrsaibuddy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Asking for the model list, by "Test connection" or quietly for the model picker: only the answer to the latest request
 * counts, a stopped one says nothing, a result belongs to the setup it was made with, and the picker asks once per
 * setup and never while the provider can't be reached. The provider is a stand-in that answers when the test says so.
 */
public class ConnectionTesterTest
{
	private static final String SETUP = ConnectionCheck.setupKey("profile-a", null, "sk-ant-1");
	/** Stands in for the EDT: tasks wait here until the test runs them, as invokeLater's do. */
	private final BlockingQueue<Runnable> edt = new LinkedBlockingQueue<>();
	private int changes;
	private final ConnectionTester tester = new ConnectionTester(edt::add, () -> changes++);
	private final FakeApi api = new FakeApi();

	/** A provider that keeps every model list request, and answers only when the test calls its listener. */
	private static final class FakeApi implements ChatApi
	{
		final List<ModelsListener> listeners = new ArrayList<>();
		final List<Pending> requests = new ArrayList<>();
		RuntimeException failure;

		@Override
		public Pending send(Conversation conversation, Listener listener)
		{
			throw new AssertionError("Test sends no messages");
		}

		@Override
		public String displayName()
		{
			return "Claude";
		}

		@Override
		public Pending listModels(ModelsListener listener)
		{
			if (failure != null)
			{
				throw failure;
			}
			listeners.add(listener);
			Pending p = new Pending();
			requests.add(p);
			return p;
		}
	}

	private void runEdt()
	{
		Runnable r;
		while ((r = edt.poll()) != null)
		{
			r.run();
		}
	}

	private ConnectionCheck.Note note()
	{
		return tester.note(SETUP, "Anthropic", "claude-opus-5-5", true, false);
	}

	@Test
	public void aTestShowsWhatTheProviderListed()
	{
		assertNull("nothing yet", note());
		tester.start(api, SETUP, false);
		assertEquals(ConnectionCheck.Kind.TESTING, note().kind);

		api.listeners.get(0).onModels(List.of("claude-opus-5-5", "claude-haiku-4-5"));
		assertEquals("not until the EDT runs it", ConnectionCheck.Kind.TESTING, note().kind);
		runEdt();
		assertEquals(ConnectionCheck.Kind.OK, note().kind);
		assertEquals(List.of("claude-opus-5-5", "claude-haiku-4-5"), tester.check(SETUP).models);
		assertEquals(1, changes);
	}

	@Test
	public void onlyTheLatestTestCounts()
	{
		tester.start(api, SETUP, false);
		ChatApi.ModelsListener first = api.listeners.get(0);
		tester.start(api, SETUP, false);
		assertTrue("the first one is stopped", api.requests.get(0).isCancelled());

		first.onModels(List.of("claude-opus-5-5"));
		runEdt();
		assertEquals(ConnectionCheck.Kind.TESTING, note().kind);
		assertEquals(0, changes);

		api.listeners.get(1).onError("Anthropic didn't accept your API key. Check the API key in the Claude section of the OSRS AI Buddy "
			+ "settings.");
		runEdt();
		assertEquals(ConnectionCheck.Kind.ERROR, note().kind);
		assertEquals(1, changes);
	}

	@Test
	public void aStoppedTestSaysNothing()
	{
		tester.start(api, SETUP, false);
		tester.stop();
		assertTrue(api.requests.get(0).isCancelled());
		assertNull(note());
		api.listeners.get(0).onModels(List.of("claude-opus-5-5"));
		runEdt();
		assertNull(note());
		assertEquals(0, changes);
	}

	@Test
	public void aResultBelongsToTheSetupItWasMadeWith()
	{
		tester.start(api, SETUP, false);
		api.listeners.get(0).onModels(List.of("claude-opus-5-5"));
		runEdt();
		String otherKey = ConnectionCheck.setupKey("profile-a", null, "sk-ant-2");
		assertNull(tester.note(otherKey, "Anthropic", "claude-opus-5-5", true, false));
		// Another model is fine: the list is read against it.
		assertEquals(ConnectionCheck.Kind.WARNING, tester.note(SETUP, "Anthropic", "claude-opus-9", true, false).kind);
	}

	@Test
	public void aTestThatCantBeSentSaysSoWithoutTheKey()
	{
		api.failure = new IllegalArgumentException("Unexpected char in header value: sk-ant-secret");
		tester.start(api, SETUP, false);
		ConnectionCheck.Note note = note();
		assertEquals(ConnectionCheck.Kind.ERROR, note.kind);
		assertEquals(RequestRunner.COULDNT_SEND, note.text);
		assertFalse(note.text.contains("sk-ant"));
	}

	@Test
	public void thePickersListIsAskedForQuietlyOncePerSetup()
	{
		assertTrue(tester.list(null, SETUP, () -> api, false));
		assertEquals(1, api.listeners.size());
		assertNull("the banner stays quiet", note());
		assertEquals(ConnectionCheck.Kind.TESTING, tester.check(SETUP).note("Anthropic", "m", true, false).kind);
		assertFalse("not again while it's asking", tester.list(null, SETUP, () -> api, false));

		api.listeners.get(0).onModels(List.of("claude-opus-5-5"));
		runEdt();
		assertEquals(1, changes);
		assertEquals(List.of("claude-opus-5-5"), tester.check(SETUP).models);
		assertNull(note());
		assertFalse("nor once it has the list", tester.list(null, SETUP, () -> api, false));
		assertEquals(1, api.listeners.size());

		// Another key: its own list. And back: asked again, since that's a change too.
		String other = ConnectionCheck.setupKey("profile-a", null, "sk-ant-2");
		assertNull(tester.check(other));
		assertTrue(tester.list(null, other, () -> api, false));
		assertTrue(tester.list(null, SETUP, () -> api, false));
		assertEquals(3, api.listeners.size());
		assertTrue("the one before is stopped", api.requests.get(1).isCancelled());
	}

	@Test
	public void refreshListAsksAgainButNeverWhileTheProviderCantBeReached()
	{
		tester.list(null, SETUP, () -> api, false);
		api.listeners.get(0).onModels(List.of("claude-opus-5-5"));
		runEdt();
		assertFalse("the picker has its list", tester.list(null, SETUP, () -> api, false));

		// The player asks for it again: asked, quietly.
		assertTrue(tester.refresh(null, SETUP, () -> api, false));
		assertEquals(2, api.listeners.size());
		assertNull("the banner stays quiet", note());
		// Asked again before that came back: the first answer is stale, and doesn't count.
		assertTrue(tester.refresh(null, SETUP, () -> api, false));
		assertTrue(api.requests.get(1).isCancelled());
		api.listeners.get(1).onModels(List.of("old-model"));
		api.listeners.get(2).onModels(List.of("claude-opus-5-5", "claude-sonnet-5-5"));
		runEdt();
		assertEquals(List.of("claude-opus-5-5", "claude-sonnet-5-5"), tester.check(SETUP).models);

		// AI requests off: nothing is asked, and no API is even made.
		String off = "Turn on \"Enable AI requests\" in the OSRS AI Buddy settings, then choose a provider and add your API key.";
		assertFalse(tester.refresh(off, SETUP, () ->
		{
			throw new AssertionError("no API is even made");
		}, false));
		assertEquals(3, api.listeners.size());
	}

	@Test
	public void refreshListLeavesATestThatsStillAskingAlone()
	{
		tester.start(api, SETUP, false);
		// Refresh list before the Test's answer: the Test isn't stopped, and its answer is the list.
		assertFalse(tester.refresh(null, SETUP, () -> api, false));
		assertEquals(1, api.listeners.size());
		assertFalse(api.requests.get(0).isCancelled());
		assertEquals(ConnectionCheck.Kind.TESTING, note().kind);
		api.listeners.get(0).onModels(List.of("claude-opus-5-5", "claude-haiku-4-5"));
		runEdt();
		assertEquals("the banner says what the Test found", ConnectionCheck.Kind.OK, note().kind);
		assertEquals(List.of("claude-opus-5-5", "claude-haiku-4-5"), tester.check(SETUP).models);

		// Once it's answered, Refresh list asks again, quietly, as ever.
		assertTrue(tester.refresh(null, SETUP, () -> api, false));
		assertEquals(2, api.listeners.size());
		// A Test whose banner was closed while it was asking is asked again too.
		tester.start(api, SETUP, false);
		tester.dismiss();
		assertTrue(tester.refresh(null, SETUP, () -> api, false));
		assertTrue(api.requests.get(2).isCancelled());
	}

	@Test
	public void testAsksAgainAndTheBannerCanBeClosed()
	{
		tester.list(null, SETUP, () -> api, false);
		api.listeners.get(0).onModels(List.of("claude-opus-5-5"));
		runEdt();

		tester.start(api, SETUP, false);
		assertEquals(2, api.listeners.size());
		api.listeners.get(1).onModels(List.of("claude-opus-5-5", "claude-haiku-4-5"));
		runEdt();
		assertEquals(ConnectionCheck.Kind.OK, note().kind);

		// Closed: the banner has nothing to say, but the picker keeps the list.
		tester.dismiss();
		assertNull(note());
		assertEquals(List.of("claude-opus-5-5", "claude-haiku-4-5"), tester.check(SETUP).models);
	}

	@Test
	public void nothingIsAskedWhileTheProviderCantBeReached()
	{
		String off = "Turn on \"Enable AI requests\" in the OSRS AI Buddy settings, then choose a provider and add your API key.";
		assertFalse(tester.list(off, SETUP, () ->
		{
			throw new AssertionError("no API is even made");
		}, false));
		assertTrue(api.listeners.isEmpty());
		assertNull(tester.check(SETUP));

		// Turned off after a list came in: forgotten, and asked for again once it's back on.
		tester.list(null, SETUP, () -> api, false);
		api.listeners.get(0).onModels(List.of("claude-opus-5-5"));
		runEdt();
		tester.stop();
		assertNull(tester.check(SETUP));
		assertTrue(tester.list(null, SETUP, () -> api, false));
		assertEquals(2, api.listeners.size());
	}

	@Test
	public void goodNewsGoesWithTheNextMessageButProblemsStay()
	{
		tester.start(api, SETUP, false);
		assertFalse("still testing", tester.sent(SETUP, "Anthropic", "claude-opus-5-5", true, false));
		assertEquals(ConnectionCheck.Kind.TESTING, note().kind);
		api.listeners.get(0).onModels(List.of("claude-haiku-4-5"));
		runEdt();
		// The model that's set isn't listed: a warning, which stays.
		assertFalse(tester.sent(SETUP, "Anthropic", "claude-opus-5-5", true, false));
		assertEquals(ConnectionCheck.Kind.WARNING, note().kind);
		// Read against the model now set, it's good news: gone once a message goes.
		assertTrue(tester.sent(SETUP, "Anthropic", "claude-haiku-4-5", true, false));
		assertNull(tester.note(SETUP, "Anthropic", "claude-haiku-4-5", true, false));
		assertEquals("the picker keeps the list", List.of("claude-haiku-4-5"), tester.check(SETUP).models);
	}
}

