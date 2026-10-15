package com.osrsaibuddy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.runelite.http.api.item.ItemPrice;
import okhttp3.OkHttpClient;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.LoggerFactory;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * A message's way out and its answer's way back: summary first when a chat is long, the reply as it streams in, what
 * was looked up, Stop, Retry, and answers that come too late to count. The provider is a stand-in that answers when the
 * test says so; the EDT is a queue the test runs.
 */
public class RequestRunnerTest
{
	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
	/** Stands in for the EDT: tasks wait here until the test runs them, as invokeLater's do. */
	private final BlockingQueue<Runnable> edt = new LinkedBlockingQueue<>();
	private final FakeApi api = new FakeApi();
	private final FakeHost host = new FakeHost();
	private final RequestRunner runner = new RequestRunner(host, edt::add, scheduler);
	private final Chat chat = new Chat("Chat 1");

	@Before
	public void open()
	{
		host.chats.add(chat);
	}

	@After
	public void stop()
	{
		scheduler.shutdownNow();
	}

	/** A provider that keeps every request, and answers only when the test calls its listener. */
	private static final class FakeApi implements ChatApi
	{
		final List<Conversation> sent = new ArrayList<>();
		final List<Listener> listeners = new ArrayList<>();
		final List<Pending> requests = new ArrayList<>();
		RuntimeException failure;

		@Override
		public Pending send(Conversation conversation, Listener listener)
		{
			if (failure != null)
			{
				throw failure;
			}
			sent.add(conversation);
			listeners.add(listener);
			Pending p = new Pending();
			requests.add(p);
			return p;
		}

		@Override
		public String displayName()
		{
			return "Claude";
		}

		Listener listener()
		{
			return listeners.get(listeners.size() - 1);
		}

		Conversation last()
		{
			return sent.get(sent.size() - 1);
		}
	}

	private final class FakeHost implements RequestRunner.Host
	{
		final List<Chat> chats = new ArrayList<>();
		final List<Chat.Message> ended = new ArrayList<>();
		boolean ai = true;
		boolean character = true;
		int changed;
		int live;
		Consumer<String> characterRead;
		BooleanSupplier wanted;
		Consumer<String> activity;
		Runnable started;
		/** The plugin's tools, made from stand-ins: a Wiki that never sends anything, no prices, no game. */
		final ToolBox.Parts parts = new ToolBox.Parts(new WikiClient(new OkHttpClient(), new Gson(), () -> false),
			new NoPrices(), new NoGame(), Runnable::run, scheduler, () -> true, () -> character);

		@Override
		public boolean aiRequests()
		{
			return ai;
		}

		@Override
		public boolean shareCharacter()
		{
			return character;
		}

		@Override
		public void readCharacter(Consumer<String> done)
		{
			characterRead = done;
		}

		@Override
		public ToolBox tools(RequestRunner.Setup setup, BooleanSupplier wanted, Consumer<String> activity, Runnable started)
		{
			this.wanted = wanted;
			this.activity = activity;
			this.started = started;
			return parts.forRequest(setup, wanted, activity, started);
		}

		@Override
		public boolean has(Chat c)
		{
			return chats.contains(c);
		}

		@Override
		public void changed(Chat c)
		{
			changed++;
		}

		@Override
		public void live(Chat c)
		{
			live++;
		}

		@Override
		public void ended(Chat c, Chat.Message m)
		{
			ended.add(m);
		}
	}

	private static final class NoPrices implements LookupTools.Prices
	{
		@Override
		public List<ItemPrice> search(String text)
		{
			return Collections.emptyList();
		}

		@Override
		public long price(ItemPrice item)
		{
			return 0;
		}

		@Override
		public void highAlchemy(List<Integer> ids, Consumer<Map<Integer, Integer>> done)
		{
			done.accept(Collections.emptyMap());
		}
	}

	private static final class NoGame implements GameDataTools.Game
	{
		@Override
		public boolean loggedIn()
		{
			return false;
		}

		@Override
		public List<GameData.ItemLine> equipment()
		{
			return Collections.emptyList();
		}

		@Override
		public GameData.Items inventory()
		{
			return null;
		}

		@Override
		public GameData.Bank bank()
		{
			return null;
		}

		@Override
		public GameData.SlayerTask slayerTask()
		{
			return null;
		}

		@Override
		public List<GameData.Diary> diaries()
		{
			return Collections.emptyList();
		}
	}

	/** Runs what's waiting for the EDT, and whatever that queues in turn. */
	private void runEdt()
	{
		Runnable r;
		while ((r = edt.poll()) != null)
		{
			r.run();
		}
	}

	/** Waits for something to reach the EDT (a redraw held back by the throttle), then runs everything waiting. */
	private void awaitEdt() throws InterruptedException
	{
		Runnable r = edt.poll(5, TimeUnit.SECONDS);
		assertNotNull("nothing reached the EDT", r);
		r.run();
		runEdt();
	}

	private RequestRunner.Setup setup(boolean shareCharacter, boolean shareItems, boolean wikiLookups)
	{
		return new RequestRunner.Setup(api, "claude-opus-5-5", "Be brief.", shareCharacter, shareItems, wikiLookups);
	}

