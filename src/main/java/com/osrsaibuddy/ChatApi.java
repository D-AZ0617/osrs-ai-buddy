package com.osrsaibuddy;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import okhttp3.Call;
import okhttp3.MediaType;

/** An AI provider: send the conversation so far, get back one reply or one error. */
interface ChatApi
{
	MediaType JSON = MediaType.get("application/json; charset=utf-8");
	/** Times a reply may stop to look things up before it has to answer. */
	int MAX_TOOL_ROUNDS = 8;
	/**
	 * Look-ups run each time it stops; any more are turned down. The Wiki answers one request at a time, and a reply
	 * that asks for dozens of pages at once would keep it busy for minutes.
	 */
	int MAX_TOOL_CALLS = 10;
	/** Times a request is sent again when the provider is busy or couldn't be reached. */
	int MAX_RETRIES = 2;
	/** The longest wait before sending again; a provider that asks for longer gets an error instead. */
	long MAX_RETRY_WAIT_MS = 60_000;
	/**
	 * Deeper than any answer or tool call needs to go. Gson reads nesting by recursion, and a few thousand levels (a
	 * model stuck writing "[") overflow the stack.
	 */
	int MAX_JSON_DEPTH = 64;
	String TOO_MANY_ROUNDS = "The assistant kept looking things up without answering. Try asking more specifically.";
	String CUT_OFF = "The reply was cut off: the connection closed early.";

	/**
	 * Starts the request: the reply streams in, with any tool calls and retries it takes. The listener hears back
	 * later on OkHttp or executor threads: {@link Listener#onPartial} any number of times, then {@link Listener#onReply}
	 * or {@link Listener#onError} exactly once, unless the request is cancelled first (then nothing more).
	 */
	Pending send(Conversation conversation, Listener listener);

	/** The provider's name for this setup, shown as the author of its replies. */
	String displayName();

	/** Lists the models the key can use, for "Test connection". The listener hears back once, on an OkHttp thread. */
	default Pending listModels(ModelsListener listener)
	{
		listener.onError("This provider can't list its models.");
		return new Pending();
	}

	final class Conversation
	{
		String model;
		String system;
		final List<Turn> turns = new ArrayList<>();
		/** Tools the model may call, in the same order every request (the order is part of the cached prompt). */
		final List<ToolSpec> tools = new ArrayList<>();
		/** Runs the tools the model calls; needed when {@link #tools} isn't empty. */
		ToolRunner toolRunner;
		/** A lower reply length limit than the provider's own, or 0 for that. */
		int maxTokens;
	}

	/** A tool the model may call while answering: a name, what it's for, and a JSON Schema for its input. */
	final class ToolSpec
	{
		final String name;
		final String description;
		final JsonObject inputSchema;

		ToolSpec(String name, String description, JsonObject inputSchema)
		{
			this.name = name;
			this.description = description;
			this.inputSchema = inputSchema;
		}
	}

	/** Runs the tools the model calls. */
	interface ToolRunner
	{
		/**
		 * Runs one call and reports back exactly once, on any thread. Never throws: problems become an error result the
		 * model can read.
		 */
		void run(String name, JsonObject input, Consumer<ToolResult> done);
	}

	final class ToolResult
	{
		final String content;
		final boolean error;
        List<String> wikiSources = java.util.Collections.emptyList();

		private ToolResult(String content, boolean error)
		{
			this.content = content;
			this.error = error;
		}

		static ToolResult ok(String content)
		{
			return new ToolResult(content, false);
		}

		static ToolResult error(String message)
		{
			return new ToolResult(message, true);
		}
	}

	/** Tokens a reply used, over all its requests (tool rounds included). */
	final class Usage
	{
		/** Input billed at the full price: not read from or written to the provider's prompt cache. */
		long input;
		long cacheRead;
		long cacheWrite;
		long output;

		void add(Usage u)
		{
			input += u.input;
			cacheRead += u.cacheRead;
			cacheWrite += u.cacheWrite;
			output += u.output;
		}
	}

	interface ModelsListener
	{
		void onModels(List<String> ids);

		void onError(String message);
	}

