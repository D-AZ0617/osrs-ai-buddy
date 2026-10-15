package com.osrsaibuddy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.http.api.item.ItemPrice;
import okhttp3.HttpUrl;

/**
 * The look-ups the model can call while answering: the OSRS Wiki (search, read a page) and RuneLite's own Grand
 * Exchange prices. Made for one reply: every call it runs ends with one line for the panel saying what was looked up
 * ({@code Searched the Wiki for "abyssal whip"}), told to {@code activity} just before the result goes back.
 */
@Slf4j
final class LookupTools implements ChatApi.ToolRunner
{
	static final String WIKI_SEARCH = "wiki_search";
	static final String WIKI_PAGE = "wiki_page";
	static final String GE_PRICE = "ge_price";
	/**
	 * How the lines for look-ups that went through start. The panel counts or names them on one line over the reply,
	 * and lists them one kind to a line when that's clicked (see {@link PanelText#summary}), by these.
	 */
	static final String SEARCHED_WIKI = "Searched the Wiki for ";
	static final String READ_WIKI_PAGE = "Read the Wiki page ";
	static final String CHECKED_GE_PRICE = "Checked the GE price of ";

	static final int SEARCH_RESULTS = 6;
	/** Wikitext per wiki_page, about 3,000 tokens: most pages fit, and reading a few stays cheap. */
	static final int PAGE_CHARS = 12_000;
	/** A page longer than this lists its sections even when it fits, so the model can ask for one next time. */
	static final int LONG_PAGE_CHARS = 6_000;
	static final int PRICE_RESULTS = 5;
	private static final int SNIPPET_CHARS = 200;
	private static final int MAX_SECTIONS_LISTED = 80;
	/** The Wiki takes at most 300 characters of search, and titles are at most 255. */
	private static final int MAX_QUERY = 200;
	private static final int MAX_TITLE = 255;
	private static final long NOT_A_NUMBER = Long.MIN_VALUE;
	private static final String TRY_AGAIN = " Try again, or answer without the Wiki and say so.";
	private static final String FAILED = "That look-up failed inside OSRS AI Buddy. Answer without it.";
	/** A wikitext heading on its own line: "== Drops ==". */
	private static final Pattern HEADING_LINE = Pattern.compile("={2,6}[^\n]*={2,6}\\s*");

	/** Where ge_price gets its prices: RuneLite's own price data in the game, canned ones in tests. */
	interface Prices
	{
		/**
		 * Tradeable items whose names contain {@code text}, ignoring case; empty when nothing matches or the prices
		 * haven't loaded yet. Any thread.
		 */
		List<ItemPrice> search(String text);

		/** The price RuneLite shows for the item, following its "Use actively traded price" setting. Any thread. */
		long price(ItemPrice item);

		/** Reports the items' high alchemy values by id, once, on any thread; values it can't read are left out. */
		void highAlchemy(List<Integer> ids, Consumer<Map<Integer, Integer>> done);
	}

	/** Null when OSRS Wiki searches are off: the Wiki tools aren't offered, and calls to them are turned down. */
	private final WikiClient wiki;
	private final Prices prices;
	private final Consumer<String> activity;
	/** False once the reply these look-ups are for has stopped: Wiki requests still waiting their turn aren't sent. */
	private final BooleanSupplier wanted;

	/** Look-ups that are always wanted. */
	LookupTools(WikiClient wiki, Prices prices, Consumer<String> activity)
	{
		this(wiki, prices, activity, () -> true);
	}

	LookupTools(WikiClient wiki, Prices prices, Consumer<String> activity, BooleanSupplier wanted)
	{
		this.wiki = wiki;
		this.prices = prices;
		this.activity = activity;
		this.wanted = wanted;
	}

	static boolean handles(String name)
	{
		return WIKI_SEARCH.equals(name) || WIKI_PAGE.equals(name) || GE_PRICE.equals(name);
	}

