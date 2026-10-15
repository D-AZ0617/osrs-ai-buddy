package com.osrsaibuddy;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import okhttp3.HttpUrl;
import static org.junit.Assert.assertTrue;

/**
 * A stand-in for a provider's API on 127.0.0.1, for the provider tests: canned answers by path, served in order (the
 * last one repeats), and a record of every request. No real API is ever called.
 */
final class StandIn
{
	/** One canned answer: a status, a body and its type, and any extra headers. */
	static final class Answer
	{
		final int code;
		final String body;
		final String type;
		final Map<String, String> headers = new LinkedHashMap<>();

		Answer(int code, String body, String type)
		{
			this.code = code;
			this.body = body;
			this.type = type;
		}

		Answer header(String name, String value)
		{
			headers.put(name, value);
			return this;
		}
	}

	/**
	 * Waits at most 10 seconds for {@code latch}: how a stand-in holds an answer back until the test lets it go. Like the
	 * plugin, the tests never sleep or interrupt a thread.
	 */
	static void await(CountDownLatch latch)
	{
		try
		{
			latch.await(10, TimeUnit.SECONDS);
		}
		catch (InterruptedException e)
		{
			// The stand-in is shutting down: the answer goes nowhere anyway.
		}
	}

	static Answer json(int code, String body)
	{
		return new Answer(code, body, "application/json");
	}

	/** A streamed answer: the events, each already written out with its blank line. */
	static Answer events(String... events)
	{
		return new Answer(200, String.join("", events), "text/event-stream");
	}

	private final Gson gson = new Gson();
	private final HttpServer server;
	private final ExecutorService threads = Executors.newCachedThreadPool();
	private final Map<String, List<Answer>> answers = new ConcurrentHashMap<>();
	/** The JSON bodies of the requests, in order (null for one without a body). */
	final List<JsonObject> bodies = new CopyOnWriteArrayList<>();
	final List<Headers> headers = new CopyOnWriteArrayList<>();
	final List<URI> uris = new CopyOnWriteArrayList<>();

	StandIn() throws IOException
	{
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.setExecutor(threads);
		server.createContext("/", exchange ->
		{
			String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			bodies.add(body.isEmpty() ? null : gson.fromJson(body, JsonObject.class));
			headers.add(exchange.getRequestHeaders());
			uris.add(exchange.getRequestURI());
			List<Answer> queue = answers.getOrDefault(exchange.getRequestURI().getPath(), Collections.singletonList(json(404, "{}")));
			Answer answer = queue.size() > 1 ? queue.remove(0) : queue.get(0);
			byte[] out = answer.body.getBytes(StandardCharsets.UTF_8);
			if (answer.type != null) { exchange.getResponseHeaders().set("Content-Type", answer.type); }
			answer.headers.forEach((k, v) -> exchange.getResponseHeaders().set(k, v));
			exchange.sendResponseHeaders(answer.code, out.length);
			try (OutputStream os = exchange.getResponseBody())
			{
				os.write(out);
			}
		});
		server.start();
	}

	void stop()
	{
		server.stop(0);
		threads.shutdownNow();
	}

	HttpServer server()
	{
		return server;
	}

	HttpUrl url(String path)
	{
		return HttpUrl.get("http://127.0.0.1:" + server.getAddress().getPort() + path);
	}

	void answer(String path, Answer... queue)
	{
		answers.put(path, Collections.synchronizedList(new ArrayList<>(Arrays.asList(queue))));
	}

	void clear()
	{
		bodies.clear();
		headers.clear();
		uris.clear();
	}

	static ChatApi.Conversation conversation(String model, String... texts)
	{
		ChatApi.Conversation c = new ChatApi.Conversation();
		c.model = model;
		c.system = "Be brief.";
		for (int i = 0; i < texts.length; i++)
		{
			c.turns.add(new ChatApi.Turn(i % 2 == 0, texts[i]));
		}
		return c;
	}

	static ChatApi.ToolSpec tool(String name)
	{
		return new ChatApi.ToolSpec(name, "Looks up " + name + ".", new Gson().fromJson(
			"{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}},\"required\":[\"query\"]}", JsonObject.class));
	}

	/** All the tokens a request used, however they were billed. */
	static long tokens(ChatApi.Usage u)
	{
		return u.input + u.cacheRead + u.cacheWrite + u.output;
	}

	/** Everything a request told its listener. */
	static final class Heard implements ChatApi.Listener
	{
		final List<String> partials = new CopyOnWriteArrayList<>();
		/** "why seconds" for each retry. */
		final List<String> retries = new CopyOnWriteArrayList<>();
		final AtomicInteger answers = new AtomicInteger();
		volatile ChatApi.Reply reply;
		volatile String error;
		volatile ChatApi.Failure failure;
		private final CountDownLatch done = new CountDownLatch(1);
		private final CountDownLatch retrying = new CountDownLatch(1);

		@Override
		public void onPartial(String textSoFar)
		{
			partials.add(textSoFar);
		}

		@Override
		public void onRetrying(String message, int seconds)
		{
			retries.add(message + " " + seconds);
			retrying.countDown();
		}

		@Override
		public void onReply(ChatApi.Reply r)
		{
			reply = r;
			answers.incrementAndGet();
			done.countDown();
		}

		@Override
		public void onError(ChatApi.Failure f)
		{
			failure = f;
			error = f.message;
			answers.incrementAndGet();
			done.countDown();
		}

		/** Waits for the answer. */
		Heard await() throws InterruptedException
		{
			assertTrue("no answer", done.await(10, TimeUnit.SECONDS));
			return this;
		}

		/** Waits for the reply, failing with the error if there was one instead. */
		ChatApi.Reply reply() throws InterruptedException
		{
			await();
			assertTrue("error instead of a reply: " + error, reply != null);
			return reply;
		}

		String error() throws InterruptedException
		{
			await();
			assertTrue("a reply instead of an error: " + (reply == null ? null : reply.text), error != null);
			return error;
		}

		boolean answeredWithin(long ms) throws InterruptedException
		{
			return done.await(ms, TimeUnit.MILLISECONDS);
		}

		void awaitRetrying() throws InterruptedException
		{
			assertTrue("no retry", retrying.await(10, TimeUnit.SECONDS));
		}
	}

	/** Tool runs, recorded: "name {input}". Answers at once, or as {@code answer} decides. */
	static final class Tools implements ChatApi.ToolRunner
	{
		final List<String> calls = new CopyOnWriteArrayList<>();
		private final Map<String, Consumer<Consumer<ChatApi.ToolResult>>> special = new ConcurrentHashMap<>();

		/** How a tool answers instead of at once with "result of name". */
		Tools on(String name, Consumer<Consumer<ChatApi.ToolResult>> answer)
		{
			special.put(name, answer);
			return this;
		}

		@Override
		public void run(String name, JsonObject input, Consumer<ChatApi.ToolResult> done)
		{
			calls.add(name + " " + input);
			Consumer<Consumer<ChatApi.ToolResult>> answer = special.get(name);
			if (answer != null)
			{
				answer.accept(done);
			}
			else
			{
				done.accept(ChatApi.ToolResult.ok("result of " + name));
			}
		}
	}
}

