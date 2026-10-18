package com.osrsaibuddy;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * The Old School RuneScape Wiki's MediaWiki API: search, and a page's wikitext. Requests go through RuneLite's own HTTP
 * client, one at a time (the Wiki asks tools not to send several at once), and answer on an OkHttp thread.
 */
@Slf4j
final class WikiClient
{
	/** The error code for a request that wasn't sent because Allow OSRS Wiki searches were turned off. */
	static final String TURNED_OFF = "turned-off";
	/** The error code for a request that wasn't sent because the reply it was for had stopped. */
	static final String STOPPED = "stopped";
	/** Where people read the Wiki: links in replies point here, also in tests. */
	static final HttpUrl BASE = HttpUrl.get("https://oldschool.runescape.wiki/");
	static final HttpUrl API = HttpUrl.get("https://oldschool.runescape.wiki/api.php");
	/** The Wiki asks tools to say who they are and where to find them; RuneLite puts its own name in front. */
	static final String USER_AGENT = "osrs-ai-buddy (RuneLite plugin; https://github.com/D-AZ0617/osrs-ai-buddy)";
	private static final long TIMEOUT_MILLIS = 15_000;
	/**
	 * Seconds the Wiki's own servers may keep an answer, so asking again in the same chat costs it little. Nothing is
	 * kept on this computer: the searches come from the player's questions.
	 */
	private static final String CACHE_SECONDS = "300";

	private static final Pattern TAG = Pattern.compile("<[^>]*>");
	private static final Pattern ENTITY = Pattern.compile("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z]{2,8});");
	private static final Pattern SPACES = Pattern.compile("\\s+");
	/** An HTML comment, also one left open at the end. */
	private static final Pattern COMMENT = Pattern.compile("(?s)<!--.*?(-->|$)");
	/** Where the footnotes were listed. */
	private static final Pattern REFLIST = Pattern.compile("(?i)\\{\\{\\s*reflist\\s*}}");
	/** Images, sounds and the page's categories. A link to one starts with a colon instead and is left alone. */
	private static final Pattern FILE_LINK = Pattern.compile("(?i)\\[\\[\\s*(file|image|media|category)\\s*:");
	/** Page layout switches such as __NOTOC__. */
	private static final Pattern MAGIC_WORD = Pattern.compile("__[A-Z]+__");
	/**
	 * Spaces at the end of a line, matched only from where a run of them starts: tried from every space of a long run
	 * that doesn't end the line (anyone can edit the Wiki), it would take seconds.
	 */
	private static final Pattern TRAILING_SPACES = Pattern.compile("(?m)(?<![ \\t])[ \\t]++$");
	private static final Pattern BLANK_LINES = Pattern.compile("\n{3,}");
	/** The deepest heading level: "====== Six ======". */
	private static final int MAX_HEADING = 6;

	/** What a look-up hears back: one of the two, once, on an OkHttp thread. */
	interface Listener<T>
	{
		void onResult(T result);

		/**
		 * {@code code}: the Wiki's own error code ("missingtitle", "nosuchsection"...) when it answered with one,
		 * {@link #TURNED_OFF} when look-ups were turned off before the request went out, {@link #STOPPED} when it was no
		 * longer wanted by then, or null when the Wiki couldn't be reached or understood. {@code message} says what went
		 * wrong in a sentence.
		 */
		void onError(String code, String message);
	}

	static final class SearchResult
	{
		final String title;
		final String url;
		/** Plain text, on one line; may be empty. */
		final String snippet;

		SearchResult(String title, String url, String snippet)
		{
			this.title = title;
			this.url = url;
			this.snippet = snippet;
		}
	}

	static final class Search
	{
		final List<SearchResult> results;
		/** The Wiki's "did you mean", or null. */
		final String suggestion;

		Search(List<SearchResult> results, String suggestion)
		{
			this.results = results;
			this.suggestion = suggestion;
		}
	}