	private RequestRunner.Setup setup()
	{
		return setup(false, false, true);
	}

	/** Sends {@code text} and returns the player's message. */
	private Chat.Message send(String text)
	{
		runner.send(chat, text, setup());
		runEdt();
		for (int i = chat.messages.size() - 1; i >= 0; i--)
		{
			if (chat.messages.get(i).role == Chat.Role.USER)
			{
				return chat.messages.get(i);
			}
		}
		throw new AssertionError("no message sent");
	}

	private static ChatApi.Reply reply(String text, long input, long output)
	{
		ChatApi.Reply r = new ChatApi.Reply();
		r.text = text;
		r.model = "claude-opus-5-5-20261001";
		r.usage.input = input;
		r.usage.output = output;
		return r;
	}

	/** {@code pairs} questions and answers, every one of them sent. */
	private void history(int pairs)
	{
		for (int i = 1; i <= pairs; i++)
		{
			chat.messages.add(new Chat.Message(Chat.Role.USER, "Q" + i));
			chat.messages.add(new Chat.Message(Chat.Role.ASSISTANT, "A" + i));
		}
	}

	private static String lastTurn(ChatApi.Conversation c)
	{
		return c.turns.get(c.turns.size() - 1).text;
	}

	@Test
	public void theReplyStreamsInThenLandsOnce()
	{
		Chat.Message question = send("How do I get to Zulrah?");
		assertTrue(chat.isRunning());
		assertEquals("How do I get to Zulrah?", chat.name);
		assertEquals("Claude", chat.answering);
		assertEquals(1, api.sent.size());
		assertEquals("How do I get to Zulrah?", lastTurn(api.last()));
		assertEquals("Thinking.", PanelText.live(chat, chat.runStartedAt));

		api.listener().onPartial("Take the");
		runEdt();
		assertEquals("Take the", chat.liveText);
		assertNull("nothing to say while it writes", PanelText.live(chat, chat.runStartedAt));
		assertTrue(host.live > 0);

		api.listener().onReply(reply("Take the Zul-Andra teleport.", 1200, 80));
		runEdt();
		assertFalse(chat.isRunning());
		assertNull(chat.liveText);
		assertEquals(2, chat.messages.size());
		Chat.Message answer = chat.messages.get(1);
		assertEquals(Chat.Role.ASSISTANT, answer.role);
		assertEquals("Take the Zul-Andra teleport.", answer.text);
		assertEquals("Claude", answer.who);
		assertTrue(answer.activity.isEmpty());
		assertFalse(question.unanswered);
		// The notification and game chat: once, on completion.
		assertEquals(Collections.singletonList(answer), host.ended);
	}

	@Test
	public void repliesAreKeptToGoBackAsTheyCameUntilOneIsBuiltOnText()
	{
		history(1);
		chat.summaryVersion = 2;
		Chat.Message a1 = chat.messages.get(1);
		a1.summaryVersion = 2;
		JsonArray r1 = new JsonArray();
		a1.rawMessages = r1;
		a1.rawKey = "k1";
		Chat.Message q2 = send("Q2");
		assertSame("the earlier reply goes back as it came", r1, api.last().turns.get(1).rawMessages);
		ChatApi.Reply reply = reply("A2", 1, 1);
		JsonArray r2 = new JsonArray();
		reply.rawMessages = r2;
		reply.rawKey = "k2";
		api.listener().onReply(reply);
		runEdt();
		Chat.Message a2 = chat.messages.get(3);
		assertSame(r2, a2.rawMessages);
		assertEquals("k2", a2.rawKey);
		assertEquals(2, q2.summaryVersion);
		assertEquals("made under the summary the question went with", 2, a2.summaryVersion);
		assertSame(r1, a1.rawMessages);

		send("Q3");
		assertSame(r1, api.last().turns.get(1).rawMessages);
		assertSame(r2, api.last().turns.get(3).rawMessages);
		assertEquals("k2", api.last().turns.get(3).rawKey);
		// Claude took the earlier replies only as text this time: this reply was built on that, so they stay text.
		reply = reply("A3", 1, 1);
		JsonArray r3 = new JsonArray();
		reply.rawMessages = r3;
		reply.rawKey = "k3";
		reply.historyAsText = true;
		api.listener().onReply(reply);
		runEdt();
		assertNull(a1.rawMessages);
		assertNull(a1.rawKey);
		assertNull(a2.rawMessages);
		assertNull(a2.rawKey);
		assertSame(r3, chat.messages.get(5).rawMessages);

		send("Q4");
		List<ChatApi.Turn> turns = api.last().turns;
		assertNull(turns.get(1).rawMessages);
		assertNull(turns.get(3).rawMessages);
		assertSame(r3, turns.get(5).rawMessages);
	}

	@Test
	public void aReplyCutShortSaysSo()
	{
		send("hi");
		ChatApi.Reply r = reply("Hello!", 0, 0);
		r.cutShort = true;
		api.listener().onReply(r);
		runEdt();
		assertEquals("Hello!\n\n(The reply was cut short.)", chat.messages.get(1).text);
	}

