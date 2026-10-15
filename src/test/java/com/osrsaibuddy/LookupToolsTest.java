package com.osrsaibuddy;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.runelite.client.callback.ClientThread;
import net.runelite.http.api.item.ItemPrice;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The Wiki and GE price look-ups, against a stand-in Wiki on 127.0.0.1 and canned prices: what they ask the Wiki, and
 * what the model and the panel get back.
 */
public class LookupToolsTest
{
	private final Gson gson = new Gson();
	private final OkHttpClient http = new OkHttpClient();
	private HttpServer server;
	private ExecutorService serverThreads;
	/** Canned answers {code, body}, served in order; the last one repeats. Code "hold" answers 200 once released. */
	private final List<String[]> answers = Collections.synchronizedList(new ArrayList<>());
	private final List<HttpUrl> requests = new CopyOnWriteArrayList<>();
	private final List<String> userAgents = new CopyOnWriteArrayList<>();
	private final CountDownLatch release = new CountDownLatch(1);
	private final AtomicInteger inFlight = new AtomicInteger();
	private final AtomicInteger mostInFlight = new AtomicInteger();
	private volatile CountDownLatch arrivals = new CountDownLatch(0);
	private volatile boolean allowed = true;
	private final List<String> activity = new CopyOnWriteArrayList<>();
	private final List<ChatApi.ToolResult> results = new CopyOnWriteArrayList<>();

	@Before
	public void start() throws IOException
	{
		serverThreads = Executors.newCachedThreadPool();
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.setExecutor(serverThreads);
		server.createContext("/", exchange ->
		{
			mostInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
			requests.add(HttpUrl.get("http://127.0.0.1" + exchange.getRequestURI()));
			userAgents.add(exchange.getRequestHeaders().getFirst("User-Agent"));
			arrivals.countDown();
			String[] answer;
			synchronized (answers)
			{
				answer = answers.size() > 1 ? answers.remove(0) : answers.get(0);
			}
			int code;
			if ("hold".equals(answer[0]))
			{
				StandIn.await(release);
				code = 200;
			}
			else
			{
				code = Integer.parseInt(answer[0]);
			}
			byte[] out = answer[1].getBytes(StandardCharsets.UTF_8);
			// Before answering: the client can't send its next request until it has this answer.
			inFlight.decrementAndGet();
			try
			{
				exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
				exchange.sendResponseHeaders(code, out.length);
				try (OutputStream os = exchange.getResponseBody())
				{
					os.write(out);
				}
			}
			catch (IOException e)
			{
				// The client gave up waiting.
			}
		});
		server.start();
	}

	@After
	public void stop()
	{
		release.countDown();
		server.stop(0);
		serverThreads.shutdownNow();
	}

	private void answer(String... codeAndBodyPairs)
	{
		synchronized (answers)
		{
			answers.clear();
			for (int i = 0; i < codeAndBodyPairs.length; i += 2)
			{
				answers.add(new String[]{codeAndBodyPairs[i], codeAndBodyPairs[i + 1]});
			}
		}
	}

	private WikiClient wiki(long timeoutMillis)
	{
		return new WikiClient(http, gson, () -> allowed,
			HttpUrl.get("http://127.0.0.1:" + server.getAddress().getPort() + "/api.php"), timeoutMillis);
	}

	private LookupTools tools()
	{
		return new LookupTools(wiki(5000), new FakePrices(), activity::add);
	}

	/** Starts one call; its result lands in {@link #results}. */
	private CountDownLatch start(LookupTools tools, String name, String input)
	{
		CountDownLatch done = new CountDownLatch(1);
		tools.run(name, input == null ? null : gson.fromJson(input, JsonObject.class), r ->
		{
			results.add(r);
			done.countDown();
		});
		return done;
	}

	/** Runs one call and waits for its result. */
	private ChatApi.ToolResult run(LookupTools tools, String name, String input) throws InterruptedException
	{
		int before = results.size();
		assertTrue("no answer", start(tools, name, input).await(10, TimeUnit.SECONDS));
		assertEquals("exactly one result", before + 1, results.size());
		return results.get(before);
	}

	private String lastActivity()
	{
		return activity.get(activity.size() - 1);
	}

	private static final String SEARCH = "{\"batchcomplete\":true,\"continue\":{\"sroffset\":3,\"continue\":\"-||\"},"
		+ "\"query\":{\"searchinfo\":{\"totalhits\":40},\"search\":["
		+ "{\"ns\":0,\"title\":\"Abyssal whip\",\"pageid\":1001,\"snippet\":\"The <span class=\\\"searchmatch\\\">abyssal</span> "
		+ "<span class=\\\"searchmatch\\\">whip</span> is a one-handed\\n melee weapon that needs 70 &lt;Attack&gt;. It&#039;s "
		+ "dropped by &quot;abyssal demons&quot; &amp; more.\"},"
		+ "{\"ns\":0,\"title\":\"Abyssal whip (or)\",\"pageid\":1002,\"snippet\":\"An ornamented version\"},"
		+ "{\"ns\":0,\"title\":\"Frozen abyssal whip\",\"pageid\":1003,\"snippet\":\"\"}]}}";

	private static final String PAGE = "{\"parse\":{\"title\":\"Abyssal whip\",\"pageid\":1001,"
		+ "\"wikitext\":\"{{Infobox Item\\n|name = Abyssal whip\\n|members = Yes\\n}}<!-- editors: keep this short -->\\n"
		+ "[[File:Abyssal whip detail.png|left|120px|An [[abyssal demon]] drop]]\\n"
		+ "The '''abyssal whip''' is a one-handed melee weapon.<ref name=\\\"drop\\\">Jagex. Mod Ash's Twitter account.</ref> "
		+ "It requires 70 [[Attack]].<ref name=\\\"drop\\\" />\\n\\n\\n\\n"
		+ "==Combat stats==\\n{{Infobox Bonuses\\n|astab = 0\\n}}\\n<gallery>\\nAbyssal whip chathead.png\\n</gallery>\\n"
		+ "==Drops==\\nDropped by abyssal demons.   \\n__NOTOC__\\n{{Reflist}}\\n[[Category:Weapons]]\","
		+ "\"sections\":[{\"toclevel\":1,\"level\":\"2\",\"line\":\"Combat stats\",\"number\":\"1\",\"index\":\"1\","
		+ "\"fromtitle\":\"Abyssal_whip\",\"byteoffset\":300,\"anchor\":\"Combat_stats\",\"linkAnchor\":\"Combat_stats\"},"
		+ "{\"toclevel\":1,\"level\":\"2\",\"line\":\"Drops\",\"number\":\"2\",\"index\":\"2\",\"fromtitle\":\"Abyssal_whip\","
		+ "\"byteoffset\":400,\"anchor\":\"Drops\",\"linkAnchor\":\"Drops\"}]}}";

