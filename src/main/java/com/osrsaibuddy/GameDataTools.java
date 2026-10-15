package com.osrsaibuddy;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

/**
 * The tools that read the player's own account, offered only with the settings that share it: equipment, inventory
 * and bank with "Share bank, inventory & gear", Slayer task and achievement diaries with "Share character overview". Made for one
 * reply: every call reads the game on the client thread, writes the answer up off it, and ends with one line for the
 * panel saying what was shared ("Shared your equipment"), told to {@code activity} just before the result goes back.
 */
@Slf4j
final class GameDataTools implements ChatApi.ToolRunner
{
	static final String GET_EQUIPMENT = "get_equipment";
	static final String GET_INVENTORY = "get_inventory";
	static final String GET_BANK = "get_bank";
	static final String GET_SLAYER_TASK = "get_slayer_task";
	static final String GET_ACHIEVEMENT_DIARIES = "get_achievement_diaries";

	/** The client thread runs queued work every frame, also on the login screen; this is in case it can't. */
	static final long READ_TIMEOUT_MILLIS = 5000;
	private static final int MAX_SEARCH = 100;
	private static final String FAILED = "Reading the game failed inside OSRS AI Buddy. Answer without it.";
	private static final String ITEMS_SETTING = "Share bank, inventory & gear";
	/**
	 * How the lines for what was shared start: the panel names what was shared, from these, on the line over each reply
	 * (see {@link PanelText#summary}).
	 */
	static final String SHARED = "Shared your ";
	static final String SEARCHED = "Searched your bank for ";
	/** Ends the line for what was read but never sent: the request had stopped (see {@link #unshared}). */
	static final String UNSHARED = ", but didn't share it: the request had stopped";
	/** Starts the line for what was read but never sent, in place of {@link #SHARED}. */
	static final String READ = "Read your ";
	private static final String CHARACTER_SETTING = "Share character overview";

	/**
	 * Where the answers come from: {@link GameData} in the game, canned data in tests. Client thread only, and only
	 * while the player is logged in.
	 */
	interface Game
	{
		boolean loggedIn();

		List<GameData.ItemLine> equipment();

		GameData.Items inventory();

		/**
		 * Null when the bank hasn't been seen on this account since it could be: since OSRS AI Buddy, "Share bank, inventory & gear" or
		 * AI requests was last turned on.
		 */
		GameData.Bank bank();

		GameData.SlayerTask slayerTask();

		List<GameData.Diary> diaries();
	}

	private final Game game;
	/** Runs work on the client thread: ClientThread.invoke in the game. */
	private final Consumer<Runnable> clientThread;
	private final ScheduledExecutorService executor;
	private final BooleanSupplier shareItems;
	private final BooleanSupplier shareCharacter;
	private final Consumer<String> activity;
	private final long timeoutMillis;

	/**
	 * {@code shareItems} and {@code shareCharacter}: the settings, read again for every call, so turning one off while
	 * a reply is being written stops the sharing at once.
	 */
	GameDataTools(Game game, Consumer<Runnable> clientThread, ScheduledExecutorService executor,
		BooleanSupplier shareItems, BooleanSupplier shareCharacter, Consumer<String> activity)
	{
		this(game, clientThread, executor, shareItems, shareCharacter, activity, READ_TIMEOUT_MILLIS);
	}

	GameDataTools(Game game, Consumer<Runnable> clientThread, ScheduledExecutorService executor,
		BooleanSupplier shareItems, BooleanSupplier shareCharacter, Consumer<String> activity, long timeoutMillis)
	{
		this.game = game;
		this.clientThread = clientThread;
		this.executor = executor;
		this.shareItems = shareItems;
		this.shareCharacter = shareCharacter;
		this.activity = activity;
		this.timeoutMillis = timeoutMillis;
	}

	static boolean handles(String name)
	{
		return GET_EQUIPMENT.equals(name) || GET_INVENTORY.equals(name) || GET_BANK.equals(name)
			|| GET_SLAYER_TASK.equals(name) || GET_ACHIEVEMENT_DIARIES.equals(name);
	}