	@Test
	public void aModelThatCantLookThingsUpSaysSoUnderItsReply()
	{
		send("Vorkath's weaknesses?");
		ChatApi.Reply r = reply("Stab, I think.", 10, 5);
		r.toolsUnavailable = true;
		api.listener().onReply(r);
		runEdt();
		assertEquals(Collections.singletonList(RequestRunner.NO_LOOKUPS), chat.messages.get(1).activity);
	}

	@Test
	public void eachRequestOffersTheToolsItsSettingsAllow()
	{
		runner.send(chat, "What's in my bank?", setup(true, true, false));
		runEdt();
		host.characterRead.accept(null);
		runEdt();
		assertEquals(Arrays.asList("ge_price", "get_equipment", "get_inventory", "get_bank", "get_slayer_task",
			"get_achievement_diaries"), toolNames());
		assertNotNull(api.last().toolRunner);

		api.listener().onReply(reply("a", 1, 1));
		runEdt();
		runner.send(chat, "What drops a whip?", setup(false, false, true));
		runEdt();
		assertEquals(Arrays.asList("wiki_search", "wiki_page", "ge_price"), toolNames());
	}

	private List<String> toolNames()
	{
		List<String> names = new ArrayList<>();
		for (ChatApi.ToolSpec t : api.last().tools)
		{
			names.add(t.name);
		}
		return names;
	}

	@Test
	public void lookUpsArentWantedOnceTheRequestStops()
	{
		send("q");
		assertTrue(host.wanted.getAsBoolean());
		runner.stop(chat);
		// OSRS Wiki searches still waiting their turn aren't sent.
		assertFalse(host.wanted.getAsBoolean());
	}

	@Test
	public void lookUpsShowWhileTheyRunAndStayWithTheReply()
	{
		send("Vorkath's weaknesses?");
		api.listener().onPartial("Let me check.");
		runEdt();

		host.started.run();
		runEdt();
		assertTrue(chat.lookingUp);
		assertEquals("Looking things up\u2026 0s", PanelText.live(chat, chat.runStartedAt));

		host.activity.accept("Read the Wiki page \"Vorkath\"");
		runEdt();
		// Listed above the reply; the line under it still only says the look-ups are going on.
		assertEquals(Collections.singletonList("Read the Wiki page \"Vorkath\""), chat.liveActivity);
		assertTrue(chat.lookingUp);
		assertEquals("Looking things up\u2026 0s", PanelText.live(chat, chat.runStartedAt));

		api.listener().onPartial("Let me check.\n\nVorkath is weak to stab.");
		runEdtSoon();
		assertFalse(chat.lookingUp);
		assertNull("nothing to say while it writes", PanelText.live(chat, chat.runStartedAt));

		api.listener().onReply(reply("Let me check.\n\nVorkath is weak to stab.", 10, 10));
		runEdt();
		Chat.Message answer = chat.messages.get(chat.messages.size() - 1);
		assertEquals(Collections.singletonList("Read the Wiki page \"Vorkath\""), answer.activity);
		assertTrue(chat.liveActivity.isEmpty());
	}

	/** Runs the EDT queue, waiting for a redraw the throttle held back if there's nothing yet. */
	private void runEdtSoon() throws AssertionError
	{
		try
		{
			awaitEdt();
		}
		catch (InterruptedException e)
		{
			throw new AssertionError(e);
		}
	}

	@Test
	public void theTextFromBeforeTheLookUpsDoesntEndThem() throws Exception
	{
		send("q");
		api.listener().onPartial("Let me");
		runEdt();
		// Held back: the last redraw was just now.
		api.listener().onPartial("Let me check.");
		host.started.run();
		runEdt();
		assertTrue(chat.lookingUp);
		// The held-back redraw arrives after the look-up started: same words, still looking things up. (On a slow
		// machine it may have come before it, which is the ordinary case.)
		if (!"Let me check.".equals(chat.liveText))
		{
			awaitEdt();
		}
		assertEquals("Let me check.", chat.liveText);
		assertTrue(chat.lookingUp);
	}

	@Test
	public void theProviderBeingBusyShowsACountdown()
	{
		send("q");
		api.listener().onRetrying("ChatGPT is busy", 6);
		runEdt();
		assertEquals("ChatGPT is busy", chat.retryWhy);
		String status = PanelText.live(chat, System.currentTimeMillis());
		assertTrue(status, status.matches("ChatGPT is busy; trying again in [56]s"));
	}

	@Test
	public void answersToAStoppedRequestDontCount()
	{
		Chat.Message question = send("q");
		ChatApi.Listener listener = api.listener();
		runner.stop(chat);
		assertFalse(chat.isRunning());
		assertTrue(api.requests.get(0).isCancelled());
		assertTrue(question.unanswered);
		Chat.Message stopped = chat.messages.get(chat.messages.size() - 1);
		assertEquals(Chat.Role.NOTE, stopped.role);
		assertEquals("Stopped.", stopped.text);

		int messages = chat.messages.size();
		listener.onPartial("late");
		listener.onRetrying("ChatGPT is busy", 2);
		listener.onReply(reply("late", 1, 1));
		listener.onError(new ChatApi.Failure("late"));
		runEdt();
		assertEquals(messages, chat.messages.size());
		assertNull(chat.liveText);
		assertNull(chat.retryWhy);
		assertTrue(host.ended.isEmpty());
	}