	/** One turn of the conversation as the provider sees it. */
	final class Turn
	{
		final boolean user;
		final String text;
		/**
		 * Cached-response compatibility: the messages this reply was made of (tool calls and their results included), exactly as
		 * exchanged. Sent back as they are only when {@link #rawKey} matches the request's {@link #promptKey}.
		 */
		JsonArray rawMessages;
		String rawKey;

		Turn(boolean user, String text)
		{
			this.user = user;
			this.text = text;
		}
	}

	final class Reply
	{
		String text;
		/** The model that answered, which can differ from the one asked for (e.g. a fallback). */
		String model;
		/** The reply hit the length limit. */
		boolean cutShort;
		/**
		 * Cached-response compatibility: earlier replies had to be sent as plain text for this one (see ConversationBuilder). They can't be
		 * sent any other way from now on, or this reply's reasoning wouldn't match what it was built on.
		 */
		boolean historyAsText;
		/** Cached-response compatibility: see {@link Turn#rawMessages}. */
		JsonArray rawMessages;
		/** The {@link #promptKey} of the requests that made this reply. */
		String rawKey;
		/** The request came with tools, but the model can't use them: it answered without looking anything up. */
		boolean toolsUnavailable;
		final Usage usage = new Usage();
	}

	interface Listener
	{
		/** The reply so far while it streams in: all of its text, not just the new part. On an OkHttp thread. */
		default void onPartial(String textSoFar)
		{
		}

		/**
		 * The provider is busy or couldn't be reached; the request is sent again in {@code seconds}. {@code message}
		 * says why, without a full stop: "ChatGPT is busy".
		 */
		default void onRetrying(String message, int seconds)
		{
		}

		void onReply(Reply reply);

		/** The request ended without a reply: why, for the panel, and what it used on the way. */
		void onError(Failure failure);
	}

	/** Why a request ended without a reply. */
	final class Failure
	{
		/** A short, readable explanation for the panel. */
		final String message;
		/** The tokens its requests used before it failed (look-up rounds, a reply cut off part way): billed all the same. */
		final Usage usage = new Usage();
		/** The model that answered before it failed, or null. */
		String model;
		/**
		 * The text shown so far is taken back rather than kept: the provider's safety filter stopped the reply part
		 * way, and what it had written isn't to be read as an answer.
		 */
		boolean withdrawn;
		/** The chat is longer than the model can take in: summarising its earlier messages may get it through. */
		boolean tooLong;

		Failure(String message)
		{
			this.message = message;
		}
	}

	/**
	 * What earlier replies were made under: the model, the instructions and the tools. Replies are sent back exactly as
	 * they came only while it's unchanged.
	 */
	static String promptKey(Conversation c)
	{
		StringBuilder key = new StringBuilder(c.model).append('\u0000').append(c.system);
		for (ToolSpec t : c.tools)
		{
			key.append('\u0000').append(t.name).append('\u0000').append(t.description).append('\u0000').append(t.inputSchema);
		}
		return key.toString();
	}

	/**
	 * A request in flight. A retry or the next tool round replaces the call (or the wait) inside, so cancelling always
	 * stops whatever is running.
	 */
	final class Pending
	{
		private volatile Call call;
		private volatile Future<?> timer;
		private volatile boolean cancelled;

		void set(Call c)
		{
			call = c;
			if (cancelled)
			{
				c.cancel();
			}
		}

		/** A wait before the next attempt. */
		void setTimer(Future<?> f)
		{
			timer = f;
			if (cancelled)
			{
				f.cancel(false);
			}
		}

		void cancel()
		{
			cancelled = true;
			Call c = call;
			if (c != null)
			{
				c.cancel();
			}
			Future<?> f = timer;
			if (f != null)
			{
				f.cancel(false);
			}
		}

		boolean isCancelled()
		{
			return cancelled;
		}
	}