	static final class Section
	{
		/** What to ask for to read only this section. */
		final int index;
		/** 2 for "== A ==", 3 for "=== B ===", and so on. */
		final int level;
		final String heading;

		Section(int index, int level, String heading)
		{
			this.index = index;
			this.level = level;
			this.heading = heading;
		}
	}

	static final class Page
	{
		/** The page's own title, after following redirects. */
		final String title;
		/** The title asked for, when it redirected here; otherwise null. */
		final String redirectedFrom;
		/** The page (or the one section asked for) as written, not yet cleaned. */
		final String wikitext;
		/** The page's own sections, in order; empty when only one section was asked for. */
		final List<Section> sections;
		/** The page's address, at the section asked for if its heading is plain enough to link to. */
		final String url;

		Page(String title, String redirectedFrom, String wikitext, List<Section> sections, String url)
		{
			this.title = title;
			this.redirectedFrom = redirectedFrom;
			this.wikitext = wikitext;
			this.sections = sections;
			this.url = url;
		}
	}

	private final OkHttpClient http;
	private final Gson gson;
	private final HttpUrl api;
	/** Checked just before each request goes out: Allow OSRS Wiki searches (and AI requests) may be turned off while one waits. */
	private final BooleanSupplier allowed;
	/** Requests waiting for the one in flight to finish. */
	private final Deque<Runnable> waiting = new ArrayDeque<>();
	private boolean busy;
    private final java.util.function.LongSupplier clock;
    private long cooldownUntil;
    private static final long CACHE_MILLIS = 300_000;
    private static final int MAX_RESPONSE_BYTES = 1_048_576;
    private static final class Cached
    {
        final String text; final long expires;
        Cached(String text, long expires) { this.text = text; this.expires = expires; }
    }
    private final java.util.Map<String, Cached> cache = new java.util.LinkedHashMap<String, Cached>(32, .75f, true) {
        @Override protected boolean removeEldestEntry(java.util.Map.Entry<String, Cached> entry) { return size() > 32; }
    };

	WikiClient(OkHttpClient http, Gson gson, BooleanSupplier allowed)
	{
		this(http, gson, allowed, API, TIMEOUT_MILLIS);
	}

	WikiClient(OkHttpClient http, Gson gson, BooleanSupplier allowed, HttpUrl api, long timeoutMillis)
	{
        this(http, gson, allowed, api, timeoutMillis, System::currentTimeMillis);
    }

    WikiClient(OkHttpClient http, Gson gson, BooleanSupplier allowed, HttpUrl api, long timeoutMillis, java.util.function.LongSupplier clock)
    {
        this.clock = clock;
		this.http = http.newBuilder()
			.cache(null)
			.connectTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
			.readTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
			.callTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
			.build();
		this.gson = gson;
		this.allowed = allowed;
		this.api = api;
	}

	/**
	 * Full-text search of the Wiki's articles: up to {@code limit} results with a snippet each. {@code wanted}: checked
	 * just before it goes out, like the settings; false once the reply it's for has stopped.
	 */
	void search(String query, int limit, BooleanSupplier wanted, Listener<Search> listener)
	{
		HttpUrl url = common(api.newBuilder()
			.addQueryParameter("action", "query")
			.addQueryParameter("list", "search")
			.addQueryParameter("srsearch", query)
			.addQueryParameter("srlimit", String.valueOf(limit))
			.addQueryParameter("srprop", "snippet")
			.addQueryParameter("srnamespace", "0"));
		get(url, wanted, listener, WikiClient::search);
	}

	/**
	 * A page's wikitext, redirects followed. {@code section} -1 reads the whole page and lists its sections; 0 or more
	 * reads only that section (0 is the part before the first heading). {@code wanted}: as for {@link #search}.
	 */
	void page(String title, int section, BooleanSupplier wanted, Listener<Page> listener)
	{
		HttpUrl.Builder url = api.newBuilder()
			.addQueryParameter("action", "parse")
			.addQueryParameter("page", title)
			.addQueryParameter("prop", section < 0 ? "wikitext|sections" : "wikitext")
			// MediaWiki takes any value as yes; leaving it out is no.
			.addQueryParameter("redirects", "1");
		if (section >= 0)
		{
			url.addQueryParameter("section", String.valueOf(section));
		}
		get(common(url), wanted, listener, o -> page(o, section));
	}