	/**
	 * The tools to offer, always in the same order and with the same text: they're part of the prompt the provider
	 * caches.
	 */
	List<ChatApi.ToolSpec> specs()
	{
		List<ChatApi.ToolSpec> specs = new ArrayList<>();
		if (wiki != null)
		{
			JsonObject search = new JsonObject();
			search.add("query", property("string",
				"What to look for, e.g. \"abyssal whip\" or \"barrows gloves requirements\"."));
			specs.add(new ChatApi.ToolSpec(WIKI_SEARCH,
				"Search the Old School RuneScape Wiki. Returns up to " + SEARCH_RESULTS + " page titles with links and a "
					+ "snippet each; read one with " + WIKI_PAGE + ".",
				schema(search, "query")));

			JsonObject page = new JsonObject();
			page.add("title", property("string", "The exact page title, e.g. \"Abyssal whip\". Redirects are followed."));
			JsonObject section = property("integer", "Optional: one section's number from the page's section list; 0 is "
				+ "the part before the first heading.");
			section.addProperty("minimum", 0);
			page.add("section", section);
			specs.add(new ChatApi.ToolSpec(WIKI_PAGE,
				"Read an OSRS Wiki page's wikitext: infobox stats, requirements, drops, strategy. Long pages are cut and "
					+ "list their sections; then ask for one section. Link the page you used in your answer.",
				schema(page, "title")));
		}

		JsonObject price = new JsonObject();
		price.add("item", property("string", "An item name or part of one, e.g. \"dragon bones\"."));
		JsonObject quantity = property("integer", "Optional: how many, for the total.");
		quantity.addProperty("minimum", 1);
		price.add("quantity", quantity);
		specs.add(new ChatApi.ToolSpec(GE_PRICE,
			"Current Grand Exchange prices from RuneLite's own price data: up to " + PRICE_RESULTS + " tradeable items "
				+ "whose names contain the text, with their high alchemy values. Untradeable items have no price.",
			schema(price, "item")));
		return specs;
	}

	private static JsonObject property(String type, String description)
	{
		JsonObject p = new JsonObject();
		p.addProperty("type", type);
		p.addProperty("description", description);
		return p;
	}

	private static JsonObject schema(JsonObject properties, String required)
	{
		JsonObject s = new JsonObject();
		s.addProperty("type", "object");
		s.add("properties", properties);
		JsonArray r = new JsonArray();
		r.add(required);
		s.add("required", r);
		return s;
	}

	@Override
	public void run(String name, JsonObject input, Consumer<ChatApi.ToolResult> done)
	{
		Report report = new Report(done);
		try
		{
			if (WIKI_SEARCH.equals(name))
			{
				wikiSearch(input, report);
			}
			else if (WIKI_PAGE.equals(name))
			{
				wikiPage(input, report);
			}
			else if (GE_PRICE.equals(name))
			{
				gePrice(input, report);
			}
			else
			{
				report.send("Skipped an unknown look-up " + quote(name),
					ChatApi.ToolResult.error("There's no tool called " + name + "."));
			}
		}
		catch (RuntimeException e)
		{
			// A bug, not the player's or the model's doing; the reply can still carry on without this look-up.
			log.warn("look-up {} failed", name, e);
			report.send("Couldn't finish a look-up (" + name + ")", ChatApi.ToolResult.error(FAILED));
		}
	}

	/**
	 * One call's ending: its activity line, then its result, together and only once. No line for a look-up that wasn't
	 * made because its reply had stopped: there's nothing to list.
	 */
	private final class Report
	{
		private final AtomicBoolean sent = new AtomicBoolean();
		private final Consumer<ChatApi.ToolResult> done;

		Report(Consumer<ChatApi.ToolResult> done)
		{
			this.done = done;
		}