	private static final String MISSING = "{\"error\":{\"code\":\"missingtitle\",\"info\":\"The page you specified doesn't exist.\","
		+ "\"docref\":\"See https://oldschool.runescape.wiki/api.php for API usage.\"},\"servedby\":\"mw1\"}";

	@Test
	public void wikiSearchListsPagesWithLinksAndPlainSnippets() throws Exception
	{
		answer("200", SEARCH);
		ChatApi.ToolResult r = run(tools(), "wiki_search", "{\"query\":\"  abyssal\\n whip \"}");
		assertFalse(r.content, r.error);
		assertTrue(r.content, r.content.startsWith("Wiki pages for \"abyssal whip\":\n1. Abyssal whip - https://oldschool.runescape.wiki/w/Abyssal_whip\n"));
		// Tags gone, entities decoded once (an escaped "<Attack>" is text, not a tag), all on one line.
		assertTrue(r.content, r.content.contains("   The abyssal whip is a one-handed melee weapon that needs 70 <Attack>. "
			+ "It's dropped by \"abyssal demons\" & more.\n"));
		assertTrue(r.content, r.content.contains("2. Abyssal whip (or) - https://oldschool.runescape.wiki/w/Abyssal_whip_(or)\n   An ornamented version\n"));
		assertTrue(r.content, r.content.endsWith("3. Frozen abyssal whip - https://oldschool.runescape.wiki/w/Frozen_abyssal_whip"));
		assertFalse(r.content, r.content.contains("searchmatch"));
		assertEquals("Searched the Wiki for \"abyssal whip\"", lastActivity());

		HttpUrl sent = requests.get(0);
		assertEquals("/api.php", sent.encodedPath());
		assertEquals("query", sent.queryParameter("action"));
		assertEquals("search", sent.queryParameter("list"));
		assertEquals("abyssal whip", sent.queryParameter("srsearch"));
		assertEquals("6", sent.queryParameter("srlimit"));
		assertEquals("0", sent.queryParameter("srnamespace"));
		assertEquals("json", sent.queryParameter("format"));
		assertEquals("2", sent.queryParameter("formatversion"));
		assertEquals("5", sent.queryParameter("maxlag"));
		assertEquals(WikiClient.USER_AGENT, userAgents.get(0));
	}

	@Test
	public void wikiSearchWithNoResultsPassesOnTheSuggestion() throws Exception
	{
		answer("200", "{\"batchcomplete\":true,\"query\":{\"searchinfo\":{\"totalhits\":0,\"suggestion\":\"abyssal whip\"},\"search\":[]}}");
		ChatApi.ToolResult r = run(tools(), "wiki_search", "{\"query\":\"abysal wip\"}");
		assertFalse(r.error);
		assertEquals("The Wiki found no pages for \"abysal wip\". Did you mean \"abyssal whip\"?", r.content);

		answer("200", "{\"batchcomplete\":true,\"query\":{\"searchinfo\":{\"totalhits\":0},\"search\":[]}}");
		assertEquals("The Wiki found no pages for \"zzz\". Try other words.", run(tools(), "wiki_search", "{\"query\":\"zzz\"}").content);
	}

	@Test
	public void wikiPageIsCleanedAndLinked() throws Exception
	{
		answer("200", PAGE);
		ChatApi.ToolResult r = run(tools(), "wiki_page", "{\"title\":\"Abyssal whip\"}");
		assertFalse(r.content, r.error);
		assertTrue(r.content, r.content.startsWith("https://oldschool.runescape.wiki/w/Abyssal_whip\nWiki page \"Abyssal whip\":\n\n"
			+ "{{Infobox Item\n|name = Abyssal whip\n|members = Yes\n}}\n\n"
			+ "The '''abyssal whip''' is a one-handed melee weapon. It requires 70 [[Attack]].\n\n==Combat stats==\n"));
		assertTrue(r.content, r.content.endsWith("==Drops==\nDropped by abyssal demons."));
		for (String gone : new String[]{"<!--", "editors", "<ref", "Mod Ash", "File:", "abyssal demon]] drop", "gallery", "chathead",
			"__NOTOC__", "Reflist", "Category:", "\n\n\n", "   \n"})
		{
			assertFalse(gone, r.content.contains(gone));
		}
		// Short: no list of sections.
		assertFalse(r.content, r.content.contains("Its sections"));
		assertEquals("Read the Wiki page \"Abyssal whip\"", lastActivity());

		HttpUrl sent = requests.get(0);
		assertEquals("parse", sent.queryParameter("action"));
		assertEquals("Abyssal whip", sent.queryParameter("page"));
		assertEquals("wikitext|sections", sent.queryParameter("prop"));
		assertEquals("1", sent.queryParameter("redirects"));
		assertNull(sent.queryParameter("section"));
		assertEquals("2", sent.queryParameter("formatversion"));
		assertEquals("5", sent.queryParameter("maxlag"));
	}