	private static HttpUrl common(HttpUrl.Builder url)
	{
		return url
			.addQueryParameter("format", "json")
			.addQueryParameter("formatversion", "2")
			// Asks the Wiki to turn the request away rather than add to its load while it's struggling.
			.addQueryParameter("maxlag", "5")
			.addQueryParameter("maxage", CACHE_SECONDS)
			.addQueryParameter("smaxage", CACHE_SECONDS)
			.build();
	}

	/** Sends a GET once no other request is in flight, and turns the answer into a result with {@code read}. */
	private <T> void get(HttpUrl url, BooleanSupplier wanted, Listener<T> listener, Function<JsonObject, T> read)
	{
		Request request = new Request.Builder()
			.url(url)
			.header("User-Agent", USER_AGENT)
			.build();
		Runnable send = () -> send(request, wanted, listener, read);
		if (startNow(send))
		{
			send.run();
		}
	}

	/**
	 * Runs as the one request in flight. {@link #finished} follows exactly once whatever happens, before the listener
	 * hears back: a listener that throws can't hold up the requests waiting. A request whose reply was stopped while it
	 * waited its turn isn't sent: the Wiki has nothing to answer, and the next reply's look-ups don't wait behind it.
	 */
	private <T> void send(Request request, BooleanSupplier wanted, Listener<T> listener, Function<JsonObject, T> read)
	{
		String notSent;
		try
		{
			notSent = !wanted.getAsBoolean() ? STOPPED : !allowed.getAsBoolean() ? TURNED_OFF : null;
			if (notSent == null)
			{
                Cached hit;
                boolean cooling;
                synchronized (this) {
                    hit = cache.get(request.url().toString());
                    if (hit != null && hit.expires <= clock.getAsLong()) { cache.remove(request.url().toString()); hit = null; }
                    cooling = cooldownUntil > clock.getAsLong();
                }
                if (hit != null) {
                    finished();
                    try { answer(200, hit.text, listener, read); }
                    catch (RuntimeException failure) { log.warn("Wiki cache listener failed", failure); }
                    return;
                }
                if (cooling) { finished(); listener.onError("cooldown", "The Wiki has requested a pause. Answer without a fresh Wiki lookup and try later."); return; }
				http.newCall(request).enqueue(callback(listener, read));
				return;
			}
		}
		catch (RuntimeException e)
		{
			// OkHttp turned the call away (RuneLite is closing, say).
			log.debug("Wiki request not sent", e);
			notSent = null;
		}
		finished();
		if (STOPPED.equals(notSent))
		{
			listener.onError(STOPPED, "The reply this was for has stopped.");
		}
		else if (TURNED_OFF.equals(notSent))
		{
			listener.onError(TURNED_OFF, "OSRS Wiki searches are turned off in the OSRS AI Buddy settings.");
		}
		else
		{
			listener.onError(null, "Couldn't reach the Wiki.");
		}
	}