		/**
		 * For a result written later, on another thread: a bug while writing it still ends the call, instead of leaving
		 * the reply waiting for it.
		 */
		void write(String line, Supplier<ChatApi.ToolResult> result)
		{
			ChatApi.ToolResult r;
			try
			{
				r = result.get();
			}
			catch (RuntimeException e)
			{
				log.warn("look-up result not written", e);
				r = ChatApi.ToolResult.error(FAILED);
			}
			send(line, r);
		}

		void send(String line, ChatApi.ToolResult result)
		{
			if (!sent.compareAndSet(false, true))
			{
				return;
			}
			try
			{
				if (line != null)
				{
					activity.accept(line);
				}
			}
			catch (RuntimeException e)
			{
				log.warn("look-up activity not shown", e);
			}
			done.accept(result);
		}
	}

	private void wikiSearch(JsonObject input, Report report)
	{
		if (wiki == null)
		{
			report.send("Skipped a Wiki search: OSRS Wiki searches are off", wikiOff());
			return;
		}
		String query = text(input, "query", MAX_QUERY);
		if (query == null)
		{
			report.send("Skipped a Wiki search: nothing to search for",
				ChatApi.ToolResult.error(WIKI_SEARCH + " needs a query, e.g. {\"query\": \"abyssal whip\"}."));
			return;
		}
		wiki.search(query, SEARCH_RESULTS, wanted, new WikiClient.Listener<WikiClient.Search>()
		{
			@Override
			public void onResult(WikiClient.Search search)
			{
				report.write(SEARCHED_WIKI + quote(query), () -> ChatApi.ToolResult.ok(searchText(query, search)));
			}

			@Override
			public void onError(String code, String message)
			{
				report.send(WikiClient.STOPPED.equals(code) ? null : "Couldn't search the Wiki for " + quote(query),
					WikiClient.TURNED_OFF.equals(code) ? wikiOff() : ChatApi.ToolResult.error(message + TRY_AGAIN));
			}
		});
	}

	private void wikiPage(JsonObject input, Report report)
	{
		if (wiki == null)
		{
			report.send("Skipped a Wiki page: OSRS Wiki searches are off", wikiOff());
			return;
		}
		String title = title(text(input, "title", MAX_TITLE + 100));
		long section = whole(input, "section", -1);
		if (title == null)
		{
			report.send("Skipped a Wiki page: no title given",
				ChatApi.ToolResult.error(WIKI_PAGE + " needs a title, e.g. {\"title\": \"Abyssal whip\"}."));
			return;
		}
		if (section < -1 || section > 9999)
		{
			report.send("Skipped the Wiki page " + quote(title) + ": no such section",
				ChatApi.ToolResult.error("section must be a section number from the page's section list, like 2. Leave it "
					+ "out to read the page from the start."));
			return;
		}
		int part = (int) section;
		wiki.page(title, part, wanted, new WikiClient.Listener<WikiClient.Page>()
		{
			@Override
			public void onResult(WikiClient.Page page)
			{
				report.write(READ_WIKI_PAGE + quote(page.title) + sectionName(page, part),
					() -> ChatApi.ToolResult.ok(pageText(page, part)));
			}

			@Override
			public void onError(String code, String message)
			{
				if (WikiClient.STOPPED.equals(code))
				{
					report.send(null, ChatApi.ToolResult.error(message));
				}
				else if ("missingtitle".equals(code) || "invalidtitle".equals(code))
				{
					report.send("Found no Wiki page called " + quote(title), ChatApi.ToolResult.error(
						"The Wiki has no page called " + quote(title) + ". Try " + WIKI_SEARCH + " first."));
				}
				else if ("nosuchsection".equals(code))
				{
					report.send("Found no section " + part + " on the Wiki page " + quote(title), ChatApi.ToolResult.error(
						"The page " + quote(title) + " has no section " + part + ". Read it without a section to see its "
							+ "list of sections."));
				}
				else
				{
					report.send("Couldn't read the Wiki page " + quote(title), WikiClient.TURNED_OFF.equals(code)
						? wikiOff() : ChatApi.ToolResult.error(message + TRY_AGAIN));
				}
			}
		});
	}