	/**
	 * The error message inside a provider's error body, or null. Anthropic, OpenAI and most others send
	 * {"error":{"message":...}}; Gemini wraps that in a list, some local servers send {"error":"text"}, Mistral
	 * {"detail":...}. The body is read whatever its Content-Type says: OpenAI labels some JSON errors as plain text.
	 */
	static String errorMessage(Gson gson, String body)
	{
		try
		{
			JsonElement parsed = fromJson(gson, body, JsonElement.class);
			if (parsed != null && parsed.isJsonArray() && parsed.getAsJsonArray().size() > 0)
			{
				parsed = parsed.getAsJsonArray().get(0);
			}
			if (parsed == null || !parsed.isJsonObject())
			{
				return null;
			}
			JsonObject o = parsed.getAsJsonObject();
			JsonElement error = o.get("error");
			if (error != null && error.isJsonObject())
			{
				JsonElement message = error.getAsJsonObject().get("message");
				return message != null && message.isJsonPrimitive() ? message.getAsString() : null;
			}
			if (error != null && error.isJsonPrimitive())
			{
				return error.getAsString();
			}
			JsonElement detail = o.get("detail");
			if (detail != null && detail.isJsonPrimitive())
			{
				return detail.getAsString();
			}
			return null;
		}
		catch (JsonParseException | IllegalStateException | ClassCastException | UnsupportedOperationException e)
		{
			return null;
		}
	}

	/**
	 * A pasted API key without what copying tends to add: spaces, line breaks, non-breaking and zero-width spaces.
	 */
	static String cleanKey(String key)
	{
		return key == null ? "" : key.replaceAll("[\\s\\p{Z}\\p{Cf}]", "");
	}

	/** Whether a (cleaned) key can go in an HTTP header: printable ASCII only, so no curly quotes or ellipses. */
	static boolean sendableKey(String key)
	{
		for (int i = 0; i < key.length(); i++)
		{
			char c = key.charAt(i);
			if (c < 0x21 || c > 0x7e)
			{
				return false;
			}
		}
		return true;
	}

	/**
	 * The provider was reached but took too long to answer. A connect timeout is a different story: nothing answered.
	 */
	static boolean tookTooLong(IOException e)
	{
		if (!(e instanceof InterruptedIOException))
		{
			return false;
		}
		String m = e.getMessage();
		return m == null || !m.toLowerCase(Locale.ROOT).contains("connect");
	}

	/** Cut to a length that fits the panel. */
	static String shorten(String s, int max)
	{
		if (s == null)
		{
			return "";
		}
		String t = s.trim();
		return t.length() <= max ? t : t.substring(0, max).trim() + "...";
	}

	// ------------------------------------------------------------------
	// Shared by both providers: retries and tool rounds
	// ------------------------------------------------------------------

	/**
	 * Whether an HTTP error is worth sending the same request again for: a timeout, a conflict, a rate limit, or the
	 * provider being overloaded or briefly broken. Not a 429 that says the account is out of credits or over its
	 * quota: waiting won't fix that.
	 */
	static boolean retryableStatus(int code, String body)
	{
		switch (code)
		{
			case 429:
				return !outOfCredits(body);
			case 408:
			case 409:
			case 500:
			case 502:
			case 503:
			case 504:
			case 529:
				return true;
			default:
				return false;
		}
	}

	/**
	 * Whether an error answer says the conversation is longer than the model can take in. Each service words it its own
	 * way: Anthropic's "prompt is too long", OpenAI's and Groq's context_length_exceeded, "maximum context length"
	 * (OpenAI, OpenRouter, vLLM, Mistral), llama.cpp's "context size", Gemini's "exceeds the maximum number of tokens".
	 * {@code body}: the whole answer, which has the error's code as well as its message.
	 */
	static boolean tooLongForModel(int code, String body)
	{
		if (code != 400 && code != 413 && code != 422)
		{
			return false;
		}
		String lower = body == null ? "" : body.toLowerCase(Locale.ROOT);
		return code == 413 || lower.contains("prompt is too long") || lower.contains("context_length_exceeded")
			|| lower.contains("context length") || lower.contains("context window") || lower.contains("context size")
			|| lower.contains("context limit") || lower.contains("maximum context")
			|| lower.contains("exceeds the maximum number of tokens");
	}