	@Test
	public void redirectsAreFollowedAndTheRealPageIsLinked() throws Exception
	{
		answer("200", "{\"parse\":{\"title\":\"Abyssal whip\",\"pageid\":1001,\"redirects\":[{\"from\":\"Whip\",\"to\":\"Abyssal whip\"}],"
			+ "\"wikitext\":\"The whip.\",\"sections\":[]}}");
		ChatApi.ToolResult r = run(tools(), "wiki_page", "{\"title\":\"Whip\"}");
		assertEquals("https://oldschool.runescape.wiki/w/Abyssal_whip\nWiki page \"Abyssal whip\" (redirected from \"Whip\"):\n\nThe whip.", r.content);
		assertEquals("Whip", requests.get(0).queryParameter("page"));
		assertEquals("Read the Wiki page \"Abyssal whip\"", lastActivity());

		// A redirect to one part of a page links to that part.
		answer("200", "{\"parse\":{\"title\":\"Abyssal demon\",\"redirects\":[{\"from\":\"Abyssal demon drops\",\"to\":\"Abyssal demon\","
			+ "\"tofragment\":\"Drops\"}],\"wikitext\":\"Demons.\",\"sections\":[]}}");
		assertTrue(run(tools(), "wiki_page", "{\"title\":\"Abyssal demon drops\"}").content
			.startsWith("https://oldschool.runescape.wiki/w/Abyssal_demon#Drops\n"));
	}

	@Test
	public void aMissingPageSaysToSearchFirst() throws Exception
	{
		answer("200", MISSING);
		ChatApi.ToolResult r = run(tools(), "wiki_page", "{\"title\":\"Abyssal wip\"}");
		assertTrue(r.error);
		assertEquals("The Wiki has no page called \"Abyssal wip\". Try wiki_search first.", r.content);
		assertEquals("Found no Wiki page called \"Abyssal wip\"", lastActivity());
	}

	@Test
	public void oneSectionCanBeRead() throws Exception
	{
		answer("200", "{\"parse\":{\"title\":\"Abyssal whip\",\"pageid\":1001,\"wikitext\":\"==Drops==\\nDropped by [[abyssal demon]]s.<ref>Drop rates</ref>\"}}");
		ChatApi.ToolResult r = run(tools(), "wiki_page", "{\"title\":\"abyssal whip\",\"section\":2}");
		assertFalse(r.content, r.error);
		assertEquals("https://oldschool.runescape.wiki/w/Abyssal_whip#Drops\nWiki page \"Abyssal whip\", section 2 only:\n\n"
			+ "==Drops==\nDropped by [[abyssal demon]]s.", r.content);
		assertEquals("Read the Wiki page \"Abyssal whip\" (Drops)", lastActivity());
		HttpUrl sent = requests.get(0);
		assertEquals("2", sent.queryParameter("section"));
		assertEquals("wikitext", sent.queryParameter("prop"));
		assertEquals("1", sent.queryParameter("redirects"));

		// Models sometimes send numbers as text.
		run(tools(), "wiki_page", "{\"title\":\"Abyssal whip\",\"section\":\"2\"}");
		assertEquals("2", requests.get(1).queryParameter("section"));
		// The introduction, which has no heading to link to.
		answer("200", "{\"parse\":{\"title\":\"Abyssal whip\",\"wikitext\":\"The whip.\"}}");
		assertTrue(run(tools(), "wiki_page", "{\"title\":\"Abyssal whip\",\"section\":0}").content.startsWith("https://oldschool.runescape.wiki/w/Abyssal_whip\n"));
		assertEquals("Read the Wiki page \"Abyssal whip\" (introduction)", lastActivity());

		answer("200", "{\"error\":{\"code\":\"nosuchsection\",\"info\":\"There is no section 9 in Abyssal whip.\"}}");
		ChatApi.ToolResult missing = run(tools(), "wiki_page", "{\"title\":\"Abyssal whip\",\"section\":9}");
		assertTrue(missing.error);
		assertTrue(missing.content, missing.content.startsWith("The page \"Abyssal whip\" has no section 9."));
		assertEquals("Found no section 9 on the Wiki page \"Abyssal whip\"", lastActivity());
	}

	@Test
	public void thePanelFoldsTheLinesOfLookUpsThatWentThrough() throws Exception
	{
		// The panel knows these lines by how they start (PanelText.summary): what is written here is what it reads.
		LookupTools tools = new LookupTools(wiki(5000), whips(), activity::add);
		answer("200", SEARCH);
		run(tools, "wiki_search", "{\"query\":\"abyssal whip\"}");
		answer("200", "{\"parse\":{\"title\":\"Abyssal whip\",\"wikitext\":\"==Drops==\\nDropped by demons.\"}}");
		run(tools, "wiki_page", "{\"title\":\"abyssal whip\",\"section\":2}");
		run(tools, "ge_price", "{\"item\":\"cannonballs\"}");
		answer("200", MISSING);
		run(tools, "wiki_page", "{\"title\":\"Abyssal wip\"}");
		assertEquals(Arrays.asList("Searched the Wiki for \"abyssal whip\"", "Read the Wiki page \"Abyssal whip\" (Drops)",
			"Checked the GE price of Cannonball", "Found no Wiki page called \"Abyssal wip\""), activity);

		PanelText.Summary shown = PanelText.summary(activity);
		assertEquals("Looked up Abyssal whip, Cannonball (GE price)", shown.line(s -> true));
		assertEquals("Found no Wiki page called \"Abyssal wip\"\nWiki pages: Abyssal whip\n"
			+ "Wiki searches: \"abyssal whip\"\nGE prices: Cannonball", shown.details);
	}