	private static ChatApi.ToolResult wikiOff()
	{
		return ChatApi.ToolResult.error("OSRS Wiki searches are turned off in the OSRS AI Buddy settings. Answer without the Wiki.");
	}

	/**
	 * The page title the model meant: it sometimes gives a link to the page, or a title with "#Section" on the end;
	 * MediaWiki wants just the title.
	 */
	static String title(String given)
	{
		if (given == null)
		{
			return null;
		}
		String title = given;
		HttpUrl url = HttpUrl.parse(given);
		if (url != null && url.host().equals(WikiClient.BASE.host()) && url.pathSize() > 1
			&& "w".equals(url.pathSegments().get(0)))
		{
			title = String.join("/", url.pathSegments().subList(1, url.pathSize())).replace('_', ' ');
		}
		int hash = title.indexOf('#');
		if (hash > 0)
		{
			title = title.substring(0, hash);
		}
		title = title.trim();
		if (title.isEmpty())
		{
			return null;
		}
		return title.length() <= MAX_TITLE ? title : title.substring(0, MAX_TITLE);
	}

	/** For the activity line: " (Drops)" after the page's title when one section was read. */
	private static String sectionName(WikiClient.Page page, int section)
	{
		if (section < 0)
		{
			return "";
		}
		if (section == 0)
		{
			return " (introduction)";
		}
		String heading = WikiClient.heading(page.wikitext);
		return " (" + (heading == null ? "section " + section : ChatApi.shorten(WikiClient.plainText(heading), 40)) + ")";
	}

	static String searchText(String query, WikiClient.Search search)
	{
		if (search.results.isEmpty())
		{
			return "The Wiki found no pages for " + quote(query) + "."
				+ (search.suggestion == null ? " Try other words." : " Did you mean " + quote(search.suggestion) + "?");
		}
		StringBuilder out = new StringBuilder("Wiki pages for ").append(quote(query)).append(':');
		int n = 0;
		for (WikiClient.SearchResult r : search.results)
		{
			if (n == SEARCH_RESULTS)
			{
				break;
			}
			out.append('\n').append(++n).append(". ").append(r.title).append(" - ").append(r.url);
			if (!r.snippet.isEmpty())
			{
				out.append("\n   ").append(ChatApi.shorten(r.snippet, SNIPPET_CHARS));
			}
		}
		return out.toString();
	}

	/**
	 * The page for the model: its link first (to cite), then the cleaned wikitext, cut to {@link #PAGE_CHARS}. A cut
	 * or long page ends with its sections, so the model can read just the part it needs.
	 */
	static String pageText(WikiClient.Page page, int section)
	{
		StringBuilder out = new StringBuilder(page.url).append('\n');
		out.append("Wiki page ").append(quote(page.title));
		if (page.redirectedFrom != null)
		{
			out.append(" (redirected from ").append(quote(page.redirectedFrom)).append(')');
		}
		if (section >= 0)
		{
			out.append(", section ").append(section).append(" only");
		}
		out.append(":\n\n");
		String text = WikiClient.clean(page.wikitext);
		boolean cut = text.length() > PAGE_CHARS;
		if (cut)
		{
			text = cut(text, PAGE_CHARS);
		}
		out.append(text.isEmpty() ? "(This has no text.)" : text);
		boolean listed = section < 0 && !page.sections.isEmpty();
		if (cut && listed)
		{
			out.append("\n\n[Cut here: the page goes on. Ask for one section with `section`; the page's sections are:\n")
				.append(sectionList(page.sections)).append(']');
		}
		else if (cut)
		{
			out.append("\n\n[Cut here: the rest is on the page itself.]");
		}
		else if (listed && text.length() > LONG_PAGE_CHARS)
		{
			out.append("\n\n[A long page: next time, ask for one section with `section`. Its sections are:\n")
				.append(sectionList(page.sections)).append(']');
		}
		return out.toString();
	}