	/** The error for a chat the model can't take in. {@code hint}: more to say about it, or "". */
	static Failure tooLong(String model, String hint)
	{
		Failure f = new Failure("This chat is too long for " + model + ". Start a new chat, or choose a model that "
			+ "can take more." + hint);
		f.tooLong = true;
		return f;
	}

	/**
	 * An error that says the account has run out of credits or hit a spending limit (OpenAI's insufficient_quota). Only
	 * words that say so count: Groq's per-minute rate limits end with a link to its billing page, and a rate limit's
	 * code says it is one.
	 */
	static boolean outOfCredits(String body)
	{
		String lower = body == null ? "" : body.toLowerCase(Locale.ROOT);
		if (lower.contains("rate_limit_exceeded"))
		{
			return false;
		}
		return lower.contains("insufficient_quota") || lower.contains("exceeded your current quota")
			|| lower.contains("credit balance") || lower.contains("insufficient credits") || lower.contains("out of credits")
			|| lower.contains("insufficient balance") || lower.contains("spend limit") || lower.contains("spending limit");
	}

	/**
	 * How long to wait before sending a request again, in milliseconds: what the provider asked for in a
	 * retry-after-ms or retry-after (seconds) header, or else 2 seconds before the first retry and 6 before the second.
	 * A retry-after given as a date is rare and ignored.
	 */
	static long retryDelay(String retryAfterMs, String retryAfter, int retry)
	{
		Double ms = number(retryAfterMs);
		if (ms != null)
		{
			return (long) Math.ceil(ms);
		}
		Double seconds = number(retryAfter);
		if (seconds != null)
		{
			return (long) Math.ceil(seconds * 1000);
		}
		return retry <= 1 ? 2000 : 6000;
	}