	@Test
	public void longPagesAreCutAndListTheirSections() throws Exception
	{
		StringBuilder text = new StringBuilder("Intro.");
		StringBuilder sections = new StringBuilder();
		for (int i = 1; i <= 30; i++)
		{
			text.append("\n== Part ").append(i).append(" ==\n").append("x".repeat(990));
			if (sections.length() > 0)
			{
				sections.append(',');
			}
			sections.append("{\"level\":\"").append(i % 5 == 0 ? 3 : 2).append("\",\"line\":\"Part <i>").append(i)
				.append("</i>\",\"index\":\"").append(i).append("\",\"fromtitle\":\"Money_making_guide\"}");
		}
		// A section that comes from a template the page uses can't be asked for through this page.
		sections.append(",{\"level\":\"2\",\"line\":\"From a template\",\"index\":\"T-1\",\"fromtitle\":\"Template:Navbox\"}");
		answer("200", "{\"parse\":{\"title\":\"Money making guide\",\"wikitext\":" + gson.toJson(text.toString())
			+ ",\"sections\":[" + sections + "]}}");
		ChatApi.ToolResult r = run(tools(), "wiki_page", "{\"title\":\"Money making guide\"}");
		assertFalse(r.error);
		int list = r.content.indexOf("\n\n[Cut here: the page goes on. Ask for one section with `section`; the page's sections are:\n"
			+ "0 (introduction)\n1 Part 1\n2 Part 2\n");
		assertTrue(r.content, list > 0);
		// The wikitext itself is cut at a line break within the limit, and not just after a heading.
		String header = "https://oldschool.runescape.wiki/w/Money_making_guide\nWiki page \"Money making guide\":\n\n";
		assertTrue(list - header.length() <= LookupTools.PAGE_CHARS);
		assertTrue(r.content.substring(0, list).endsWith("x"));
		assertTrue(r.content, r.content.contains("\n4 Part 4\n  5 Part 5\n6 Part 6\n"));
		assertTrue(r.content, r.content.endsWith("\n29 Part 29\n  30 Part 30]"));
		assertFalse(r.content, r.content.contains("template"));

		// Long but not cut: still lists them, for next time.
		answer("200", "{\"parse\":{\"title\":\"Money making guide\",\"wikitext\":" + gson.toJson(text.substring(0, 8000))
			+ ",\"sections\":[" + sections + "]}}");
		String fits = run(tools(), "wiki_page", "{\"title\":\"Money making guide\"}").content;
		assertFalse(fits.contains("[Cut here"));
		assertTrue(fits, fits.contains("\n\n[A long page: next time, ask for one section with `section`. Its sections are:\n0 (introduction)\n1 Part 1\n"));

		// One long section has no list to offer.
		answer("200", "{\"parse\":{\"title\":\"Money making guide\",\"wikitext\":" + gson.toJson("== Part 1 ==\n" + "y ".repeat(8000)) + "}}");
		String part = run(tools(), "wiki_page", "{\"title\":\"Money making guide\",\"section\":1}").content;
		assertTrue(part, part.endsWith("y\n\n[Cut here: the rest is on the page itself.]"));
	}

	@Test
	public void wikiTroubleIsAnErrorTheModelCanReadNeverACrash() throws Exception
	{
		answer("500", "<html>Internal error</html>");
		ChatApi.ToolResult r = run(tools(), "wiki_page", "{\"title\":\"Abyssal whip\"}");
		assertTrue(r.error);
		assertEquals("The Wiki is having trouble right now (HTTP 500). Try again, or answer without the Wiki and say so.", r.content);
		assertEquals("Couldn't read the Wiki page \"Abyssal whip\"", lastActivity());
		ChatApi.ToolResult s = run(tools(), "wiki_search", "{\"query\":\"whip\"}");
		assertTrue(s.error);
		assertEquals("Couldn't search the Wiki for \"whip\"", lastActivity());

		answer("429", "{}");
		assertTrue(run(tools(), "wiki_search", "{\"query\":\"whip\"}").content.startsWith("The Wiki is getting too many requests"));

		answer("200", "{\"error\":{\"code\":\"maxlag\",\"info\":\"Waiting for a database server: 6 seconds lagged.\"}}");
		assertTrue(run(tools(), "wiki_search", "{\"query\":\"whip\"}").content.startsWith("The Wiki is busy right now."));

		for (String unreadable : new String[]{"<html>not json", "[]", "", "{\"parse\":{\"title\":\"Abyssal whip\"}}", "{\"parse\":[1,2]}"})
		{
			answer("200", unreadable);
			ChatApi.ToolResult u = run(tools(), "wiki_page", "{\"title\":\"Abyssal whip\"}");
			assertTrue(unreadable, u.error);
			assertTrue(u.content, u.content.startsWith("The Wiki sent an answer OSRS AI Buddy couldn't read."));
		}
		answer("200", "{\"query\":7}");
		assertTrue(run(tools(), "wiki_search", "{\"query\":\"whip\"}").content.startsWith("The Wiki sent an answer OSRS AI Buddy couldn't read."));

		WikiClient nothingThere = new WikiClient(http, gson, () -> true, HttpUrl.get("http://127.0.0.1:1/api.php"), 5000);
		ChatApi.ToolResult down = run(new LookupTools(nothingThere, new FakePrices(), activity::add), "wiki_search", "{\"query\":\"whip\"}");
		assertTrue(down.content, down.content.startsWith("Couldn't reach the Wiki"));
	}

	@Test
	public void aSlowWikiTimesOutAndTheNextLookUpStillGoes() throws Exception
	{
		answer("hold", PAGE, "200", SEARCH);
		LookupTools tools = new LookupTools(wiki(300), new FakePrices(), activity::add);
		ChatApi.ToolResult r = run(tools, "wiki_page", "{\"title\":\"Abyssal whip\"}");
		assertTrue(r.error);
		assertEquals("The Wiki took too long to answer. Try again, or answer without the Wiki and say so.", r.content);
		assertEquals("Couldn't read the Wiki page \"Abyssal whip\"", lastActivity());
		assertFalse(run(tools, "wiki_search", "{\"query\":\"whip\"}").error);
	}

	@Test
	public void wikiRequestsGoOneAtATime() throws Exception
	{
		answer("hold", PAGE, "200", SEARCH);
		arrivals = new CountDownLatch(2);
		LookupTools tools = tools();
		CountDownLatch page = start(tools, "wiki_page", "{\"title\":\"Abyssal whip\"}");
		CountDownLatch search = start(tools, "wiki_search", "{\"query\":\"whip\"}");
		// While the Wiki is still answering the first, the second waits its turn.
		assertFalse(arrivals.await(500, TimeUnit.MILLISECONDS));
		assertEquals(1, requests.size());
		release.countDown();
		assertTrue(page.await(10, TimeUnit.SECONDS));
		assertTrue(search.await(10, TimeUnit.SECONDS));
		assertEquals(2, requests.size());
		assertEquals(1, mostInFlight.get());
		assertEquals(2, results.size());
		assertFalse(results.get(0).error);
		assertFalse(results.get(1).error);
	}