	private static String sectionList(List<WikiClient.Section> sections)
	{
		StringBuilder out = new StringBuilder("0 (introduction)");
		int listed = 0;
		for (WikiClient.Section s : sections)
		{
			if (listed == MAX_SECTIONS_LISTED)
			{
				out.append("\n...and ").append(sections.size() - listed).append(" more");
				break;
			}
			out.append('\n');
			for (int level = 2; level < s.level; level++)
			{
				out.append("  ");
			}
			out.append(s.index).append(' ').append(s.heading);
			listed++;
		}
		return out.toString();
	}

	/** At most {@code max} characters, ending at a line break (or a space) where there's one in the second half. */
	static String cut(String text, int max)
	{
		if (text.length() <= max)
		{
			return text;
		}
		int end = text.lastIndexOf('\n', max);
		if (end < max / 2)
		{
			end = text.lastIndexOf(' ', max);
		}
		if (end < max / 2)
		{
			end = Character.isHighSurrogate(text.charAt(max - 1)) ? max - 1 : max;
		}
		String kept = text.substring(0, end).trim();
		// A heading with nothing under it would read as an empty section.
		int lastLine = kept.lastIndexOf('\n');
		if (lastLine > 0 && HEADING_LINE.matcher(kept.substring(lastLine + 1)).matches())
		{
			kept = kept.substring(0, lastLine).trim();
		}
		return kept;
	}

	private void gePrice(JsonObject input, Report report)
	{
		String item = text(input, "item", MAX_QUERY);
		long quantity = whole(input, "quantity", 1);
		if (item == null)
		{
			report.send("Skipped a GE price check: no item given",
				ChatApi.ToolResult.error(GE_PRICE + " needs an item name, e.g. {\"item\": \"dragon bones\"}."));
			return;
		}
		if (quantity < 1 || quantity > Integer.MAX_VALUE)
		{
			report.send("Skipped the GE price of " + quote(item) + ": odd quantity",
				ChatApi.ToolResult.error("quantity must be a whole number from 1 up, or left out."));
			return;
		}
		List<ItemPrice> found = matches(item);
		if (found.isEmpty())
		{
			report.send("Found no GE price for " + quote(item), ChatApi.ToolResult.error(
				"RuneLite has no Grand Exchange price for anything called " + quote(item) + ". It may not be tradeable, or "
					+ "go by another name" + (wiki == null ? "" : " (" + WIKI_SEARCH + " can tell)")
					+ ", or RuneLite hasn't loaded its prices yet."));
			return;
		}
		List<ItemPrice> best = best(found, item, PRICE_RESULTS);
		List<Integer> ids = new ArrayList<>();
		List<Long> each = new ArrayList<>();
		for (ItemPrice p : best)
		{
			ids.add(p.getId());
			each.add(prices.price(p));
		}
		String line = CHECKED_GE_PRICE + ChatApi.shorten(best.get(0).getName(), 60);
		prices.highAlchemy(ids, alch -> report.write(line, () -> ChatApi.ToolResult.ok(
			priceText(item, best, each, found.size(), alch == null ? Collections.emptyMap() : alch, quantity))));
	}

	/** RuneLite's matches for the name; "cannonballs" finds Cannonball. */
	private List<ItemPrice> matches(String item)
	{
		List<ItemPrice> found = prices.search(item);
		if ((found == null || found.isEmpty()) && item.length() > 3 && item.toLowerCase(Locale.ROOT).endsWith("s"))
		{
			found = prices.search(item.substring(0, item.length() - 1));
		}
		List<ItemPrice> named = new ArrayList<>();
		if (found != null)
		{
			for (ItemPrice p : found)
			{
				if (p != null && p.getName() != null)
				{
					named.add(p);
				}
			}
		}
		return named;
	}

