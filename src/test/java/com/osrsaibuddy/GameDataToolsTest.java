package com.osrsaibuddy;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/** The game-data tools: what they offer, when they share, and that every call ends exactly once, off the game's thread. */
public class GameDataToolsTest
{
	private static final String CLIENT_THREAD = "test client thread";
	/** The settings that let the assistant see the player's items, and their character, as the settings panel names them. */
	private static final String ITEMS = SettingName.of("shareItems");
	private static final String CHARACTER = SettingName.of("sendCharacter");

	private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
	/** Stands in for the game's client thread. */
	private final ExecutorService client = Executors.newSingleThreadExecutor(r -> new Thread(r, CLIENT_THREAD));
	private final FakeGame game = new FakeGame();
	private final List<String> activity = new CopyOnWriteArrayList<>();
	private boolean shareItems = true;
	private boolean shareCharacter = true;

	@After
	public void stop()
	{
		executor.shutdownNow();
		client.shutdownNow();
	}

	/** Canned game data; counts reads and remembers which thread they ran on. */
	private static final class FakeGame implements GameDataTools.Game
	{
		boolean loggedIn = true;
		GameData.Bank bank;
		RuntimeException failure;
		final AtomicInteger reads = new AtomicInteger();
		volatile String readOn;

		private void read()
		{
			reads.incrementAndGet();
			readOn = Thread.currentThread().getName();
			if (failure != null)
			{
				throw failure;
			}
		}

		@Override
		public boolean loggedIn()
		{
			return loggedIn;
		}

		@Override
		public List<GameData.ItemLine> equipment()
		{
			read();
			return Collections.singletonList(new GameData.ItemLine("Weapon", "Abyssal whip", false, 1, 1_400_000));
		}

		@Override
		public GameData.Items inventory()
		{
			read();
			return new GameData.Items(Collections.singletonList(new GameData.ItemLine(null, "Shark", false, 3, 1000)), 3);
		}

		@Override
		public GameData.Bank bank()
		{
			read();
			return bank;
		}

		@Override
		public GameData.SlayerTask slayerTask()
		{
			read();
			return new GameData.SlayerTask("Abyssal demons", 87, 150, null, 1234, 56);
		}

		@Override
		public List<GameData.Diary> diaries()
		{
			read();
			return GameData.diaries(id -> 1);
		}
	}

	private GameDataTools tools(Consumer<Runnable> clientThread, long timeoutMillis)
	{
		return new GameDataTools(game, clientThread, executor, () -> shareItems, () -> shareCharacter, activity::add,
			timeoutMillis);
	}

	private GameDataTools tools()
	{
		return tools(client::execute, GameDataTools.READ_TIMEOUT_MILLIS);
	}

	/** The result of one call, and the thread it came back on. */
	private static final class Ended
	{
		ChatApi.ToolResult result;
		String thread;
		final AtomicInteger times = new AtomicInteger();
	}

	private Ended run(GameDataTools tools, String name, JsonObject input) throws InterruptedException
	{
		Ended ended = new Ended();
		CountDownLatch done = new CountDownLatch(1);
		tools.run(name, input, r ->
		{
			ended.result = r;
			ended.thread = Thread.currentThread().getName();
			ended.times.incrementAndGet();
			done.countDown();
		});
		assertTrue("no result", done.await(10, TimeUnit.SECONDS));
		return ended;
	}

	private static List<String> names(List<ChatApi.ToolSpec> specs)
	{
		List<String> names = new ArrayList<>();
		for (ChatApi.ToolSpec s : specs)
		{
			names.add(s.name);
		}
		return names;
	}

	@Test
	public void offersOnlyWhatTheSettingsShare()
	{
		assertEquals(Arrays.asList("get_equipment", "get_inventory", "get_bank", "get_slayer_task", "get_achievement_diaries"),
			names(tools().specs()));
		shareCharacter = false;
		assertEquals(Arrays.asList("get_equipment", "get_inventory", "get_bank"), names(tools().specs()));
		shareItems = false;
		shareCharacter = true;
		assertEquals(Arrays.asList("get_slayer_task", "get_achievement_diaries"), names(tools().specs()));
		shareCharacter = false;
		assertTrue(tools().specs().isEmpty());

		for (String name : Arrays.asList("get_equipment", "get_inventory", "get_bank", "get_slayer_task", "get_achievement_diaries"))
		{
			assertTrue(name, GameDataTools.handles(name));
		}
		assertFalse(GameDataTools.handles("wiki_search"));
	}