	@Test
	public void lookUpsStillWaitingWhenTheReplyStopsArentSent() throws Exception
	{
		answer("hold", PAGE, "200", SEARCH);
		AtomicBoolean wanted = new AtomicBoolean(true);
		LookupTools tools = new LookupTools(wiki(5000), new FakePrices(), activity::add, wanted::get);
		CountDownLatch page = start(tools, "wiki_page", "{\"title\":\"Abyssal whip\"}");
		CountDownLatch search = start(tools, "wiki_search", "{\"query\":\"whip\"}");
		// The reply stops while the Wiki is still answering the page: the search waiting its turn goes nowhere.
		wanted.set(false);
		release.countDown();
		assertTrue(page.await(10, TimeUnit.SECONDS));
		assertTrue(search.await(10, TimeUnit.SECONDS));
		assertEquals(1, requests.size());
		int errors = 0;
		for (ChatApi.ToolResult r : results)
		{
			errors += r.error ? 1 : 0;
		}
		assertEquals(1, errors);
		assertEquals("nothing to list for it", List.of("Read the Wiki page \"Abyssal whip\""), activity);
	}

	@Test
	public void aListenerThatThrowsDoesntHoldUpTheNextRequest() throws Exception
	{
		answer("hold", PAGE, "200", PAGE);
		WikiClient wiki = wiki(5000);
		wiki.page("Abyssal whip", -1, () -> true, new WikiClient.Listener<WikiClient.Page>()
		{
			@Override
			public void onResult(WikiClient.Page result)
			{
				throw new IllegalStateException("a bug elsewhere");
			}

			@Override
			public void onError(String code, String message)
			{
				throw new IllegalStateException("a bug elsewhere");
			}
		});
		CountDownLatch second = new CountDownLatch(1);
		wiki.page("Abyssal whip", -1, () -> true, new WikiClient.Listener<WikiClient.Page>()
		{
			@Override
			public void onResult(WikiClient.Page result)
			{
				second.countDown();
			}

			@Override
			public void onError(String code, String message)
			{
			}
		});
		release.countDown();
		assertTrue(second.await(10, TimeUnit.SECONDS));
	}

	@Test
	public void nothingGoesToTheWikiWhileItsNotAllowed() throws Exception
	{
		answer("200", SEARCH);
		allowed = false;
		ChatApi.ToolResult r = run(tools(), "wiki_search", "{\"query\":\"whip\"}");
		assertTrue(r.error);
		assertEquals("Wiki look-ups are turned off in the OSRS AI Buddy settings. Answer without the Wiki.", r.content);
		assertTrue(requests.isEmpty());
	}

	@Test
	public void withWikiLookUpsOffOnlyThePriceToolIsOffered() throws Exception
	{
		LookupTools tools = new LookupTools(null, new FakePrices(), activity::add);
		assertEquals(List.of("ge_price"), names(tools.specs()));
		ChatApi.ToolResult r = run(tools, "wiki_page", "{\"title\":\"Abyssal whip\"}");
		assertTrue(r.error);
		assertTrue(r.content, r.content.contains("turned off"));
		assertEquals("Skipped a Wiki page: Wiki look-ups are off", lastActivity());
		assertTrue(requests.isEmpty());
	}

	@Test
	public void toolsAreOfferedInAFixedOrderWithStableText()
	{
		List<ChatApi.ToolSpec> specs = tools().specs();
		assertEquals(List.of("wiki_search", "wiki_page", "ge_price"), names(specs));
		List<ChatApi.ToolSpec> again = tools().specs();
		for (int i = 0; i < specs.size(); i++)
		{
			ChatApi.ToolSpec s = specs.get(i);
			assertTrue(s.name, s.description.length() < 300);
			assertEquals("object", s.inputSchema.get("type").getAsString());
			assertEquals(1, s.inputSchema.getAsJsonArray("required").size());
			assertTrue(s.inputSchema.getAsJsonObject("properties").has(s.inputSchema.getAsJsonArray("required").get(0).getAsString()));
			// Part of the cached prompt: the same every time.
			assertEquals(s.description, again.get(i).description);
			assertEquals(s.inputSchema, again.get(i).inputSchema);
		}
		assertEquals("integer", specs.get(1).inputSchema.getAsJsonObject("properties").getAsJsonObject("section").get("type").getAsString());
		assertTrue(LookupTools.handles("wiki_page"));
		assertFalse(LookupTools.handles("get_bank"));
	}

	@Test
	public void oddInputIsAnErrorNotACrashAndAsksNothing() throws Exception
	{
		answer("200", PAGE);
		LookupTools tools = tools();
		String[][] calls = {
			{"wiki_search", "{}", "wiki_search needs a query"},
			{"wiki_search", "{\"query\":\"   \"}", "wiki_search needs a query"},
			{"wiki_search", "{\"query\":{\"text\":\"whip\"}}", "wiki_search needs a query"},
			{"wiki_page", "{\"title\":\"\"}", "wiki_page needs a title"},
			{"wiki_page", "{\"title\":\"Abyssal whip\",\"section\":\"drops\"}", "section must be"},
			{"wiki_page", "{\"title\":\"Abyssal whip\",\"section\":-3}", "section must be"},
			{"wiki_page", "{\"title\":\"Abyssal whip\",\"section\":2.5}", "section must be"},
			{"wiki_page", "{\"title\":\"Abyssal whip\",\"section\":[2]}", "section must be"},
			{"ge_price", "{}", "ge_price needs an item name"},
			{"ge_price", "{\"item\":\"whip\",\"quantity\":0}", "quantity must be"},
			{"ge_price", "{\"item\":\"whip\",\"quantity\":1e30}", "quantity must be"},
			{"wiki_edit", "{\"title\":\"Abyssal whip\"}", "There's no tool called wiki_edit."},
			{"wiki_page", null, "wiki_page needs a title"},
		};
		for (String[] call : calls)
		{
			ChatApi.ToolResult r = run(tools, call[0], call[1]);
			assertTrue(call[1], r.error);
			assertTrue(r.content, r.content.startsWith(call[2]));
		}
		assertEquals(calls.length, activity.size());
		assertTrue(requests.isEmpty());
	}

