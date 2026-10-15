package com.osrsaibuddy;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.runelite.http.api.item.ItemPrice;
import okhttp3.OkHttpClient;
import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** The tools that go with a request: which, in what order, and which runner answers each call. */
public class ToolBoxTest
{
	private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
	private final List<String> activity = new CopyOnWriteArrayList<>();
	private final AtomicInteger started = new AtomicInteger();
	/** Never sends anything: look-ups count as turned off. */
	private final WikiClient wiki = new WikiClient(new OkHttpClient(), new Gson(), () -> false);

	@After
	public void stop()
	{
		executor.shutdownNow();
	}

	/** One tradeable item; no client thread needed. */
	private static final class OnePrice implements LookupTools.Prices
	{
		@Override
		public List<ItemPrice> search(String text)
		{
			ItemPrice whip = new ItemPrice();
			whip.setId(4151);
			whip.setName("Abyssal whip");
			whip.setPrice(1_400_000);
			whip.setWikiPrice(1_450_000);
			return "abyssal whip".contains(text.toLowerCase()) ? Collections.singletonList(whip) : Collections.emptyList();
		}

		@Override
		public long price(ItemPrice item)
		{
			return item.getWikiPrice();
		}

		@Override
		public void highAlchemy(List<Integer> ids, Consumer<Map<Integer, Integer>> done)
		{
			done.accept(Collections.emptyMap());
		}
	}

	/** A logged-in player wearing a whip. */
	private static final class Game implements GameDataTools.Game
	{
		@Override
		public boolean loggedIn()
		{
			return true;
		}

		@Override
		public List<GameData.ItemLine> equipment()
		{
			return Collections.singletonList(new GameData.ItemLine("Weapon", "Abyssal whip", false, 1, 1_450_000));
		}

		@Override
		public GameData.Items inventory()
		{
			return new GameData.Items(Collections.emptyList(), 0);
		}

		@Override
		public GameData.Bank bank()
		{
			return null;
		}

		@Override
		public GameData.SlayerTask slayerTask()
		{
			return new GameData.SlayerTask(null, 0, 0, null, 0, 0);
		}

		@Override
		public List<GameData.Diary> diaries()
		{
			return Collections.emptyList();
		}
	}

	/** The tools for a request sent with these settings, as the plugin makes them. */
	private ToolBox tools(boolean wikiLookups, boolean shareItems, boolean shareCharacter)
	{
		ToolBox.Parts parts = new ToolBox.Parts(wiki, new OnePrice(), new Game(), Runnable::run, executor, () -> true,
			() -> true);
		RequestRunner.Setup setup = new RequestRunner.Setup(null, "m", "s", shareCharacter, shareItems, wikiLookups);
		return parts.forRequest(setup, () -> true, activity::add, started::incrementAndGet);
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
	public void theToolsComeInOneOrder()
	{
		assertEquals(Arrays.asList("wiki_search", "wiki_page", "ge_price", "get_equipment", "get_inventory", "get_bank",
			"get_slayer_task", "get_achievement_diaries"), names(tools(true, true, true).specs()));
		assertEquals(Arrays.asList("wiki_search", "wiki_page", "ge_price"), names(tools(true, false, false).specs()));
		assertEquals(Arrays.asList("ge_price", "get_slayer_task", "get_achievement_diaries"),
			names(tools(false, false, true).specs()));
		// GE prices need nothing from the player and send nothing anywhere: always there.
		assertEquals(Collections.singletonList("ge_price"), names(tools(false, false, false).specs()));
	}

	@Test
	public void theSameSettingsMakeTheSameTools()
	{
		// The tools are part of the prompt the provider caches: they must be the same from one request to the next.
		ChatApi.Conversation a = StandIn.conversation("m", "q");
		ChatApi.Conversation b = StandIn.conversation("m", "q");
		tools(true, true, false).addTo(a);
		tools(true, true, false).addTo(b);
		assertEquals(ChatApi.promptKey(a), ChatApi.promptKey(b));
		ChatApi.Conversation c = StandIn.conversation("m", "q");
		tools(true, false, false).addTo(c);
		assertFalse(ChatApi.promptKey(a).equals(ChatApi.promptKey(c)));
	}

	/** Runs one call and waits for its result (game reads answer on another thread). */
	private static ChatApi.ToolResult call(ToolBox box, String name, JsonObject input) throws Exception
	{
		CompletableFuture<ChatApi.ToolResult> result = new CompletableFuture<>();
		box.run(name, input, result::complete);
		return result.get(5, TimeUnit.SECONDS);
	}

	@Test
	public void callsGoToTheirOwnRunner() throws Exception
	{
		ToolBox box = tools(true, true, false);
		ChatApi.Conversation c = StandIn.conversation("m", "q");
		box.addTo(c);
		assertSame(box, c.toolRunner);

		JsonObject price = new JsonObject();
		price.addProperty("item", "abyssal whip");
		ChatApi.ToolResult ge = call(box, "ge_price", price);
		assertFalse(ge.error);
		assertTrue(ge.content, ge.content.contains("Abyssal whip: 1,450,000 gp"));

		ChatApi.ToolResult worn = call(box, "get_equipment", new JsonObject());
		assertFalse(worn.error);
		assertTrue(worn.content, worn.content.contains("Abyssal whip"));

		ChatApi.ToolResult unknown = call(box, "get_bank_pin", new JsonObject());
		assertTrue(unknown.error);
		assertEquals("There's no tool called get_bank_pin.", unknown.content);

		assertEquals(3, started.get());
		assertEquals(Arrays.asList("Checked the GE price of Abyssal whip", "Shared your equipment"), activity);
		// As the panel sums them up: what was shared first, then the look-up.
		assertEquals("Shared your equipment · Looked up Abyssal whip (GE price)",
			PanelText.summary(activity).line(s -> true));
	}

	@Test
	public void toolsThatWerentOfferedAreTurnedDown() throws Exception
	{
		ToolBox box = tools(false, false, false);
		JsonObject query = new JsonObject();
		query.addProperty("query", "vorkath");
		ChatApi.ToolResult search = call(box, "wiki_search", query);
		assertTrue(search.error);
		assertTrue(search.content, search.content.contains("Wiki look-ups are turned off"));
		ChatApi.ToolResult inventory = call(box, "get_inventory", new JsonObject());
		assertTrue(inventory.error);
		String items = SettingName.of("shareItems");
		assertTrue(inventory.content, inventory.content.contains("\"" + items + "\""));
		assertEquals(Arrays.asList("Skipped a Wiki search: Wiki look-ups are off",
			"Didn't share your inventory: \"" + items + "\" is off"), activity);
	}
}

