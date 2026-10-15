package com.osrsaibuddy;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * What both providers share: keys, retries and tool rounds. The providers themselves are tested against a stand-in
 * server in {@link ChatGptPlanApiTest}.
 */
public class ChatApiTest
{
	@Test
	public void pastedKeysAreCleanedAndChecked()
	{
		assertEquals("sk-ant-abc", ChatApi.cleanKey(" sk-ant-​abc \n"));
		assertTrue(ChatApi.sendableKey(ChatApi.cleanKey(" sk-ant-abc ")));
		assertFalse(ChatApi.sendableKey(ChatApi.cleanKey("sk-ant-abc…")));
		assertFalse(ChatApi.sendableKey(ChatApi.cleanKey("“sk-ant-abc”")));
	}

	@Test
	public void retriesWaitAsLongAsTheProviderAsks()
	{
		assertEquals(2000, ChatApi.retryDelay(null, null, 1));
		assertEquals(6000, ChatApi.retryDelay(null, null, 2));
		assertEquals(0, ChatApi.retryDelay(null, "0", 1));
		assertEquals(3000, ChatApi.retryDelay(null, "3", 1));
		assertEquals(1500, ChatApi.retryDelay(null, "1.5", 2));
		assertEquals("milliseconds first", 1250, ChatApi.retryDelay("1250", "30", 1));
		assertEquals(120_000, ChatApi.retryDelay(null, "120", 1));
		// A date can't be read here: the usual waits instead.
		assertEquals(2000, ChatApi.retryDelay(null, "Wed, 21 Oct 2026 07:28:00 GMT", 1));
		assertEquals(6000, ChatApi.retryDelay("soon", "-5", 2));
	}

	@Test
	public void onlyPassingProblemsAreRetried()
	{
		for (int code : new int[]{408, 409, 429, 500, 502, 503, 504, 529})
		{
			assertTrue(String.valueOf(code), ChatApi.retryableStatus(code, "{\"error\":{\"message\":\"busy\"}}"));
		}
		for (int code : new int[]{400, 401, 402, 403, 404, 413, 422})
		{
			assertFalse(String.valueOf(code), ChatApi.retryableStatus(code, "{}"));
		}
		assertFalse(ChatApi.retryableStatus(429, "{\"error\":{\"message\":\"You exceeded your current quota\",\"type\":\"insufficient_quota\"}}"));
		assertFalse(ChatApi.retryableStatus(429, "{\"error\":{\"message\":\"Your credit balance is too low\"}}"));
		assertFalse(ChatApi.retryableStatus(429, "{\"error\":{\"message\":\"Monthly spend limit reached\"}}"));
		// Groq's limit per minute links to its billing page: a short wait still clears it.
		assertTrue(ChatApi.retryableStatus(429, GROQ_RATE_LIMIT));
	}

	static final String GROQ_RATE_LIMIT = "{\"error\":{\"message\":\"Rate limit reached for model `llama-3.3-70b-versatile` in "
		+ "organization `org_1` service tier `on_demand` on tokens per minute (TPM): Limit 12000, Used 11000, Requested 2000. "
		+ "Please try again in 1.5s. Need more tokens? Upgrade to Dev Tier today at https://console.groq.com/settings/billing\","
		+ "\"type\":\"tokens\",\"code\":\"rate_limit_exceeded\"}}";

	@Test
	public void waitsAreWrittenForPeople()
	{
		assertEquals(1, ChatApi.seconds(1));
		assertEquals(0, ChatApi.seconds(0));
		assertEquals(6, ChatApi.seconds(6000));
		assertEquals("1 second", ChatApi.waitText(1000));
		assertEquals("90 seconds", ChatApi.waitText(90_000));
		assertEquals("2 minutes", ChatApi.waitText(91_000));
		assertEquals("5 minutes", ChatApi.waitText(300_000));
		assertEquals("2 hours", ChatApi.waitText(7_200_000));
	}

	@Test
	public void thePromptKeyCoversModelInstructionsAndTools()
	{
		ChatApi.Conversation c = StandIn.conversation("claude-opus-5-5", "hi");
		String key = ChatApi.promptKey(c);
		c.turns.add(new ChatApi.Turn(false, "the turns don't count"));
		c.maxTokens = 100;
		assertEquals(key, ChatApi.promptKey(c));
		c.tools.add(StandIn.tool("wiki_search"));
		String withTool = ChatApi.promptKey(c);
		assertNotEquals(key, withTool);
		c.tools.clear();
		c.tools.add(StandIn.tool("ge_price"));
		assertNotEquals(withTool, ChatApi.promptKey(c));
		c.tools.clear();
		c.system = "Other";
		assertNotEquals(key, ChatApi.promptKey(c));
	}