	@Test
	public void titlesAreTakenFromLinksAndHeadingsLeftOff()
	{
		assertEquals("Abyssal whip", LookupTools.title("https://oldschool.runescape.wiki/w/Abyssal_whip#Drops"));
		assertEquals("Treasure Trails/Guide/Anagrams", LookupTools.title("https://oldschool.runescape.wiki/w/Treasure_Trails/Guide/Anagrams"));
		assertEquals("Abyssal whip", LookupTools.title("Abyssal whip#Drops"));
		assertEquals("Abyssal_whip", LookupTools.title("Abyssal_whip"));
		assertNull(LookupTools.title("  "));
		assertNull(LookupTools.title(null));
		// Other sites' links are just text to look up.
		assertEquals("https://example.com/w/Abyssal_whip", LookupTools.title("https://example.com/w/Abyssal_whip"));
	}

	@Test
	public void pageLinksAreWikiAddresses()
	{
		assertEquals("https://oldschool.runescape.wiki/w/Abyssal_whip", WikiClient.pageUrl("Abyssal whip", null));
		assertEquals("https://oldschool.runescape.wiki/w/Abyssal_whip#Combat_stats", WikiClient.pageUrl("Abyssal whip", "Combat stats"));
		assertEquals("https://oldschool.runescape.wiki/w/Treasure_Trails/Guide/Anagrams", WikiClient.pageUrl("Treasure Trails/Guide/Anagrams", null));
		assertEquals("https://oldschool.runescape.wiki/w/100%25_or_more%3F", WikiClient.pageUrl("100% or more?", null));
		assertEquals("https://oldschool.runescape.wiki/w/Bob's_Brilliant_Axes", WikiClient.pageUrl("Bob's Brilliant Axes", ""));
	}

	@Test
	public void wikitextIsCleanedOfWhatsNotWorthReading()
	{
		assertEquals("Before\n\nAfter", WikiClient.clean("Before\r\n\r\n\r\n\r\n[[File:A.png|thumb|A [[b]] and [[c|d]]]]\r\nAfter"));
		assertEquals("Text", WikiClient.clean("Text<!-- never closed\nmore"));
		assertEquals("See [[:File:A.png]] and [[Image talk]].", WikiClient.clean("See [[:File:A.png]] and [[Image talk]].[[Category:X]][[ image : B.png ]]"));
		// An image link that's never closed is left as it is rather than taking the rest of the page.
		assertEquals("A [[File:B.png|thumb| caption\n\nRest", WikiClient.clean("A [[File:B.png|thumb| caption\n\n\n\nRest"));
		assertEquals("A.B.C.", WikiClient.clean("A.<ref>one</ref>B.<REF name=\"x\"/>C.<references />"));
		assertEquals("{{Infobox Monster\n|combat = 124\n}}", WikiClient.clean("{{Infobox Monster\n|combat = 124\n}}\n__NOTOC__  "));
		assertEquals("A.B.C.", WikiClient.clean("A.<references>\n<ref name=\"x\">one</ref>\n</references >B.<Gallery mode=x>\nC.png\n</GALLERY>C."));
		// Only whole tag names: these aren't footnotes or galleries.
		assertEquals("<refx>a</refx> <galleryish>b</galleryish>", WikiClient.clean("<refx>a</refx> <galleryish>b</galleryish>"));
	}

	@Test
	public void pagesFullOfTagsLeftOpenAreStillCleanedQuickly()
	{
		// One vandalised page shouldn't hold up a look-up: a regex took seconds on a tenth of this.
		StringBuilder page = new StringBuilder("Start ");
		for (int i = 0; i < 20_000; i++)
		{
			page.append("<ref>x ").append("<gallery>y ").append("<references ");
		}
		long started = System.nanoTime();
		String cleaned = WikiClient.clean(page.append("end").toString());
		assertTrue("took " + (System.nanoTime() - started) / 1_000_000 + "ms", System.nanoTime() - started < TimeUnit.SECONDS.toNanos(2));
		// Tags that are never closed are left as they are, and so is what follows them.
		assertTrue(cleaned.startsWith("Start <ref>x"));
		assertTrue(cleaned.endsWith("end"));
	}

	@Test
	public void htmlBecomesPlainText()
	{
		assertEquals("a&lt;b", WikiClient.plainText("a&amp;lt;b"));
		assertEquals("It's 5×2 – ok é", WikiClient.plainText("It&#x27;s 5&times;2 &ndash; <b>ok</b> &#233;"));
		assertEquals("&bogus; stays", WikiClient.plainText("&bogus; stays"));
		assertEquals("one two", WikiClient.plainText(" one\n\t <span class=\"x\">two</span> "));
		assertEquals("Drops", WikiClient.heading("== Drops ==\ntext"));
		assertNull(WikiClient.heading("No heading\n== Later =="));
	}

	@Test
	public void headingsAreReadAsTheWikiReadsThem()
	{
		assertEquals("Drops", WikiClient.heading("\n==Drops== \ntext"));
		assertEquals("Six", WikiClient.heading("====== Six ======"));
		assertEquals("= Seven =", WikiClient.heading("======= Seven ======="));
		assertEquals("= Uneven", WikiClient.heading("=== Uneven =="));
		assertEquals("A = B", WikiClient.heading("== A = B =="));
		assertEquals("==", WikiClient.heading("===="));
		assertNull(WikiClient.heading("== Not closed"));
		assertNull(WikiClient.heading("= ="));
		// A comment after the heading still leaves it a heading, however many spaces come before it (anyone can edit
		// the Wiki): read in a moment, not in seconds.
		long start = System.nanoTime();
		assertEquals("Drops", WikiClient.heading("== Drops ==" + " ".repeat(20_000) + "<!-- x -->\ntext"));
		assertEquals("Drops", WikiClient.heading("== Drops ==" + " ".repeat(20_000) + "<!-- x\n -->\ntext"));
		assertEquals("a b", WikiClient.clean("a" + " ".repeat(60_000) + "b" + " ".repeat(60_000) + "\n").replaceAll(" +", " "));
		long ms = (System.nanoTime() - start) / 1_000_000;
		assertTrue(ms + "ms", ms < 1000);
	}