	@Test
	public void specsAreTheSameEveryTime()
	{
		// They're part of the cached prompt, so a new set for the next reply must match byte for byte.
		List<ChatApi.ToolSpec> a = tools().specs();
		List<ChatApi.ToolSpec> b = tools().specs();
		for (int i = 0; i < a.size(); i++)
		{
			assertEquals(a.get(i).description, b.get(i).description);
			assertEquals(a.get(i).inputSchema.toString(), b.get(i).inputSchema.toString());
			assertEquals("object", a.get(i).inputSchema.get("type").getAsString());
			assertTrue(a.get(i).inputSchema.get("properties").isJsonObject());
		}
		JsonObject bank = a.get(2).inputSchema.getAsJsonObject("properties").getAsJsonObject("search");
		assertEquals("string", bank.get("type").getAsString());
		assertFalse(a.get(2).inputSchema.has("required"));
	}

	@Test
	public void sharesEquipmentReadOnTheClientThreadAndAnswersOffIt() throws Exception
	{
		Ended e = run(tools(), "get_equipment", new JsonObject());
		assertFalse(e.result.error);
		assertTrue(e.result.content, e.result.content.contains("- Weapon: Abyssal whip (1,400,000 gp)"));
		assertEquals(CLIENT_THREAD, game.readOn);
		assertNotEquals(CLIENT_THREAD, e.thread);
		assertEquals(Collections.singletonList("Shared your equipment"), activity);
	}

	@Test
	public void sharesInventorySlayerTaskAndDiaries() throws Exception
	{
		Ended inv = run(tools(), "get_inventory", null);
		assertTrue(inv.result.content, inv.result.content.startsWith("The player's inventory (3 of 28 slots used)"));
		Ended slayer = run(tools(), "get_slayer_task", new JsonObject());
		assertTrue(slayer.result.content, slayer.result.content.startsWith("The player's Slayer task: Abyssal demons, 87 left of 150."));
		Ended diaries = run(tools(), "get_achievement_diaries", new JsonObject());
		// Every varbit at 1: all done but Karamja's first three tiers, which need 2.
		assertTrue(diaries.result.content, diaries.result.content.endsWith("Completed 45 of 48 tiers."));
		assertEquals(Arrays.asList("Shared your inventory", "Shared your Slayer task", "Shared your achievement diaries"), activity);
		// The panel knows these lines by how they start (PanelText.summary), and names what was shared over the reply.
		assertEquals("Shared your inventory, Slayer task and achievement diaries", PanelText.summary(activity).sharing);
	}

	@Test
	public void sharesNothingWithTheSettingOff() throws Exception
	{
		// Offered earlier in the reply, then turned off: the next call is turned down without reading the game.
		GameDataTools tools = tools();
		shareItems = false;
		Ended e = run(tools, "get_bank", new JsonObject());
		assertTrue(e.result.error);
		// Each setting by the name the settings panel shows.
		assertEquals("The player hasn't turned on \"" + ITEMS + "\" in OSRS AI Buddy's settings, so their bank can't be read. "
			+ "They can turn it on if they'd like you to see it.", e.result.content);
		shareCharacter = false;
		Ended slayer = run(tools, "get_slayer_task", new JsonObject());
		assertTrue(slayer.result.content, slayer.result.content.contains("\"" + CHARACTER + "\""));
		assertEquals(0, game.reads.get());
		assertEquals(Arrays.asList("Didn't share your bank: \"" + ITEMS + "\" is off",
			"Didn't share your Slayer task: \"" + CHARACTER + "\" is off"), activity);
	}

	@Test
	public void needsThePlayerLoggedIn() throws Exception
	{
		game.loggedIn = false;
		Ended e = run(tools(), "get_inventory", new JsonObject());
		assertTrue(e.result.error);
		assertEquals("The player isn't logged in, so their inventory can't be read. Ask them to log in first.", e.result.content);
		assertEquals(0, game.reads.get());
		assertEquals(Collections.singletonList("Couldn't share your inventory: you're not logged in"), activity);
	}

	@Test
	public void bankNotSeenYet() throws Exception
	{
		Ended e = run(tools(), "get_bank", new JsonObject());
		assertTrue(e.result.error);
		assertEquals("The bank can only be read while it's open, and the player hasn't opened it since OSRS AI Buddy or \"" + ITEMS
			+ "\" was turned on, or they logged in to this account. Ask them to open their bank once, then ask again.",
			e.result.content);
		assertEquals(Collections.singletonList("Couldn't share your bank: open it once so OSRS AI Buddy can see it"), activity);
	}