	/**
	 * The likeliest items first: the exact name, then names starting with the text, then a word starting with it, then
	 * the shortest (RuneLite's own !price command also prefers the exact or the shortest name).
	 */
	static List<ItemPrice> best(List<ItemPrice> found, String query, int max)
	{
		String q = query.toLowerCase(Locale.ROOT);
		List<ItemPrice> sorted = new ArrayList<>(found);
		sorted.sort(Comparator.comparingInt((ItemPrice p) -> rank(p.getName().toLowerCase(Locale.ROOT), q))
			.thenComparingInt(p -> p.getName().length())
			.thenComparing(ItemPrice::getName));
		return new ArrayList<>(sorted.subList(0, Math.min(max, sorted.size())));
	}

	private static int rank(String name, String query)
	{
		if (name.equals(query))
		{
			return 0;
		}
		if (name.startsWith(query))
		{
			return 1;
		}
		return name.contains(" " + query) || name.contains("(" + query) ? 2 : 3;
	}

	/**
	 * One line per item: "Abyssal whip: 1,523,410 gp (GE guide price 1,498,000 gp); high alchemy 72,000 gp". The guide
	 * price is added when it isn't the price RuneLite shows.
	 */
	static String priceText(String query, List<ItemPrice> items, List<Long> each, int matched, Map<Integer, Integer> alch,
		long quantity)
	{
		StringBuilder out = new StringBuilder("Grand Exchange prices from RuneLite (its price list updates every 30 minutes):");
		for (int i = 0; i < items.size(); i++)
		{
			ItemPrice item = items.get(i);
			long price = each.get(i);
			out.append("\n- ").append(item.getName()).append(": ");
			if (price <= 0)
			{
				out.append("no recent price");
			}
			else if (quantity > 1)
			{
				out.append(gp(price)).append(" each, ").append(gp(price * quantity)).append(" for ").append(number(quantity));
			}
			else
			{
				out.append(gp(price));
			}
			if (item.getPrice() > 0 && item.getPrice() != price)
			{
				out.append(" (GE guide price ").append(gp(item.getPrice())).append(quantity > 1 ? " each)" : ")");
			}
			Integer ha = alch.get(item.getId());
			if (ha != null && ha > 0)
			{
				out.append("; high alchemy ").append(gp(ha)).append(quantity > 1 ? " each" : "");
			}
		}
		if (matched > items.size())
		{
			int more = matched - items.size();
			out.append("\n...and ").append(more).append(more == 1 ? " more item matches " : " more items match ")
				.append(quote(query)).append("; ask with a fuller name to see ").append(more == 1 ? "it." : "them.");
		}
		return out.toString();
	}

	private static String gp(long amount)
	{
		return number(amount) + " gp";
	}

	private static String number(long n)
	{
		return String.format(Locale.ROOT, "%,d", n);
	}

	/** A text value the model gave, on one line and capped; null when it's missing or blank. */
	private static String text(JsonObject input, String key, int max)
	{
		JsonElement e = input == null ? null : input.get(key);
		if (e == null || !e.isJsonPrimitive())
		{
			return null;
		}
		String s = e.getAsString().replaceAll("[\\s\\p{Cc}]+", " ").trim();
		if (s.isEmpty())
		{
			return null;
		}
		return s.length() <= max ? s : s.substring(0, max).trim();
	}

	/**
	 * A whole number the model gave (2, 2.0 or "2"), {@code absent} when it gave none (or a blank), and
	 * {@link #NOT_A_NUMBER} when it isn't one.
	 */
	private static long whole(JsonObject input, String key, long absent)
	{
		JsonElement e = input == null ? null : input.get(key);
		if (e == null || e.isJsonNull() || (e.isJsonPrimitive() && e.getAsString().trim().isEmpty()))
		{
			return absent;
		}
		if (!e.isJsonPrimitive())
		{
			return NOT_A_NUMBER;
		}
		try
		{
			return e.getAsBigDecimal().longValueExact();
		}
		catch (NumberFormatException | ArithmeticException ex)
		{
			return NOT_A_NUMBER;
		}
	}

