package com.osrsaibuddy;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;

/**
 * The tools that go with one request, and the runner that answers the model's calls to them: the look-ups first
 * (wiki_search and wiki_page with "Wiki look-ups", ge_price always), then the game-data tools the sharing settings allow
 * (get_equipment, get_inventory, get_bank, get_slayer_task, get_achievement_diaries). Always in that order: the tools
 * are part of the prompt the provider caches, and changing them sends earlier replies as plain text.
 */
@Slf4j
final class ToolBox implements ChatApi.ToolRunner
{
	/** What the tools of every request are made from: the plugin's own, or a test's stand-ins. */
	static final class Parts
	{
		private final WikiClient wiki;
		private final LookupTools.Prices prices;
		private final GameDataTools.Game game;
		private final Consumer<Runnable> clientThread;
		private final ScheduledExecutorService executor;
		private final BooleanSupplier shareItems;
		private final BooleanSupplier shareCharacter;

		/**
		 * {@code shareItems}, {@code shareCharacter}: whether the settings allow sharing right now, AI requests
		 * included; read on the tools' own threads.
		 */
		Parts(WikiClient wiki, LookupTools.Prices prices, GameDataTools.Game game, Consumer<Runnable> clientThread,
			ScheduledExecutorService executor, BooleanSupplier shareItems, BooleanSupplier shareCharacter)
		{
			this.wiki = wiki;
			this.prices = prices;
			this.game = game;
			this.clientThread = clientThread;
			this.executor = executor;
			this.shareItems = shareItems;
			this.shareCharacter = shareCharacter;
		}

		/**
		 * The tools for a request sent with {@code setup}. {@code wanted}: false once the request has stopped.
		 * {@code activity} hears one line per call, and {@code started} when a call starts.
		 */
		ToolBox forRequest(RequestRunner.Setup setup, BooleanSupplier wanted, Consumer<String> activity, Runnable started)
		{
			LookupTools lookups = new LookupTools(setup.wikiLookups ? wiki : null, prices, activity, wanted);
			// The settings as they were when the player sent the message, and as they are at each call: turning one off
			// while a reply is being written stops the sharing at once.
			GameDataTools gameData = new GameDataTools(game, clientThread, executor,
				() -> setup.shareItems && shareItems.getAsBoolean(),
				() -> setup.shareCharacter && shareCharacter.getAsBoolean(),
				activity);
			return new ToolBox(lookups, gameData, started);
		}
	}

	private final LookupTools lookups;
	private final GameDataTools gameData;
	/** Told when a call starts, on the provider's thread, so the panel can say it's looking things up. */
	private final Runnable started;

	ToolBox(LookupTools lookups, GameDataTools gameData, Runnable started)
	{
		this.lookups = lookups;
		this.gameData = gameData;
		this.started = started;
	}

	List<ChatApi.ToolSpec> specs()
	{
		List<ChatApi.ToolSpec> specs = new ArrayList<>(lookups.specs());
		specs.addAll(gameData.specs());
		return specs;
	}

	/** Offers the tools with {@code conversation}, and answers its calls to them. */
	void addTo(ChatApi.Conversation conversation)
	{
		conversation.tools.addAll(specs());
		conversation.toolRunner = this;
	}

	@Override
	public void run(String name, JsonObject input, Consumer<ChatApi.ToolResult> done)
	{
		try
		{
			started.run();
		}
		catch (RuntimeException e)
		{
			// Only the status line is lost.
			log.warn("look-up start not shown", e);
		}
		// Each runner checks its own settings again, so a call to a tool that wasn't offered is turned down there.
		if (LookupTools.handles(name))
		{
			lookups.run(name, input, done);
		}
		else if (GameDataTools.handles(name))
		{
			gameData.run(name, input, done);
		}
		else
		{
			done.accept(ChatApi.ToolResult.error("There's no tool called " + name + "."));
		}
	}
}