	@Test
	public void toolsRunTogetherAndAnswerInOrderWhateverHappens() throws Exception
	{
		CountDownLatch done = new CountDownLatch(1);
		AtomicReference<List<ChatApi.ToolResult>> results = new AtomicReference<>();
		List<String> ran = new ArrayList<>();
		CountDownLatch secondIn = new CountDownLatch(1);
		ChatApi.ToolRunner runner = (name, input, answer) ->
		{
			ran.add(name);
			switch (name)
			{
				case "slow":
					// Answers on another thread, after the one asked for after it.
					new Thread(() ->
					{
						StandIn.await(secondIn);
						answer.accept(ChatApi.ToolResult.ok("slow " + input.get("n")));
					}).start();
					break;
				case "fast":
					answer.accept(ChatApi.ToolResult.ok("fast"));
					// A second answer is ignored.
					answer.accept(ChatApi.ToolResult.error("again"));
					secondIn.countDown();
					break;
				default:
					throw new IllegalStateException("broken tool");
			}
		};
		JsonObject n = new JsonObject();
		n.addProperty("n", 1);
		ChatApi.runTools(runner, Arrays.asList("slow", "fast", "broken", "unread"), Arrays.asList(n, new JsonObject(), new JsonObject(), null), r ->
		{
			results.set(r);
			done.countDown();
		});
		assertTrue(done.await(10, TimeUnit.SECONDS));
		List<ChatApi.ToolResult> r = results.get();
		assertEquals("unreadable arguments aren't run", List.of("slow", "fast", "broken"), ran);
		assertEquals("slow 1", r.get(0).content);
		assertEquals("fast", r.get(1).content);
		assertFalse(r.get(1).error);
		assertTrue(r.get(2).error);
		assertTrue(r.get(2).content.contains("broken"));
		assertTrue(r.get(3).error);
		assertTrue(r.get(3).content.startsWith("Arguments weren't valid JSON"));

		// No runner (a request without tools that got a call anyway), and no calls at all.
		AtomicReference<List<ChatApi.ToolResult>> none = new AtomicReference<>();
		ChatApi.runTools(null, List.of("wiki_search"), List.of(new JsonObject()), none::set);
		assertTrue(none.get().get(0).error);
		ChatApi.runTools(runner, List.of(), List.of(), none::set);
		assertTrue(none.get().isEmpty());
	}

	@Test
	public void onlySoManyToolCallsRunAtOnce()
	{
		List<String> ran = new CopyOnWriteArrayList<>();
		List<String> names = new ArrayList<>();
		List<JsonObject> inputs = new ArrayList<>();
		for (int i = 0; i < ChatApi.MAX_TOOL_CALLS + 2; i++)
		{
			names.add("wiki_page " + i);
			inputs.add(new JsonObject());
		}
		AtomicReference<List<ChatApi.ToolResult>> results = new AtomicReference<>();
		ChatApi.runTools((name, input, done) ->
		{
			ran.add(name);
			done.accept(ChatApi.ToolResult.ok("read"));
		}, names, inputs, results::set);
		assertEquals(names.subList(0, ChatApi.MAX_TOOL_CALLS), ran);
		assertEquals(names.size(), results.get().size());
		assertFalse(results.get().get(ChatApi.MAX_TOOL_CALLS - 1).error);
		ChatApi.ToolResult turnedDown = results.get().get(ChatApi.MAX_TOOL_CALLS);
		assertTrue(turnedDown.error);
		assertEquals("Too many look-ups at once: only the first 10 were run. Ask for fewer at a time.", turnedDown.content);
	}

	@Test
	public void onlyATimeoutAfterConnectingIsTakingTooLong()
	{
		// The provider had the request: it may be answering it, and billing it.
		assertTrue(ChatApi.tookTooLong(new SocketTimeoutException("timeout")));
		assertTrue(ChatApi.tookTooLong(new SocketTimeoutException("Read timed out")));
		assertTrue(ChatApi.tookTooLong(new InterruptedIOException("timeout")));
		assertTrue(ChatApi.tookTooLong(new SocketTimeoutException()));
		// Nothing answered at all.
		assertFalse(ChatApi.tookTooLong(new SocketTimeoutException("connect timed out")));
		assertFalse(ChatApi.tookTooLong(new SocketTimeoutException("failed to connect to /10.0.0.1 (port 443) after 10000ms")));
		assertFalse(ChatApi.tookTooLong(new IOException("timeout")));
	}

	@Test
	public void jsonNestedDeeperThanAnswersNeedIsCaught()
	{
		String deep = "[".repeat(ChatApi.MAX_JSON_DEPTH + 1);
		assertFalse(ChatApi.tooDeep("{\"query\":\"[[[[\",\"n\":[1,[2.5e-3]],\"ok\":true}"));
		assertFalse(ChatApi.tooDeep("[".repeat(ChatApi.MAX_JSON_DEPTH) + "]".repeat(ChatApi.MAX_JSON_DEPTH)));
		assertTrue(ChatApi.tooDeep(deep));
		assertFalse("brackets in a string, after an escaped quote", ChatApi.tooDeep("{\"q\":\"\\\"" + "[".repeat(100) + "\"}"));
		try
		{
			ChatApi.fromJson(new Gson(), "{\"a\":" + deep + "]".repeat(ChatApi.MAX_JSON_DEPTH + 1) + "}", JsonObject.class);
			fail("read");
		}
		catch (JsonParseException e)
		{
			// Refused like any other bad JSON.
		}

		// Gson also reads JSON that isn't plain, where a quote may not start a string: what follows still counts.
		assertTrue("a quote in a bare word", ChatApi.tooDeep("[a\"," + deep));
		assertTrue("single quotes", ChatApi.tooDeep("['\"'," + deep));
		assertTrue("a comment", ChatApi.tooDeep("/* ,\" */" + deep));
		assertTrue("the prefix Gson skips", ChatApi.tooDeep(")]}'\n" + deep));
		assertFalse("a few brackets in JSON that isn't plain", ChatApi.tooDeep("{query: 'x', n: [1]}"));
	}

	@Test
	public void roundsAreJoinedIntoOneReply()
	{
		assertEquals("Let me check.\n\nIt's 1.5m.", ChatApi.joinRounds(List.of(" Let me check. ", ""), "It's 1.5m.\n"));
		assertEquals("Only", ChatApi.joinRounds(List.of(), "Only"));
		assertEquals("", ChatApi.joinRounds(List.of(""), " "));
	}
}