	@Test
	public void stoppingKeepsWhatWasShownOfTheReply()
	{
		Chat.Message question = send("How do I get to Zulrah?");
		api.listener().onPartial("Take the Zul-Andra teleport, then");
		runEdt();
		runner.stop(chat);
		assertEquals(Arrays.asList(Chat.Role.USER, Chat.Role.ASSISTANT, Chat.Role.NOTE), roles());
		Chat.Message partial = chat.messages.get(1);
		assertEquals("still there to read and copy", "Take the Zul-Andra teleport, then", partial.text);
		assertTrue(partial.unfinished);
		assertEquals("Claude", partial.who);
		assertEquals("Stopped.", chat.messages.get(2).text);

		// It doesn't count as the answer: the question can be retried, and goes without it.
		assertSame(question, RequestRunner.retryable(chat));
		assertTrue(runner.retry(chat, setup()));
		runEdt();
		assertEquals(1, api.last().turns.size());
		assertEquals("How do I get to Zulrah?", lastTurn(api.last()));
	}

	@Test
	public void closingRuneLiteMidReplyKeepsWhatWasShownForRetry()
	{
		Chat.Message question = send("How do I get to Zulrah?");
		api.listener().onPartial("Take the Zul-Andra teleport, then");
		runEdt();
		// What the plugin does when RuneLite closes, before the last save.
		runner.stop(chat, "Stopped: RuneLite was closed.");
		assertTrue(api.requests.get(0).isCancelled());
		Chat back = ChatStore.fromJson(new Gson(), ChatStore.toJson(new Gson(), List.of(chat), chat)).chats.get(0);
		assertEquals(Arrays.asList(Chat.Role.USER, Chat.Role.ASSISTANT, Chat.Role.NOTE), roles(back));
		assertEquals("Take the Zul-Andra teleport, then", back.messages.get(1).text);
		assertTrue(back.messages.get(1).unfinished);
		assertEquals(question.text, RequestRunner.retryable(back).text);
	}

	@Test
	public void aReplyThatBreaksOffKeepsItsTextUnlessItWasTakenBack()
	{
		Chat.Message question = send("q");
		api.listener().onPartial("Half a");
		api.listener().onError(new ChatApi.Failure(ChatApi.CUT_OFF));
		runEdt();
		assertEquals(Arrays.asList(Chat.Role.USER, Chat.Role.ASSISTANT, Chat.Role.ERROR), roles());
		assertEquals("Half a", chat.messages.get(1).text);
		assertTrue(chat.messages.get(1).unfinished);
		assertEquals(ChatApi.CUT_OFF, chat.messages.get(2).text);
		assertEquals("only the error is announced", Collections.singletonList(chat.messages.get(2)), host.ended);
		assertSame(question, RequestRunner.retryable(chat));

		// A refusal part way takes back what was written.
		assertTrue(runner.retry(chat, setup()));
		runEdt();
		api.listener().onPartial("Sure, here");
		ChatApi.Failure declined = new ChatApi.Failure("Claude declined to answer that.");
		declined.withdrawn = true;
		api.listener().onError(declined);
		runEdt();
		assertEquals(Arrays.asList(Chat.Role.USER, Chat.Role.ASSISTANT, Chat.Role.ERROR, Chat.Role.ERROR), roles());
	}

	@Test
	public void aLookUpThatFinishesAfterStopIsStillListed()
	{
		send("q");
		host.started.run();
		runEdt();
		runner.stop(chat);
		int changes = host.changed;
		host.activity.accept("Read the Wiki page \"Vorkath\"");
		// The game answered too late: its result was dropped with the request, not sent.
		host.activity.accept("Shared your bank");
		host.activity.accept("Searched your bank for \"rune\"");
		runEdt();
		Chat.Message stopped = chat.messages.get(chat.messages.size() - 1);
		assertEquals(Arrays.asList("Read the Wiki page \"Vorkath\"",
			"Read your bank, but didn't share it: the request had stopped",
			"Searched your bank for \"rune\", but didn't share it: the request had stopped"), stopped.activity);
		assertTrue("shown and saved", host.changed > changes);
	}

	@Test
	public void dataSharedJustBeforeAStopIsListedAsShared()
	{
		send("What's in my bank?");
		host.started.run();
		runEdt();
		// Stop is pressed while the EDT is busy: the click waits there, and meanwhile the bank goes to the provider.
		edt.add(() -> runner.stop(chat));
		host.activity.accept("Shared your bank");
		runEdt();
		Chat.Message stopped = chat.messages.get(chat.messages.size() - 1);
		assertEquals("Stopped.", stopped.text);
		assertEquals(Collections.singletonList("Shared your bank"), stopped.activity);
	}

	@Test
	public void answersForADeletedChatDontCount()
	{
		send("q");
		host.chats.remove(chat);
		api.listener().onReply(reply("a", 1, 1));
		runEdt();
		assertEquals(1, chat.messages.size());
		assertTrue(host.ended.isEmpty());
	}