	@Test
	public void cutsEndAtALineBreak()
	{
		assertEquals("short", LookupTools.cut("short", 10));
		assertEquals("line one", LookupTools.cut("line one\nline two", 12));
		assertEquals("word word", LookupTools.cut("word word word", 10));
		assertEquals("abcdefghij", LookupTools.cut("abcdefghijklmnop", 10));
		assertEquals("Intro.", LookupTools.cut("Intro.\n== Drops ==\nlots of drops", 24));
	}

	// GE prices, with canned prices instead of RuneLite's.

	private static class FakePrices implements LookupTools.Prices
	{
		final List<ItemPrice> items = new ArrayList<>();
		final Map<Integer, Integer> alch = new HashMap<>();
		final List<String> searches = new CopyOnWriteArrayList<>();
		boolean activelyTraded = true;

		FakePrices add(int id, String name, long guide, long active, int highAlchemy)
		{
			ItemPrice p = new ItemPrice();
			p.setId(id);
			p.setName(name);
			p.setPrice(guide);
			p.setWikiPrice(active);
			items.add(p);
			alch.put(id, highAlchemy);
			return this;
		}

		@Override
		public List<ItemPrice> search(String text)
		{
			searches.add(text);
			List<ItemPrice> found = new ArrayList<>();
			for (ItemPrice p : items)
			{
				if (p.getName().toLowerCase(Locale.ROOT).contains(text.toLowerCase(Locale.ROOT)))
				{
					found.add(p);
				}
			}
			return found;
		}

		@Override
		public long price(ItemPrice item)
		{
			return activelyTraded && item.getWikiPrice() > 0 ? item.getWikiPrice() : item.getPrice();
		}

		@Override
		public void highAlchemy(List<Integer> ids, Consumer<Map<Integer, Integer>> done)
		{
			Map<Integer, Integer> values = new HashMap<>();
			for (int id : ids)
			{
				if (alch.containsKey(id))
				{
					values.put(id, alch.get(id));
				}
			}
			// Like the real one, from another thread.
			new Thread(() -> done.accept(values)).start();
		}
	}

	private static FakePrices whips()
	{
		return new FakePrices()
			.add(12773, "Volcanic abyssal whip", 1_700_000, 1_700_000, 72_000)
			.add(12774, "Frozen abyssal whip", 1_650_000, 1_640_000, 72_000)
			.add(4151, "Abyssal whip", 1_498_000, 1_523_410, 72_000)
			.add(26482, "Abyssal whip (or)", 1_600_000, 0, 72_000)
			.add(4178, "Abyssal whip (beta)", 0, 0, 0)
			.add(20405, "Abyssal tentacle", 900_000, 950_000, 0)
			.add(21000, "Twisted abyssal whip thing", 5, 5, 1)
			.add(22000, "Abyssal whip fragment", 10, 10, 1)
			.add(2, "Cannonball", 190, 187, 3);
	}

	@Test
	public void gePricesListTheLikeliestItemsFirst() throws Exception
	{
		FakePrices prices = whips();
		ChatApi.ToolResult r = run(new LookupTools(null, prices, activity::add), "ge_price", "{\"item\":\"abyssal whip\"}");
		assertFalse(r.content, r.error);
		assertEquals("Grand Exchange prices from RuneLite (its price list updates every 30 minutes):\n"
			+ "- Abyssal whip: 1,523,410 gp (GE guide price 1,498,000 gp); high alchemy 72,000 gp\n"
			+ "- Abyssal whip (or): 1,600,000 gp; high alchemy 72,000 gp\n"
			+ "- Abyssal whip (beta): no recent price\n"
			+ "- Abyssal whip fragment: 10 gp; high alchemy 1 gp\n"
			+ "- Frozen abyssal whip: 1,640,000 gp (GE guide price 1,650,000 gp); high alchemy 72,000 gp\n"
			+ "...and 2 more items match \"abyssal whip\"; ask with a fuller name to see them.", r.content);
		assertEquals("Checked the GE price of Abyssal whip", lastActivity());
		assertEquals(List.of("abyssal whip"), prices.searches);
	}

	@Test
	public void gePricesForAQuantityFollowRuneLitesPriceSetting() throws Exception
	{
		FakePrices prices = whips();
		prices.activelyTraded = false;
		ChatApi.ToolResult r = run(new LookupTools(null, prices, activity::add), "ge_price", "{\"item\":\"cannonballs\",\"quantity\":\"1000\"}");
		// "cannonballs" finds Cannonball; with the guide price shown, there's no second price to mention.
		assertEquals("Grand Exchange prices from RuneLite (its price list updates every 30 minutes):\n"
			+ "- Cannonball: 190 gp each, 190,000 gp for 1,000; high alchemy 3 gp each", r.content);
		assertEquals(List.of("cannonballs", "cannonball"), prices.searches);
		assertEquals("Checked the GE price of Cannonball", lastActivity());

		prices.activelyTraded = true;
		assertEquals("Grand Exchange prices from RuneLite (its price list updates every 30 minutes):\n"
			+ "- Cannonball: 187 gp each, 187,000 gp for 1,000 (GE guide price 190 gp each); high alchemy 3 gp each",
			run(new LookupTools(null, prices, activity::add), "ge_price", "{\"item\":\"Cannonball\",\"quantity\":1000}").content);
	}