	/**
	 * The tools the settings allow right now, always in the same order and with the same text: they're part of the
	 * prompt the provider caches.
	 */
	List<ChatApi.ToolSpec> specs()
	{
		List<ChatApi.ToolSpec> specs = new ArrayList<>();
		if (shareItems.getAsBoolean())
		{
			specs.add(new ChatApi.ToolSpec(GET_EQUIPMENT,
				"The player's worn equipment right now: the item in each slot, with Grand Exchange prices.",
				schema(new JsonObject())));
			specs.add(new ChatApi.ToolSpec(GET_INVENTORY,
				"The player's inventory right now: items and amounts with Grand Exchange prices, and how many of the "
					+ GameData.INVENTORY_SLOTS + " slots are used.",
				schema(new JsonObject())));
			JsonObject bank = new JsonObject();
			bank.add("search", property("string", "Optional: only items whose names contain this, e.g. \"rune\" or "
				+ "\"potion\". Leave it out for the whole bank; a big bank is cut at " + GameData.BANK_LINES + " items."));
			specs.add(new ChatApi.ToolSpec(GET_BANK,
				"The player's bank as it was the last time they had it open: items and amounts with Grand Exchange "
					+ "prices, placeholders left out. Unknown until they open their bank.",
				schema(bank)));
		}
		if (shareCharacter.getAsBoolean())
		{
			specs.add(new ChatApi.ToolSpec(GET_SLAYER_TASK,
				"The player's current Slayer task: the monster, how many are left of how many, the area it must be "
					+ "done in if any, and their Slayer points and task streak.",
				schema(new JsonObject())));
			specs.add(new ChatApi.ToolSpec(GET_ACHIEVEMENT_DIARIES,
				"Which achievement diary tiers (easy, medium, hard, elite) the player has completed in each region.",
				schema(new JsonObject())));
		}
		return specs;
	}

	private static JsonObject property(String type, String description)
	{
		JsonObject p = new JsonObject();
		p.addProperty("type", type);
		p.addProperty("description", description);
		return p;
	}

	private static JsonObject schema(JsonObject properties)
	{
		JsonObject s = new JsonObject();
		s.addProperty("type", "object");
		s.add("properties", properties);
		return s;
	}

	@Override
	public void run(String name, JsonObject input, Consumer<ChatApi.ToolResult> done)
	{
		Report report = new Report(done);
		try
		{
			if (GET_EQUIPMENT.equals(name))
			{
				readItems(report, "equipment", game::equipment, worn -> shared("equipment", GameData.equipmentText(worn)));
			}
			else if (GET_INVENTORY.equals(name))
			{
				readItems(report, "inventory", game::inventory, inv -> shared("inventory", GameData.inventoryText(inv)));
			}
			else if (GET_BANK.equals(name))
			{
				String search = search(input);
				long now = System.currentTimeMillis();
				readItems(report, "bank", game::bank, bank -> bankOutcome(bank, search, now));
			}
			else if (GET_SLAYER_TASK.equals(name))
			{
				readCharacter(report, "Slayer task", game::slayerTask, task -> shared("Slayer task", GameData.slayerText(task)));
			}
			else if (GET_ACHIEVEMENT_DIARIES.equals(name))
			{
				readCharacter(report, "achievement diaries", game::diaries,
					diaries -> shared("achievement diaries", GameData.diaryText(diaries)));
			}
			else
			{
				report.send(new Outcome("Skipped an unknown look-up \"" + ChatApi.shorten(name, 60) + "\"",
					ChatApi.ToolResult.error("There's no tool called " + name + ".")));
			}
		}
		catch (RuntimeException e)
		{
			// A bug, not the player's or the model's doing; the reply can still carry on without this.
			log.warn("game data tool {} failed", name, e);
			report.send(new Outcome("Couldn't read the game (" + name + ")", ChatApi.ToolResult.error(FAILED)));
		}
	}

	/** A call's ending: its line for the panel and its result for the model. */
	private static final class Outcome
	{
		final String line;
		final ChatApi.ToolResult result;

		Outcome(String line, ChatApi.ToolResult result)
		{
			this.line = line;
			this.result = result;
		}
	}

	private static Outcome shared(String what, String text)
	{
		return new Outcome(SHARED + what, ChatApi.ToolResult.ok(text));
	}

	/**
	 * The line for a call whose result was never sent: the request had stopped by the time the game answered, and a
	 * stopped request sends nothing more. "Shared your bank" becomes "Read your bank, but didn't share it: the request
	 * had stopped". Lines for calls that shared nothing anyway stay as they are.
	 */
	static String unshared(String line)
	{
		if (line.startsWith(SHARED))
		{
			return READ + line.substring(SHARED.length()) + UNSHARED;
		}
		return line.startsWith(SEARCHED) ? line + UNSHARED : line;
	}

	private static Outcome bankOutcome(GameData.Bank bank, String search, long now)
	{
		if (bank == null)
		{
			return new Outcome("Couldn't share your bank: open it once so OSRS AI Buddy can see it",
				ChatApi.ToolResult.error("The bank can only be read while it's open, and the player hasn't opened it since OSRS AI Buddy or \""
					+ ITEMS_SETTING + "\" was turned on, or they logged in to this account. Ask them to open their bank once, "
					+ "then ask again."));
		}
		String line = search == null ? SHARED + "bank" : SEARCHED + "\"" + ChatApi.shorten(search, 60) + "\"";
		return new Outcome(line, ChatApi.ToolResult.ok(GameData.bankText(bank, search, now)));
	}

	/** Equipment, inventory and bank: only with "Share bank, inventory & gear". */
	private <T> void readItems(Report report, String what, Supplier<T> read, Function<T, Outcome> write)
	{
		if (!shareItems.getAsBoolean())
		{
			report.send(settingOff(what, ITEMS_SETTING));
			return;
		}
		readThenWrite(report, what, read, write);
	}