	@Test
	public void eachRequestLogsTheTokensItUsed()
	{
		// For a developer checking that prompt caching works: one debug line per request, counts and the model only.
		Logger logger = (Logger) LoggerFactory.getLogger(RequestRunner.class);
		Level level = logger.getLevel();
		ListAppender<ILoggingEvent> logged = new ListAppender<>();
		logged.start();
		logger.addAppender(logged);
		logger.setLevel(Level.DEBUG);
		try
		{
			history(20);
			send("Q21");
			ChatApi.Reply summary = reply("The player is training Agility.", 5000, 100);
			summary.usage.cacheWrite = 30;
			api.listener().onReply(summary);
			runEdt();
			ChatApi.Reply answer = reply("Train at Seers' Village.", 1204, 352);
			answer.usage.cacheRead = 3410;
			api.listener().onReply(answer);
			runEdt();
			// Its look-up rounds were billed though it failed; no model answered, so it's the one asked for.
			send("Q22");
			ChatApi.Failure failure = new ChatApi.Failure(ChatApi.TOO_MANY_ROUNDS);
			failure.usage.input = 9000;
			failure.usage.output = 300;
			api.listener().onError(failure);
			runEdt();

			List<String> lines = new ArrayList<>();
			for (ILoggingEvent e : logged.list)
			{
				assertEquals(Level.DEBUG, e.getLevel());
				lines.add(e.getFormattedMessage());
			}
			assertEquals(List.of(
				"summary from claude-opus-5-5-20261001: 5000 input, 0 from cache, 30 written to cache, 100 output tokens",
				"reply from claude-opus-5-5-20261001: 1204 input, 3410 from cache, 0 written to cache, 352 output tokens",
				"failed reply from claude-opus-5-5: 9000 input, 0 from cache, 0 written to cache, 300 output tokens"), lines);
		}
		finally
		{
			logger.detachAppender(logged);
			logger.setLevel(level);
		}
	}

	@Test
	public void characterDetailsReadAfterStopDontSendTheQuestion()
	{
		runner.send(chat, "q", setup(true, false, true));
		runEdt();
		// Still reading the character details: nothing has gone to the provider.
		Consumer<String> stale = host.characterRead;
		runner.stop(chat);

		// The game answers after the Stop: the question stays where it was.
		stale.accept("[Character: Zezima]");
		runEdt();
		assertTrue(api.sent.isEmpty());
		assertFalse(chat.isRunning());
		assertEquals("Stopped.", chat.messages.get(chat.messages.size() - 1).text);
		assertNull(chat.messages.get(0).context);

		// Nor after a Retry: only the new read sends the question, once.
		assertTrue(runner.retry(chat, setup(true, false, true)));
		runEdt();
		host.characterRead.accept("[Character: Zezima]");
		runEdt();
		stale.accept("[Character: Zezima]");
		runEdt();
		assertEquals(1, api.sent.size());
		assertSame(api.requests.get(0), chat.pending);
	}

	@Test
	public void anErrorCanBeRetriedWithTheSameQuestion()
	{
		Chat.Message question = send("q");
		api.listener().onError(new ChatApi.Failure("Anthropic is overloaded or having trouble right now. Try again shortly."));
		runEdt();
		assertTrue(question.unanswered);
		Chat.Message error = chat.messages.get(1);
		assertEquals(Chat.Role.ERROR, error.role);
		assertEquals(Collections.singletonList(error), host.ended);
		assertSame(question, RequestRunner.retryable(chat));

		assertTrue(runner.retry(chat, setup()));
		runEdt();
		assertFalse(question.unanswered);
		assertTrue(chat.isRunning());
		assertNull(RequestRunner.retryable(chat));
		// The same message goes again, and the error isn't part of what's sent.
		assertEquals(2, api.sent.size());
		assertEquals(1, api.last().turns.size());
		assertEquals("q", lastTurn(api.last()));

		api.listener().onReply(reply("a", 1, 1));
		runEdt();
		assertEquals(Arrays.asList(Chat.Role.USER, Chat.Role.ERROR, Chat.Role.ASSISTANT), roles());
		assertNull(RequestRunner.retryable(chat));
		assertFalse(runner.retry(chat, setup()));
	}

	@Test
	public void whatCanBeRetried()
	{
		assertNull("nothing yet", RequestRunner.retryable(chat));
		Chat.Message q1 = new Chat.Message(Chat.Role.USER, "q1");
		chat.messages.add(q1);
		chat.messages.add(new Chat.Message(Chat.Role.ASSISTANT, "a1"));
		assertNull("answered", RequestRunner.retryable(chat));

		Chat.Message q2 = new Chat.Message(Chat.Role.USER, "q2");
		q2.unanswered = true;
		chat.messages.add(q2);
		assertSame("RuneLite closed while it waited", q2, RequestRunner.retryable(chat));
		chat.messages.add(new Chat.Message(Chat.Role.NOTE, "This chat was too long for claude-opus-5-5, so the earlier "
			+ "messages are summarised first and the question is sent again."));
		assertSame("RuneLite closed while it was summarised", q2, RequestRunner.retryable(chat));
		chat.messages.add(new Chat.Message(Chat.Role.NOTE, "Stopped: AI requests were turned off."));
		assertSame(q2, RequestRunner.retryable(chat));
		chat.messages.add(new Chat.Message(Chat.Role.ERROR, "Couldn't reach Anthropic"));
		assertSame(q2, RequestRunner.retryable(chat));

		chat.pending = new ChatApi.Pending();
		assertNull("busy", RequestRunner.retryable(chat));
		chat.pending = null;
		q2.unanswered = false;
		assertNull(RequestRunner.retryable(chat));
	}