	/** A header's non-negative number, or null. */
	private static Double number(String header)
	{
		if (header == null)
		{
			return null;
		}
		try
		{
			double d = Double.parseDouble(header.trim());
			return Double.isNaN(d) || Double.isInfinite(d) || d < 0 ? null : d;
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}

	/** Whole seconds, rounded up, for "trying again in 6s". */
	static int seconds(long ms)
	{
		return (int) Math.min(Integer.MAX_VALUE, (ms + 999) / 1000);
	}

	/** "45 seconds", "3 minutes", "2 hours": a wait, for messages. */
	static String waitText(long ms)
	{
		long s = seconds(ms);
		if (s <= 90)
		{
			return s + (s == 1 ? " second" : " seconds");
		}
		long minutes = (s + 59) / 60;
		if (minutes < 120)
		{
			return minutes + " minutes";
		}
		return (minutes + 59) / 60 + " hours";
	}

	/**
	 * Runs {@code again} after {@code ms}, unless the request is cancelled first. The wait goes on the plugin's
	 * scheduler, never a sleeping thread, and Stop cancels it. False if the scheduler is shutting down.
	 */
	static boolean later(ScheduledExecutorService scheduler, Pending pending, long ms, Runnable again)
	{
		try
		{
			pending.setTimer(scheduler.schedule(() ->
			{
				if (!pending.isCancelled())
				{
					again.run();
				}
			}, ms, TimeUnit.MILLISECONDS));
			return true;
		}
		catch (RejectedExecutionException e)
		{
			return false;
		}
	}

	/**
	 * Runs the tool calls of one reply at the same time and hands back their results in the same order once all are
	 * in, on whichever thread finished last. {@code inputs}: null for a call whose arguments couldn't be read; it isn't
	 * run, and the model is told why. Calls beyond {@link #MAX_TOOL_CALLS} aren't run either. A runner that breaks its
	 * promise (throws, or answers twice) can't stall the reply.
	 */
	static void runTools(ToolRunner runner, List<String> names, List<JsonObject> inputs, Consumer<List<ToolResult>> done)
	{
		int n = names.size();
		ToolResult[] results = new ToolResult[n];
		AtomicInteger left = new AtomicInteger(n);
		if (n == 0)
		{
			done.accept(new ArrayList<>());
			return;
		}
		for (int i = 0; i < n; i++)
		{
			int slot = i;
			String name = names.get(i);
			AtomicBoolean answered = new AtomicBoolean();
			Consumer<ToolResult> one = r ->
			{
				if (!answered.compareAndSet(false, true))
				{
					return;
				}
				results[slot] = r != null ? r : ToolResult.error("The tool " + name + " sent nothing back.");
				// The last one in carries on; the counter makes every result visible to it.
				if (left.decrementAndGet() == 0)
				{
					List<ToolResult> all = new ArrayList<>();
					for (ToolResult result : results)
					{
						all.add(result);
					}
					done.accept(all);
				}
			};
			JsonObject input = inputs.get(i);
			if (input == null)
			{
				one.accept(ToolResult.error("Arguments weren't valid JSON. Send them as a JSON object."));
			}
			else if (i >= MAX_TOOL_CALLS)
			{
				one.accept(ToolResult.error("Too many look-ups at once: only the first " + MAX_TOOL_CALLS + " were run. "
					+ "Ask for fewer at a time."));
			}
			else if (runner == null)
			{
				one.accept(ToolResult.error("There's no tool called " + name + "."));
			}
			else
			{
				try
				{
					runner.run(name, input, one);
				}
				catch (RuntimeException e)
				{
					one.accept(ToolResult.error("The tool " + name + " failed. Answer without it."));
				}
			}
		}
	}

	/**
	 * Gson's reading of text from a provider or a model. Text nested deeper than {@link #MAX_JSON_DEPTH} is refused
	 * like any other bad JSON, before Gson can overflow the stack on it: a {@link StackOverflowError} would escape every
	 * handler, and the reply would never end.
	 */
	static <T> T fromJson(Gson gson, String json, Class<T> type)
	{
		if (json != null && tooDeep(json))
		{
			throw new JsonParseException("Nested too deep to read");
		}
		return gson.fromJson(json, type);
	}

	/**
	 * Whether {@code json} might nest arrays and objects deeper than {@link #MAX_JSON_DEPTH}. Brackets inside strings
	 * don't count. Gson also reads text that isn't plain JSON (single quotes, bare words, comments), and in that the
	 * strings can't be told apart this simply: there every bracket counts.
	 */
	static boolean tooDeep(String json)
	{
		int depth = 0;
		boolean inString = false;
		boolean plain = true;
		for (int i = 0; i < json.length() && plain; i++)
		{
			char c = json.charAt(i);
			if (inString)
			{
				if (c == '\\')
				{
					i++;
				}
				else if (c == '"')
				{
					inString = false;
				}
			}
			else if (c == '"')
			{
				// Straight after a bare word, Gson reads a quote as more of the word, not as the start of a string.
				plain = i == 0 || !bareWord(json.charAt(i - 1));
				inString = true;
			}
			else if (c == '[' || c == '{')
			{
				if (++depth > MAX_JSON_DEPTH)
				{
					return true;
				}
			}
			else if (c == ']' || c == '}')
			{
				depth--;
			}
			else
			{
				plain = bareWord(c) || c == ',' || c == ':' || c == ' ' || c == '\t' || c == '\n' || c == '\r';
			}
		}
		if (plain)
		{
			return false;
		}
		int brackets = 0;
		for (int i = 0; i < json.length(); i++)
		{
			char c = json.charAt(i);
			if ((c == '[' || c == '{') && ++brackets > MAX_JSON_DEPTH)
			{
				return true;
			}
		}
		return false;
	}

	/** What plain JSON has outside strings besides punctuation: numbers, true, false and null. */
	private static boolean bareWord(char c)
	{
		return c >= '0' && c <= '9' || "+-.eEtrufalsn".indexOf(c) >= 0;
	}

	/** The text of a reply made in several rounds (it stopped to look things up): each round's text, in order. */
	static String joinRounds(List<String> rounds, String current)
	{
		StringBuilder sb = new StringBuilder();
		for (String r : rounds)
		{
			append(sb, r);
		}
		append(sb, current);
		return sb.toString();
	}

	private static void append(StringBuilder sb, String text)
	{
		String t = text == null ? "" : text.trim();
		if (!t.isEmpty())
		{
			sb.append(sb.length() > 0 ? "\n\n" : "").append(t);
		}
	}
}