	/** Slayer task and diaries: only with "Share character overview". */
	private <T> void readCharacter(Report report, String what, Supplier<T> read, Function<T, Outcome> write)
	{
		if (!shareCharacter.getAsBoolean())
		{
			report.send(settingOff(what, CHARACTER_SETTING));
			return;
		}
		readThenWrite(report, what, read, write);
	}

	private static Outcome settingOff(String what, String setting)
	{
		return new Outcome("Didn't share your " + what + ": \"" + setting + "\" is off",
			ChatApi.ToolResult.error("The player hasn't turned on \"" + setting + "\" in OSRS AI Buddy's settings, so their "
				+ what + " can't be read. They can turn it on if they'd like you to see it."));
	}

	/**
	 * Reads on the client thread, then writes the answer up off it: the reply carries on from {@code done} (building
	 * and sending the next request), which shouldn't hold up the game.
	 */
	private <T> void readThenWrite(Report report, String what, Supplier<T> read, Function<T, Outcome> write)
	{
		Future<?> timeout = null;
		try
		{
			timeout = executor.schedule(() -> report.send(new Outcome("Couldn't read your " + what + ": the game didn't answer",
				ChatApi.ToolResult.error("The game didn't answer in time, so the player's " + what + " couldn't be "
					+ "read. Try once more, or answer without it."))), timeoutMillis, TimeUnit.MILLISECONDS);
		}
		catch (RuntimeException e)
		{
			// RuneLite is closing; read without a time limit.
			log.debug("no time limit for reading the game", e);
		}
		Future<?> limit = timeout;
		// A Runnable, so it runs once: a lambda that returned a boolean would be taken as the kind that runs again
		// every frame until it returns true.
		Runnable onClientThread = () ->
		{
			if (limit != null)
			{
				limit.cancel(false);
			}
			boolean loggedIn;
			T value;
			try
			{
				loggedIn = game.loggedIn();
				value = loggedIn ? read.get() : null;
			}
			catch (RuntimeException e)
			{
				// The game's data wasn't there to read (a game update, a loading screen).
				log.warn("couldn't read the player's {}", what, e);
				handBack(() -> report.send(new Outcome("Couldn't read your " + what, ChatApi.ToolResult.error(FAILED))));
				return;
			}
			handBack(() -> report.write(what, () -> loggedIn ? write.apply(value) : notLoggedIn(what)));
		};
		try
		{
			clientThread.accept(onClientThread);
		}
		catch (RuntimeException e)
		{
			log.debug("couldn't reach the client thread", e);
			if (limit != null)
			{
				limit.cancel(false);
			}
			report.send(new Outcome("Couldn't read your " + what, ChatApi.ToolResult.error(FAILED)));
		}
	}

	private static Outcome notLoggedIn(String what)
	{
		return new Outcome("Couldn't share your " + what + ": you're not logged in",
			ChatApi.ToolResult.error("The player isn't logged in, so their " + what + " can't be read. Ask them to log "
				+ "in first."));
	}

	/** Off the client thread before the reply carries on. */
	private void handBack(Runnable r)
	{
		try
		{
			executor.execute(r);
		}
		catch (RuntimeException e)
		{
			// RuneLite is closing; the reply won't get far, but it still hears back.
			r.run();
		}
	}

	/** The bank search the model gave, on one line and capped; null for the whole bank. */
	private static String search(JsonObject input)
	{
		JsonElement e = input == null ? null : input.get("search");
		if (e == null || !e.isJsonPrimitive())
		{
			return null;
		}
		String s = e.getAsString().replaceAll("[\\s\\p{Cc}]+", " ").trim();
		if (s.isEmpty())
		{
			return null;
		}
		return s.length() <= MAX_SEARCH ? s : s.substring(0, MAX_SEARCH).trim();
	}

	/** One call's ending: its activity line, then its result, together and only once. */
	private final class Report
	{
		private final AtomicBoolean sent = new AtomicBoolean();
		private final Consumer<ChatApi.ToolResult> done;

		Report(Consumer<ChatApi.ToolResult> done)
		{
			this.done = done;
		}

		/** For an outcome written up later, on another thread: a bug while writing it up still ends the call. */
		void write(String what, Supplier<Outcome> outcome)
		{
			Outcome o;
			try
			{
				o = outcome.get();
			}
			catch (RuntimeException e)
			{
				log.warn("couldn't write up the player's {}", what, e);
				o = new Outcome("Couldn't read your " + what, ChatApi.ToolResult.error(FAILED));
			}
			send(o);
		}

		void send(Outcome o)
		{
			if (!sent.compareAndSet(false, true))
			{
				return;
			}
			try
			{
				activity.accept(o.line);
			}
			catch (RuntimeException e)
			{
				log.warn("game data activity not shown", e);
			}
			done.accept(o.result);
		}
	}
}