	@Test
	public void aLongChatIsSummarisedFirstAndTheNoteGoesBeforeTheQuestion()
	{
		history(20);
		Chat.Message question = send("Q21");
		assertTrue(chat.isSummarizing());
		ChatApi.Conversation summary = api.last();
		assertEquals(ConversationBuilder.SUMMARY_PROMPT, summary.system);
		assertEquals(ConversationBuilder.SUMMARY_MAX_TOKENS, summary.maxTokens);
		assertTrue(summary.tools.isEmpty());
		assertTrue(PanelText.live(chat, chat.runStartedAt).startsWith("Summarising earlier messages\u2026"));
		// The summary isn't shown as it streams in.
		api.listener().onPartial("The player");
		runEdt();
		assertNull(chat.liveText);

		long summarising = chat.runStartedAt;
		api.listener().onReply(reply("The player is training Agility.", 5000, 100));
		runEdt();
		assertFalse(chat.isSummarizing());
		assertTrue(chat.isRunning());
		assertTrue(chat.runStartedAt >= summarising);
		Chat.Message note = chat.messages.get(chat.messages.indexOf(question) - 1);
		assertEquals(Chat.Role.NOTE, note.role);
		assertTrue(note.text, note.text.startsWith("Summary of the 24 earlier messages, sent instead of them:"));

		// Then the question, with the summary instead of the oldest messages.
		assertEquals(2, api.sent.size());
		assertTrue(api.last().turns.get(0).text.startsWith("[Summary of earlier messages in this chat: The player is training Agility.]"));
		assertEquals("Q21", lastTurn(api.last()));
		assertFalse(api.last().tools.isEmpty());
	}

	@Test
	public void stopWhileSummarisingSendsTheWholeChatInstead()
	{
		history(20);
		Chat.Message question = send("Q21");
		runner.stop(chat);
		runEdt();
		assertTrue(api.requests.get(0).isCancelled());
		assertTrue("the question still goes", chat.isRunning());
		assertFalse(question.unanswered);
		Chat.Message note = chat.messages.get(chat.messages.indexOf(question) - 1);
		assertEquals("Couldn't summarise the earlier messages (you skipped it), so the whole chat was sent this time.", note.text);
		assertEquals(2, api.sent.size());
		assertEquals(41, api.last().turns.size());

		// A late answer to the summary changes nothing.
		api.listeners.get(0).onReply(reply("late summary", 1, 1));
		runEdt();
		assertNull(chat.summary);

		// Pressed again, Stop stops the question.
		runner.stop(chat);
		assertFalse(chat.isRunning());
		assertTrue(question.unanswered);
		assertEquals("Stopped.", chat.messages.get(chat.messages.size() - 1).text);
	}

	@Test
	public void aFailedSummarySaysSoBeforeTheQuestion()
	{
		history(20);
		Chat.Message question = send("Q21");
		api.listener().onError(new ChatApi.Failure("Anthropic is overloaded or having trouble right now. Try again shortly."));
		runEdt();
		Chat.Message note = chat.messages.get(chat.messages.indexOf(question) - 1);
		assertTrue(note.text, note.text.startsWith("Couldn't summarise the earlier messages (Anthropic is overloaded"));
		assertEquals(41, api.last().turns.size());
	}

	@Test
	public void aSummaryCutShortIsntUsed()
	{
		history(20);
		Chat.Message question = send("Q21");
		ChatApi.Reply cut = reply("The player is training Agility and wants to", 5000, 4000);
		cut.cutShort = true;
		api.listener().onReply(cut);
		runEdt();
		assertNull(chat.summary);
		for (Chat.Message m : chat.messages)
		{
			assertFalse("nothing is left out", m.summarized);
		}
		Chat.Message note = chat.messages.get(chat.messages.indexOf(question) - 1);
		assertEquals("Couldn't summarise the earlier messages (the summary was cut short), so the whole chat was sent this time.",
			note.text);
		assertEquals(41, api.last().turns.size());
	}

	@Test
	public void aChatTooLongForTheModelIsSummarisedAndAskedAgainOnce()
	{
		// Few messages, but long ones: too long for this model all the same.
		history(3);
		Chat.Message question = send("Q4");
		assertFalse("not long by count", chat.isSummarizing());
		host.activity.accept("Read the Wiki page \"Vorkath\"");
		api.listener().onError(ChatApi.tooLong("claude-opus-5-5", ""));
		runEdt();
		assertTrue("still busy, with the summary", chat.isSummarizing());
		assertFalse(question.unanswered);
		Chat.Message note = chat.messages.get(chat.messages.size() - 1);
		assertEquals("This chat was too long for claude-opus-5-5, so the earlier messages are summarised first and the "
			+ "question is sent again.", note.text);
		assertEquals("what the attempt looked up stays listed", List.of("Read the Wiki page \"Vorkath\""), note.activity);
		assertEquals(ConversationBuilder.SUMMARY_PROMPT, api.last().system);
		assertTrue(lastTurn(api.last()).contains("Player: Q3"));
		assertFalse("not the question itself", lastTurn(api.last()).contains("Q4"));

		api.listener().onReply(reply("The player asked three things.", 50, 10));
		runEdt();
		assertEquals("only the question, under the summary", 1, api.last().turns.size());
		assertTrue(lastTurn(api.last()).endsWith("]\n\nQ4"));

		// Still too long: an error this time, not another summary.
		api.listener().onError(ChatApi.tooLong("claude-opus-5-5", ""));
		runEdt();
		assertFalse(chat.isRunning());
		assertTrue(question.unanswered);
		assertEquals(Chat.Role.ERROR, chat.messages.get(chat.messages.size() - 1).role);
		assertEquals(3, api.sent.size());
	}