	private <T> Callback callback(Listener<T> listener, Function<JsonObject, T> read)
	{
		return new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				finished();
				listener.onError(null, unreachable(e));
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				String text;
				try (ResponseBody body = response.body())
				{
                    if (body == null) { text = ""; }
                    else {
                        okio.BufferedSource source = body.source();
                        if (source.request(MAX_RESPONSE_BYTES + 1L)) { throw new IOException("Wiki response exceeds size limit"); }
                        text = source.readUtf8();
                    }
				}
				catch (IOException e)
				{
					finished();
					listener.onError(null, unreachable(e));
					return;
				}
                boolean apiBusy = false;
                boolean cacheable = false;
                try {
                    JsonObject value = gson.fromJson(text, JsonObject.class);
                    cacheable = value != null && !value.has("error");
                    if (value != null && value.has("error") && value.get("error").isJsonObject()) {
                        String code = string(value.getAsJsonObject("error"), "code");
                        apiBusy = "maxlag".equals(code) || "ratelimited".equals(code);
                    }
                } catch (RuntimeException ignored) { }
                synchronized (WikiClient.this) {
                    long now = clock.getAsLong();
                    if (response.code() == 429 || response.code() == 503 || apiBusy) {
                        cooldownUntil = Math.max(cooldownUntil, retryTime(response.header("Retry-After"), now));
                    }
                    else if (response.isSuccessful() && cacheable && text.length() <= 131072) {
                        cache.put(response.request().url().toString(), new Cached(text, now + CACHE_MILLIS));
                    }
                }
                finished();
                answer(response.code(), text, listener, read);
			}
		};
	}

	/** True to send now; false when another request is in flight, and this one waits its turn. */
	private synchronized boolean startNow(Runnable send)
	{
		if (busy)
		{
			waiting.add(send);
			return false;
		}
		busy = true;
		return true;
	}

	/** The request in flight is done: send the next one waiting, if any. */
	private void finished()
	{
		Runnable next;
		synchronized (this)
		{
			next = waiting.poll();
			if (next == null)
			{
				busy = false;
				return;
			}
		}
		try
		{
			next.run();
		}
		catch (RuntimeException e)
		{
			// Its listener threw (it can hear back at once, when look-ups were turned off). That's not this request's
			// problem: its own listener still has to hear back.
			log.warn("Wiki look-up listener failed", e);
		}
	}

	private <T> void answer(int code, String text, Listener<T> listener, Function<JsonObject, T> read)
	{
		if (code == 429)
		{
			listener.onError(null, "The Wiki is getting too many requests right now.");
			return;
		}
		if (code < 200 || code >= 300)
		{
			listener.onError(null, code >= 500
				? "The Wiki is having trouble right now (HTTP " + code + ")."
				: "The Wiki answered HTTP " + code + ".");
			return;
		}
		JsonObject error = null;
		T result = null;
		try
		{
			JsonElement parsed = gson.fromJson(text, JsonElement.class);
			JsonObject o = parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
			// Errors, a missing page among them, come back as HTTP 200 with an "error" object.
			error = o == null ? null : object(o, "error");
			if (o != null && error == null)
			{
				result = read.apply(o);
			}
		}
		catch (RuntimeException e)
		{
			// Not JSON, or not the shape asked for: the same to the reader. Nothing escapes, so the listener hears back.
			log.debug("unreadable Wiki answer", e);
		}
		if (error != null)
		{
			String errorCode = string(error, "code");
			listener.onError(errorCode, describe(errorCode, string(error, "info")));
			return;
		}
		if (result == null)
		{
			listener.onError(null, "The Wiki sent an answer OSRS AI Buddy couldn't read.");
			return;
		}
		listener.onResult(result);
	}

	private static String describe(String code, String info)
	{
		if ("maxlag".equals(code) || "ratelimited".equals(code))
		{
			return "The Wiki is busy right now.";
		}
		String detail = info == null ? "" : ": " + ChatApi.shorten(info, 200);
		return "The Wiki turned the request down" + (code == null ? "" : " (" + code + ")") + detail;
	}

    static long retryTime(String header, long now)
    {
        if (header != null) {
            try { long seconds = Long.parseLong(header.trim()); if (seconds > 0) { return Math.addExact(now, Math.multiplyExact(seconds, 1000)); } }
            catch (NumberFormatException ignored) { }
            catch (ArithmeticException overflow) { return Long.MAX_VALUE; }
            try { long date = java.time.ZonedDateTime.parse(header, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli(); if (date > now) { return date; } }
            catch (java.time.DateTimeException ignored) { }
        }
        return now + 60_000;
    }

	private static String unreachable(IOException e)
	{
		if (ChatApi.tookTooLong(e))
		{
			return "The Wiki took too long to answer.";
		}
		String m = e.getMessage();
		// RuneLite blocks the Wiki on beta and other test worlds.
		if (m != null && m.contains("outside of LIVE"))
		{
			return "The Wiki can't be reached from this kind of world.";
		}
		return "Couldn't reach the Wiki" + (m == null ? "." : ": " + ChatApi.shorten(m, 120));
	}

	private static Search search(JsonObject o)
	{
		JsonObject query = object(o, "query");
		if (query == null)
		{
			return null;
		}
		List<SearchResult> results = new ArrayList<>();
		JsonArray hits = array(query, "search");
		if (hits != null)
		{
			for (JsonElement e : hits)
			{
				String title = e.isJsonObject() ? string(e.getAsJsonObject(), "title") : null;
				if (title != null)
				{
					String snippet = string(e.getAsJsonObject(), "snippet");
					results.add(new SearchResult(title, pageUrl(title, null), snippet == null ? "" : plainText(snippet)));
				}
			}
		}
		JsonObject info = object(query, "searchinfo");
		String suggestion = info == null ? null : string(info, "suggestion");
		return new Search(results, suggestion == null ? null : plainText(suggestion));
	}

	private static Page page(JsonObject o, int section)
	{
		JsonObject parse = object(o, "parse");
		String title = parse == null ? null : string(parse, "title");
		String wikitext = parse == null ? null : string(parse, "wikitext");
		if (title == null || wikitext == null)
		{
			return null;
		}
		String from = null;
		String anchor = null;
		JsonArray redirects = array(parse, "redirects");
		if (redirects != null && redirects.size() > 0 && redirects.get(0).isJsonObject())
		{
			JsonObject r = redirects.get(0).getAsJsonObject();
			from = string(r, "from");
			// "Whip" may lead to one part of a page.
			anchor = string(r, "tofragment");
		}
		List<Section> sections = new ArrayList<>();
		JsonArray list = array(parse, "sections");
		if (list != null)
		{
			String dbTitle = title.replace(' ', '_');
			for (JsonElement e : list)
			{
				Section s = e.isJsonObject() ? section(e.getAsJsonObject(), dbTitle) : null;
				if (s != null)
				{
					sections.add(s);
				}
			}
		}
		if (section > 0)
		{
			String heading = heading(wikitext);
			anchor = heading != null && linkable(heading) ? heading : null;
		}
		return new Page(title, from, wikitext, Collections.unmodifiableList(sections), pageUrl(title, anchor));
	}

	/**
	 * One of the page's own sections, or null. Sections that come from templates the page uses are numbered "T-1" and so
	 * on, and can't be read through this page.
	 */
	private static Section section(JsonObject s, String dbTitle)
	{
		String index = string(s, "index");
		String heading = string(s, "line");
		String fromTitle = string(s, "fromtitle");
		if (index == null || heading == null || !index.matches("[0-9]{1,4}")
			|| (fromTitle != null && !fromTitle.equals(dbTitle)))
		{
			return null;
		}
		String level = string(s, "level");
		return new Section(Integer.parseInt(index), level != null && level.matches("[1-6]") ? Integer.parseInt(level) : 2,
			plainText(heading));
	}

	/**
	 * The heading a section's wikitext starts with ("== Drops ==", maybe with a comment after it), or null. Read by
	 * hand: a pattern for it backtracks for seconds over a heading line with a long run of spaces in it.
	 */
	static String heading(String wikitext)
	{
		String s = wikitext.trim();
		int end = s.indexOf('\n');
		String line = COMMENT.matcher(end < 0 ? s : s.substring(0, end)).replaceAll("").strip();
		int open = 0;
		while (open < line.length() && line.charAt(open) == '=')
		{
			open++;
		}
		int close = 0;
		while (close < line.length() && line.charAt(line.length() - 1 - close) == '=')
		{
			close++;
		}
		// As MediaWiki reads it: the shorter run of "=" sets the level, and there's something between the two runs.
		int level = Math.min(MAX_HEADING, Math.min(Math.min(open, close), (line.length() - 1) / 2));
		String heading = level == 0 ? "" : line.substring(level, line.length() - level).strip();
		return heading.isEmpty() ? null : heading;
	}

	/** Plain words only: a heading with links, templates or formatting gets an anchor this can't predict. */
	private static boolean linkable(String heading)
	{
		return !heading.contains("[[") && !heading.contains("{{") && !heading.contains("<") && !heading.contains("''")
			&& !heading.contains("&");
	}

	/** The page's address on the Wiki: https://oldschool.runescape.wiki/w/Abyssal_whip, optionally at a heading. */
	static String pageUrl(String title, String anchor)
	{
		HttpUrl.Builder url = BASE.newBuilder()
			.addPathSegment("w")
			// Subpages ("Treasure Trails/Guide/Anagrams") keep their slashes; MediaWiki titles can't be "." or "..".
			.addPathSegments(title.trim().replace(' ', '_'));
		if (anchor != null && !anchor.trim().isEmpty())
		{
			url.fragment(anchor.trim().replace(' ', '_'));
		}
		return url.build().toString();
	}

	/**
	 * Wikitext with what isn't worth reading taken out: comments, footnotes, images, galleries and categories, layout
	 * switches, trailing spaces and runs of blank lines. Templates such as infoboxes stay: they hold the stats.
	 */
	static String clean(String wikitext)
	{
		String s = wikitext.replace("\r\n", "\n").replace('\r', '\n');
		s = COMMENT.matcher(s).replaceAll("");
		// Footnotes, where they were listed, and image galleries: nothing left to read in any of them.
		s = withoutElements(s, "ref");
		s = withoutElements(s, "references");
		s = withoutElements(s, "gallery");
		s = REFLIST.matcher(s).replaceAll("");
		s = withoutFileLinks(s);
		s = MAGIC_WORD.matcher(s).replaceAll("");
		s = TRAILING_SPACES.matcher(s).replaceAll("");
		s = BLANK_LINES.matcher(s).replaceAll("\n\n");
		return s.trim();
	}

	/**
	 * {@code s} without its {@code <name ...>...</name>} and {@code <name ... />} elements, any case. Done by hand: a
	 * regex looks for the end of every tag left open all the way to the end of the page, which takes seconds on a page
	 * full of them. A tag that's never closed, and what follows it, is left as it is.
	 */
	static String withoutElements(String s, String name)
	{
		StringBuilder out = new StringBuilder(s.length());
		int from = 0;
		int open = indexOfTag(s, "<" + name, 0);
		while (open >= 0)
		{
			int tagEnd = s.indexOf('>', open);
			if (tagEnd < 0)
			{
				break;
			}
			int end;
			int last = tagEnd - 1;
			while (last > open && Character.isWhitespace(s.charAt(last)))
			{
				last--;
			}
			if (s.charAt(last) == '/')
			{
				end = tagEnd + 1;
			}
			else
			{
				end = closeEnd(s, name, tagEnd + 1);
				if (end < 0)
				{
					break;
				}
			}
			out.append(s, from, open);
			from = end;
			open = indexOfTag(s, "<" + name, end);
		}
		return out.append(s, from, s.length()).toString();
	}

	/**
	 * Where {@code <name} starts at or after {@code from}, any case, as a whole tag name ("<ref" but not "<references"),
	 * or -1.
	 */
	private static int indexOfTag(String s, String open, int from)
	{
		for (int i = from; i + open.length() <= s.length(); i++)
		{
			if (s.charAt(i) == '<' && s.regionMatches(true, i, open, 0, open.length()))
			{
				int after = i + open.length();
				if (after == s.length() || !Character.isLetterOrDigit(s.charAt(after)) && s.charAt(after) != '_')
				{
					return i;
				}
			}
		}
		return -1;
	}

	/** Just after the first {@code </name >} at or after {@code from}, any case, or -1 if there's none. */
	private static int closeEnd(String s, String name, int from)
	{
		String close = "</" + name;
		for (int i = from; i + close.length() <= s.length(); i++)
		{
			if (s.charAt(i) == '<' && s.regionMatches(true, i, close, 0, close.length()))
			{
				int j = i + close.length();
				while (j < s.length() && Character.isWhitespace(s.charAt(j)))
				{
					j++;
				}
				if (j < s.length() && s.charAt(j) == '>')
				{
					return j + 1;
				}
			}
		}
		return -1;
	}

	/** Image links can hold links of their own in the caption: [[File:Whip.png|thumb|An [[abyssal demon]] drop]]. */
	private static String withoutFileLinks(String s)
	{
		Matcher m = FILE_LINK.matcher(s);
		StringBuilder out = new StringBuilder(s.length());
		int from = 0;
		while (m.find(from))
		{
			int end = linkEnd(s, m.start());
			if (end < 0)
			{
				// Never closed: leave the rest as it is.
				break;
			}
			out.append(s, from, m.start());
			from = end;
		}
		return out.append(s, from, s.length()).toString();
	}

	/** Where the [[link]] starting at {@code start} ends (just after its "]]"), counting links inside it; -1 if never. */
	private static int linkEnd(String s, int start)
	{
		int depth = 0;
		int i = start;
		while (i < s.length() - 1)
		{
			if (s.startsWith("[[", i))
			{
				depth++;
				i += 2;
			}
			else if (s.startsWith("]]", i))
			{
				depth--;
				i += 2;
				if (depth == 0)
				{
					return i;
				}
			}
			else
			{
				i++;
			}
		}
		return -1;
	}

	/** Search snippets and headings come as HTML: just their text, on one line. */
	static String plainText(String html)
	{
		String s = TAG.matcher(html).replaceAll("");
		Matcher m = ENTITY.matcher(s);
		StringBuffer out = new StringBuffer(s.length());
		while (m.find())
		{
			m.appendReplacement(out, Matcher.quoteReplacement(entity(m.group(1), m.group())));
		}
		m.appendTail(out);
		return SPACES.matcher(out).replaceAll(" ").trim();
	}

	/** The character an HTML entity stands for, or the entity as written if it isn't one of the usual few. */
	private static String entity(String name, String written)
	{
		if (name.startsWith("#"))
		{
			boolean hex = name.length() > 1 && (name.charAt(1) == 'x' || name.charAt(1) == 'X');
			int cp;
			try
			{
				cp = Integer.parseInt(name.substring(hex ? 2 : 1), hex ? 16 : 10);
			}
			catch (NumberFormatException e)
			{
				return written;
			}
			boolean printable = cp > 0 && Character.isValidCodePoint(cp) && !Character.isISOControl(cp);
			return printable ? new String(Character.toChars(cp)) : written;
		}
		switch (name.toLowerCase(Locale.ROOT))
		{
			case "amp":
				return "&";
			case "lt":
				return "<";
			case "gt":
				return ">";
			case "quot":
				return "\"";
			case "apos":
				return "'";
			case "nbsp":
				return " ";
			case "ndash":
				return "–";
			case "mdash":
				return "—";
			case "hellip":
				return "…";
			case "times":
				return "×";
			default:
				return written;
		}
	}

	private static JsonObject object(JsonObject o, String key)
	{
		JsonElement e = o.get(key);
		return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
	}

	private static JsonArray array(JsonObject o, String key)
	{
		JsonElement e = o.get(key);
		return e != null && e.isJsonArray() ? e.getAsJsonArray() : null;
	}

	private static String string(JsonObject o, String key)
	{
		JsonElement e = o.get(key);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
	}
}