	private static String quote(String s)
	{
		return "\"" + ChatApi.shorten(s, 60) + "\"";
	}

	/**
	 * RuneLite's own prices, which it downloads itself every 30 minutes: a price check sends nothing anywhere. High
	 * alchemy values come from the game's item data, read on the client thread.
	 */
	static final class RuneLitePrices implements Prices
	{
		/** The client thread runs queued work every frame, also on the login screen; this is in case it can't. */
		private static final long ALCH_TIMEOUT_MILLIS = 2000;

		private final ItemManager itemManager;
		private final ClientThread clientThread;
		private final ScheduledExecutorService executor;
		private final BooleanSupplier activelyTraded;
		private final long alchTimeoutMillis;

		/** {@code activelyTraded}: RuneLite's "Use actively traded price" setting. */
		RuneLitePrices(ItemManager itemManager, ClientThread clientThread, ScheduledExecutorService executor,
			BooleanSupplier activelyTraded)
		{
			this(itemManager, clientThread, executor, activelyTraded, ALCH_TIMEOUT_MILLIS);
		}

		RuneLitePrices(ItemManager itemManager, ClientThread clientThread, ScheduledExecutorService executor,
			BooleanSupplier activelyTraded, long alchTimeoutMillis)
		{
			this.itemManager = itemManager;
			this.clientThread = clientThread;
			this.executor = executor;
			this.activelyTraded = activelyTraded;
			this.alchTimeoutMillis = alchTimeoutMillis;
		}

		@Override
		public List<ItemPrice> search(String text)
		{
			// Only reads the price list RuneLite keeps in memory: fine off the client thread.
			return itemManager.search(text);
		}

		@Override
		public long price(ItemPrice item)
		{
			// What ItemManager.getItemPrice gives, without its item lookup, which needs the client thread: search finds
			// tradeable items under their own ids, so there's no noted or worn version to map back.
			return activelyTraded.getAsBoolean() ? itemManager.getWikiPrice(item) : item.getPrice();
		}

		@Override
		public void highAlchemy(List<Integer> ids, Consumer<Map<Integer, Integer>> done)
		{
			AtomicBoolean reported = new AtomicBoolean();
			Future<?> timeout;
			try
			{
				timeout = executor.schedule(() ->
				{
					if (reported.compareAndSet(false, true))
					{
						done.accept(Collections.emptyMap());
					}
				}, alchTimeoutMillis, TimeUnit.MILLISECONDS);
			}
			catch (RuntimeException e)
			{
				// RuneLite is closing: prices without alchemy values.
				done.accept(Collections.emptyMap());
				return;
			}
			// A Runnable, so it runs once: a lambda that returns a boolean would be taken as the kind that runs again
			// every frame until it returns true.
			Runnable read = () ->
			{
				Map<Integer, Integer> values = new HashMap<>();
				for (int id : ids)
				{
					try
					{
						values.put(id, itemManager.getItemComposition(id).getHaPrice());
					}
					catch (RuntimeException e)
					{
						// The game's item data isn't loaded yet: leave this one out.
						log.debug("no high alchemy value for {}", id, e);
					}
				}
				timeout.cancel(false);
				if (reported.compareAndSet(false, true))
				{
					handBack(done, values);
				}
			};
			try
			{
				clientThread.invoke(read);
			}
			catch (RuntimeException e)
			{
				log.debug("high alchemy values not read", e);
				timeout.cancel(false);
				if (reported.compareAndSet(false, true))
				{
					done.accept(Collections.emptyMap());
				}
			}
		}

		/** Off the client thread before the reply carries on (formatting, then the next request). */
		private void handBack(Consumer<Map<Integer, Integer>> done, Map<Integer, Integer> values)
		{
			try
			{
				executor.execute(() -> done.accept(values));
			}
			catch (RuntimeException e)
			{
				// RuneLite is closing; the reply won't get far, but it still hears back.
				done.accept(values);
			}
		}
	}
}