	@Test
	public void aTooLongQuestionOnItsOwnIsAnError()
	{
		send("q");
		api.listener().onError(ChatApi.tooLong("claude-opus-5-5", ""));
		runEdt();
		assertEquals(Arrays.asList(Chat.Role.USER, Chat.Role.ERROR), roles());
		assertEquals("This chat is too long for claude-opus-5-5. Start a new chat, or choose a model that can take more.",
			chat.messages.get(1).text);
		assertEquals(1, api.sent.size());
	}

	@Test
	public void aRetryGoesThroughTheSummaryToo()
	{
		history(20);
		Chat.Message question = new Chat.Message(Chat.Role.USER, "Q21");
		question.unanswered = true;
		chat.messages.add(question);
		chat.messages.add(new Chat.Message(Chat.Role.NOTE, "Stopped."));
		assertTrue(runner.retry(chat, setup()));
		runEdt();
		assertTrue(chat.isSummarizing());
		assertEquals(ConversationBuilder.SUMMARY_PROMPT, api.last().system);
	}

	/** A reply that came back with cached own messages, to send back as they came. */
	private static ChatApi.Reply rawReply(String text, JsonArray raw)
	{
		ChatApi.Reply r = reply(text, 1, 1);
		r.rawMessages = raw;
		r.rawKey = "k";
		return r;
	}

	@Test
	public void retryOnTheLatestReplyAsksItsQuestionAgainOnTheSameHistory()
	{
		send("Q1");
		JsonArray r1 = new JsonArray();
		api.listener().onReply(rawReply("A1", r1));
		runEdt();
		Chat.Message a1 = chat.messages.get(1);
		Chat.Message q2 = send("Q2");
		ChatApi.Conversation first = api.last();
		api.listener().onReply(rawReply("A2", new JsonArray()));
		runEdt();
		Chat.Message a2 = chat.messages.get(3);
		assertSame(a2, RequestRunner.regenerable(chat));
		assertFalse("not an older reply", runner.regenerate(chat, a1, setup()));

		assertTrue(runner.regenerate(chat, a2, setup()));
		runEdt();
		assertTrue(chat.isRunning());
		assertEquals("the old reply is gone", Arrays.asList(Chat.Role.USER, Chat.Role.ASSISTANT, Chat.Role.USER), roles());
		assertNull("not while it's being written again", RequestRunner.regenerable(chat));
		// The same question, on the same history: the earlier reply goes back exactly as it came, as it did the first time.
		ChatApi.Conversation again = api.last();
		assertEquals(first.turns.size(), again.turns.size());
		for (int i = 0; i < first.turns.size(); i++)
		{
			assertEquals(first.turns.get(i).text, again.turns.get(i).text);
			assertSame(first.turns.get(i).rawMessages, again.turns.get(i).rawMessages);
		}
		assertSame(r1, again.turns.get(1).rawMessages);
		assertEquals("Q2", lastTurn(again));

		api.listener().onReply(reply("A2, again", 1, 1));
		runEdt();
		assertEquals(Arrays.asList(Chat.Role.USER, Chat.Role.ASSISTANT, Chat.Role.USER, Chat.Role.ASSISTANT), roles());
		assertEquals("A2, again", chat.messages.get(3).text);
		assertSame(q2, chat.messages.get(2));
		assertFalse(q2.unanswered);
	}