	@Test
	public void bankSearch() throws Exception
	{
		game.bank = new GameData.Bank(Arrays.asList(new GameData.ItemLine(null, "Shark", false, 250, 1000),
			new GameData.ItemLine(null, "Rune platebody", false, 1, 38_000)), System.currentTimeMillis());
		JsonObject input = new JsonObject();
		input.addProperty("search", "  rune\n ");
		Ended e = run(tools(), "get_bank", input);
		assertFalse(e.result.error);
		assertTrue(e.result.content, e.result.content.startsWith("Items in the player's bank with \"rune\" in their name"));
		assertTrue(e.result.content, e.result.content.contains("- Rune platebody (38,000 gp)"));
		assertFalse(e.result.content, e.result.content.contains("Shark"));

		// A blank search is the whole bank.
		input.addProperty("search", " ");
		Ended all = run(tools(), "get_bank", input);
		assertTrue(all.result.content, all.result.content.contains("- Shark x250"));
		assertEquals(Arrays.asList("Searched your bank for \"rune\"", "Shared your bank"), activity);
		assertEquals("Shared your bank", PanelText.summary(activity).sharing);
		assertEquals("Searched your bank", PanelText.summary(activity.subList(0, 1)).sharing);
	}

	@Test
	public void aFailedReadIsAnErrorNotACrash() throws Exception
	{
		game.failure = new IllegalStateException("no item data");
		Ended e = run(tools(), "get_equipment", new JsonObject());
		assertTrue(e.result.error);
		assertEquals("Reading the game failed inside OSRS AI Buddy. Answer without it.", e.result.content);
		assertNotEquals(CLIENT_THREAD, e.thread);
		assertEquals(Collections.singletonList("Couldn't read your equipment"), activity);
	}

	@Test
	public void givesUpWhenTheClientThreadNeverRuns() throws Exception
	{
		List<Runnable> neverRun = new CopyOnWriteArrayList<>();
		GameDataTools tools = tools(neverRun::add, 100);
		Ended e = run(tools, "get_inventory", new JsonObject());
		assertTrue(e.result.error);
		assertTrue(e.result.content, e.result.content.startsWith("The game didn't answer in time"));
		assertEquals(Collections.singletonList("Couldn't read your inventory: the game didn't answer"), activity);

		// If the game gets to it after all, the call has already ended: no second result.
		neverRun.get(0).run();
		executor.submit(() -> { }).get(5, TimeUnit.SECONDS);
		assertEquals(1, e.times.get());
		assertEquals(1, activity.size());
	}

	@Test
	public void clientThreadUnavailable() throws Exception
	{
		Ended e = run(tools(r ->
		{
			throw new IllegalStateException("closing");
		}, GameDataTools.READ_TIMEOUT_MILLIS), "get_equipment", new JsonObject());
		assertTrue(e.result.error);
		assertEquals(Collections.singletonList("Couldn't read your equipment"), activity);
	}

	@Test
	public void unknownTool() throws Exception
	{
		Ended e = run(tools(), "get_location", new JsonObject());
		assertTrue(e.result.error);
		assertEquals("There's no tool called get_location.", e.result.content);
		assertEquals(0, game.reads.get());
		assertEquals(Collections.singletonList("Skipped an unknown look-up \"get_location\""), activity);
	}

	@Test
	public void aBrokenActivityLineStillEndsTheCall() throws Exception
	{
		GameDataTools tools = new GameDataTools(game, client::execute, executor, () -> true, () -> true, line ->
		{
			throw new IllegalStateException("panel gone");
		});
		Ended e = run(tools, "get_equipment", new JsonObject());
		assertFalse(e.result.error);
	}

	@Test
	public void aResultThatWasNeverSentIsntCalledShared()
	{
		assertEquals("Read your equipment, but didn't share it: the request had stopped",
			GameDataTools.unshared("Shared your equipment"));
		// Nothing was shared anyway.
		String off = "Didn't share your bank: \"" + ITEMS + "\" is off";
		assertEquals(off, GameDataTools.unshared(off));
		// Nor does the panel say so over the reply.
		assertEquals(null, PanelText.summary(Arrays.asList(GameDataTools.unshared("Shared your equipment"),
			GameDataTools.unshared("Searched your bank for \"rune\""), off)).sharing);
	}
}