	@Test
	public void anItemWithoutAPriceSaysWhyItMightBe() throws Exception
	{
		ChatApi.ToolResult r = run(new LookupTools(wiki(5000), whips(), activity::add), "ge_price", "{\"item\":\"Twisted bow\"}");
		assertTrue(r.error);
		assertEquals("RuneLite has no Grand Exchange price for anything called \"Twisted bow\". It may not be tradeable, or "
			+ "go by another name (wiki_search can tell), or RuneLite hasn't loaded its prices yet.", r.content);
		assertEquals("Found no GE price for \"Twisted bow\"", lastActivity());
		assertTrue(requests.isEmpty());
	}

	@Test
	public void pricesWithoutAlchemyValuesStillAnswer() throws Exception
	{
		FakePrices prices = whips();
		prices.alch.clear();
		assertEquals("Grand Exchange prices from RuneLite (its price list updates every 30 minutes):\n- Abyssal tentacle: 950,000 gp "
			+ "(GE guide price 900,000 gp)", run(new LookupTools(null, prices, activity::add), "ge_price", "{\"item\":\"tentacle\"}").content);
	}

	@Test
	public void aBrokenPriceSourceStillAnswersOnce() throws Exception
	{
		LookupTools.Prices broken = new LookupTools.Prices()
		{
			@Override
			public List<ItemPrice> search(String text)
			{
				throw new IllegalStateException("prices not ready");
			}

			@Override
			public long price(ItemPrice item)
			{
				return 0;
			}

			@Override
			public void highAlchemy(List<Integer> ids, Consumer<Map<Integer, Integer>> done)
			{
			}
		};
		ChatApi.ToolResult r = run(new LookupTools(null, broken, activity::add), "ge_price", "{\"item\":\"whip\"}");
		assertTrue(r.error);
		assertEquals("That look-up failed inside OSRS AI Buddy. Answer without it.", r.content);
		assertEquals(1, activity.size());

		// Something going wrong later, on another thread, still ends the call.
		FakePrices odd = new FakePrices()
		{
			@Override
			public void highAlchemy(List<Integer> ids, Consumer<Map<Integer, Integer>> done)
			{
				new Thread(() -> done.accept(new HashMap<Integer, Integer>()
				{
					@Override
					public Integer get(Object key)
					{
						throw new IllegalStateException("broken");
					}
				})).start();
			}
		}.add(2, "Cannonball", 190, 187, 3);
		ChatApi.ToolResult later = run(new LookupTools(null, odd, activity::add), "ge_price", "{\"item\":\"cannonball\"}");
		assertTrue(later.error);
		assertEquals("That look-up failed inside OSRS AI Buddy. Answer without it.", later.content);
		assertEquals("Checked the GE price of Cannonball", lastActivity());

		// An activity sink that throws doesn't stop the result.
		ChatApi.ToolResult quiet = run(new LookupTools(null, whips(), line ->
		{
			throw new IllegalStateException("panel gone");
		}), "ge_price", "{\"item\":\"cannonball\"}");
		assertFalse(quiet.error);
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
	public void manyResultsAreCappedAtSix() throws Exception
	{
		StringBuilder hits = new StringBuilder();
		for (int i = 1; i <= 9; i++)
		{
			hits.append(i > 1 ? "," : "").append("{\"title\":\"Page ").append(i).append("\",\"snippet\":\"s\"}");
		}
		answer("200", "{\"query\":{\"search\":[" + hits + "]}}");
		String text = run(tools(), "wiki_search", "{\"query\":\"page\"}").content;
		assertTrue(text, text.contains("\n6. Page 6 - "));
		assertFalse(text, text.contains("Page 7"));
		assertEquals(Arrays.asList("Searched the Wiki for \"page\""), activity);
	}

	@Test
	public void runeLitePricesAnswerOnceWhateverTheGameDoes() throws Exception
	{
		ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
		try
		{
			// The client thread never gets to the read: no alchemy values, once the wait is over.
			List<Runnable> queued = new CopyOnWriteArrayList<>();
			ClientThread never = new ClientThread()
			{
				@Override
				public void invoke(Runnable r)
				{
					queued.add(r);
				}
			};
			BlockingQueue<Map<Integer, Integer>> heard = new LinkedBlockingQueue<>();
			new LookupTools.RuneLitePrices(null, never, executor, () -> false, 100).highAlchemy(List.of(4151), heard::add);
			assertEquals(Collections.emptyMap(), heard.poll(5, TimeUnit.SECONDS));
			// It gets there after all (with no item data: each id it can't read is left out): nothing more is heard.
			queued.get(0).run();
			executor.submit(() -> { }).get(5, TimeUnit.SECONDS);
			assertTrue(heard.isEmpty());

			// The client thread can't take it (RuneLite is closing): an answer at once, and only that one.
			ClientThread closing = new ClientThread()
			{
				@Override
				public void invoke(Runnable r)
				{
					throw new IllegalStateException("closing");
				}
			};
			new LookupTools.RuneLitePrices(null, closing, executor, () -> false, 100).highAlchemy(List.of(4151), heard::add);
			assertEquals(Collections.emptyMap(), heard.poll());
			// Past the wait: the scheduler runs in time order, so the wait's end would have come before this.
			executor.schedule(() -> { }, 200, TimeUnit.MILLISECONDS).get(5, TimeUnit.SECONDS);
			assertTrue(heard.isEmpty());

			// The read runs at once: its answer, off the client thread, and nothing when the wait would have ended.
			ClientThread now = new ClientThread()
			{
				@Override
				public void invoke(Runnable r)
				{
					r.run();
				}
			};
			new LookupTools.RuneLitePrices(null, now, executor, () -> false, 100).highAlchemy(List.of(4151), heard::add);
			assertEquals(Collections.emptyMap(), heard.poll(5, TimeUnit.SECONDS));
			executor.schedule(() -> { }, 200, TimeUnit.MILLISECONDS).get(5, TimeUnit.SECONDS);
			assertTrue(heard.isEmpty());
		}
		finally
		{
			executor.shutdownNow();
		}
	}
}