	@Test
	public void onlyTheLatestFinishedReplyCanBeWrittenAgain()
	{
		assertNull("nothing yet", RequestRunner.regenerable(chat));
		Chat.Message q1 = send("Q1");
		assertNull("still on its way", RequestRunner.regenerable(chat));
		api.listener().onReply(reply("A1", 1, 1));
		runEdt();
		Chat.Message a1 = chat.messages.get(1);
		assertSame(a1, RequestRunner.regenerable(chat));

		// A question after it: the reply isn't the latest message any more, whatever happens to the question.
		send("Q2");
		api.listener().onError(new ChatApi.Failure("Couldn't reach Anthropic."));
		runEdt();
		assertNull(RequestRunner.regenerable(chat));
		assertFalse(runner.regenerate(chat, a1, setup()));
		assertEquals("Retry goes on the error instead", "Q2", RequestRunner.retryable(chat).text);

		// What was shown of a reply that didn't finish is retried through its question, not written again.
		Chat unfinished = new Chat("x");
		unfinished.messages.add(new Chat.Message(Chat.Role.USER, "q"));
		Chat.Message cut = new Chat.Message(Chat.Role.ASSISTANT, "half");
		cut.unfinished = true;
		unfinished.messages.add(cut);
		assertNull(RequestRunner.regenerable(unfinished));

		// A reply whose question isn't sent any more (left out of an old saved chat) has nothing to ask again.
		Chat orphan = new Chat("y");
		orphan.messages.add(new Chat.Message(Chat.Role.NOTE, "The oldest message of this chat wasn't kept"));
		orphan.messages.add(new Chat.Message(Chat.Role.ASSISTANT, "an answer"));
		assertNull(RequestRunner.regenerable(orphan));
		Chat summarised = new Chat("z");
		Chat.Message old = new Chat.Message(Chat.Role.USER, "q");
		old.summarized = true;
		summarised.messages.add(old);
		summarised.messages.add(new Chat.Message(Chat.Role.ASSISTANT, "a"));
		assertNull(RequestRunner.regenerable(summarised));
		assertFalse(q1.unanswered);
	}

	@Test
	public void characterDetailsAreReadBeforeSending()
	{
		runner.send(chat, "What should I train?", setup(true, false, true));
		runEdt();
		assertTrue("waits for the game", api.sent.isEmpty());
		int changes = host.changed;
		host.characterRead.accept("[Character: Zezima, an ironman]");
		runEdt();
		assertEquals("[Character: Zezima, an ironman]\n\nWhat should I train?", lastTurn(api.last()));
		// The panel shows what went with the message, as soon as it's gone.
		assertEquals("[Character: Zezima, an ironman]", chat.messages.get(0).context);
		assertTrue(host.changed > changes);
	}

	@Test
	public void nothingIsSentOnceAiRequestsAreOff()
	{
		runner.send(chat, "q", setup(true, false, true));
		runEdt();
		host.ai = false;
		host.characterRead.accept(null);
		runEdt();
		assertTrue(api.sent.isEmpty());
		assertFalse(chat.isRunning());
		assertEquals("Stopped: AI requests are turned off.", chat.messages.get(chat.messages.size() - 1).text);
		assertTrue(chat.messages.get(0).unanswered);
	}

	@Test
	public void characterDetailsTurnedOffMeanwhileDontGo()
	{
		history(20);
		chat.messages.get(38).context = "[Character: Old note]";
		runner.send(chat, "Q21", setup(true, false, true));
		runEdt();
		assertTrue(chat.isSummarizing());
		// "Share character overview" is turned off while the summary is being made.
		host.character = false;
		api.listener().onReply(reply("The player is training Agility.", 50, 10));
		runEdt();
		assertNull("not even read", host.characterRead);
		assertEquals(2, api.sent.size());
		for (ChatApi.Turn t : api.last().turns)
		{
			assertFalse("earlier notes stay out too: " + t.text, t.text.contains("[Character:"));
		}
		assertNull(chat.messages.get(chat.messages.size() - 1).context);

		// Or while they're being read.
		api.listener().onReply(reply("a", 1, 1));
		runEdt();
		host.character = true;
		runner.send(chat, "Q22", setup(true, false, true));
		runEdt();
		host.character = false;
		host.characterRead.accept("[Character: Zezima, an ironman]");
		runEdt();
		assertEquals("Q22", lastTurn(api.last()));
		assertNull(chat.messages.get(chat.messages.size() - 1).context);
	}

	@Test
	public void noSummaryGoesOnceAiRequestsAreOff()
	{
		history(3);
		Chat.Message question = send("Q4");
		// Turned off just now: the stop that brings is still waiting its turn on the EDT when the provider answers.
		host.ai = false;
		api.listener().onError(ChatApi.tooLong("claude-opus-5-5", ""));
		runEdt();
		assertEquals("no summary request", 1, api.sent.size());
		assertFalse(chat.isRunning());
		assertTrue(question.unanswered);
		assertEquals("Stopped: AI requests are turned off.", chat.messages.get(chat.messages.size() - 1).text);
		assertSame(question, RequestRunner.retryable(chat));

		// Nor for a chat that's long.
		history(20);
		runner.send(chat, "Q25", setup());
		runEdt();
		assertEquals(1, api.sent.size());
		assertFalse(chat.isRunning());
	}

	@Test
	public void aRequestThatCantBeBuiltSaysSo()
	{
		api.failure = new IllegalArgumentException("Unexpected char in header value: sk-ant-secret");
		Chat.Message question = send("q");
		assertFalse(chat.isRunning());
		assertTrue(question.unanswered);
		Chat.Message error = chat.messages.get(1);
		assertEquals(RequestRunner.COULDNT_SEND, error.text);
		assertFalse("the key never shows", error.text.contains("sk-ant"));
		assertSame(question, RequestRunner.retryable(chat));
	}

	private List<Chat.Role> roles()
	{
		return roles(chat);
	}

	private static List<Chat.Role> roles(Chat c)
	{
		List<Chat.Role> roles = new ArrayList<>();
		for (Chat.Message m : c.messages)
		{
			roles.add(m.role);
		}
		return roles;
	}
}

