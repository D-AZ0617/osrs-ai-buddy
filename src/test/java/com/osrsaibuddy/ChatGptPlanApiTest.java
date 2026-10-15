package com.osrsaibuddy;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.OkHttpClient;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/** All inference is served on loopback, never by a real provider or paid API. */
public class ChatGptPlanApiTest
{
	private StandIn server;
	private final ExecutorService executor = Executors.newSingleThreadExecutor();
	private final Gson gson = new Gson();
	private final OkHttpClient http = new OkHttpClient();
	@Before public void start() throws Exception { server = new StandIn(); }
	@After public void stop() { server.stop(); executor.shutdownNow(); }
	private ChatGptPlanApi api() { return new ChatGptPlanApi(http, gson, () -> "test-oauth-token", executor, server.url("/v1/")); }
	private static String event(String value) { return "data: " + value + "\n\n"; }
	private static String completed(String text)
	{
		return event("{\"type\":\"response.completed\",\"response\":{\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"" + text + "\"}]}],\"usage\":{\"input_tokens\":20,\"input_tokens_details\":{\"cached_tokens\":5},\"output_tokens\":4}}}");
	}
	private StandIn.Heard send(ChatApi.Conversation c)
	{
		StandIn.Heard heard = new StandIn.Heard();
		api().send(c, heard);
		try { heard.await(); } catch (InterruptedException e) { throw new AssertionError(e); }
		return heard;
	}
	@Test public void usesOAuthResponsesContractAndWaitsForCompletion()
	{
		server.answer("/v1/responses", StandIn.events(event("{\"type\":\"response.output_text.delta\",\"delta\":\"Hi\"}"), completed("Hi")));
		ChatApi.Conversation c = StandIn.conversation("account-model", "Hello");
		c.maxTokens = 100;
		StandIn.Heard heard = send(c);
		assertNull(heard.error);
		assertEquals("Hi", heard.reply.text);
		assertEquals(List.of("Hi"), heard.partials);
		assertEquals(15, heard.reply.usage.input);
		assertEquals(5, heard.reply.usage.cacheRead);
		JsonObject sent = server.bodies.get(0);
		assertFalse(sent.get("store").getAsBoolean());
		assertTrue(sent.get("stream").getAsBoolean());
		assertTrue(sent.get("input").isJsonArray());
		assertFalse(sent.has("max_output_tokens"));
		assertFalse(sent.has("previous_response_id"));
		assertEquals("Be brief.", sent.get("instructions").getAsString());
		assertEquals("Bearer test-oauth-token", server.headers.get(0).getFirst("Authorization"));
	}
	@Test public void partialTextWithoutCompletedIsNotSuccess()
	{
		server.answer("/v1/responses", StandIn.events(event("{\"type\":\"response.output_text.delta\",\"delta\":\"Partial\"}")));
		StandIn.Heard heard = send(StandIn.conversation("m", "q"));
		assertNull(heard.reply);
		assertNotNull(heard.error);
		assertEquals(1, heard.answers.get());
	}
	@Test public void headerlessStreamKeepsItsFirstEventAndCompletes()
	{
		server.answer("/v1/responses", new StandIn.Answer(200,
			event("{\"type\":\"response.output_text.delta\",\"delta\":\"Hi\"}") + completed("Hi"), null));
		StandIn.Heard heard = send(StandIn.conversation("m", "q"));
		assertNull(heard.error);
		assertEquals("Hi", heard.reply.text);
		assertEquals(List.of("Hi"), heard.partials);
	}
	@Test public void headerlessStreamUsesFinalItemsWhenTerminalOutputIsEmpty()
	{
		String item = "{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"Hi\"}]}";
		server.answer("/v1/responses", new StandIn.Answer(200,
			event("{\"type\":\"response.output_text.delta\",\"delta\":\"Hi\"}")
			+ event("{\"type\":\"response.output_item.done\",\"output_index\":0,\"item\":" + item + "}")
			+ event("{\"type\":\"response.completed\",\"response\":{\"output\":[]}}"), null));
		StandIn.Heard heard = send(StandIn.conversation("m", "q"));
		assertNull(heard.error);
		assertEquals("Hi", heard.reply.text);
		assertEquals(List.of("Hi"), heard.partials);
	}
	@Test public void finalItemsWithoutTerminalCompletionStillFail()
	{
		String item = "{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"Hi\"}]}";
		server.answer("/v1/responses", new StandIn.Answer(200,
			event("{\"type\":\"response.output_item.done\",\"output_index\":0,\"item\":" + item + "}"), null));
		StandIn.Heard heard = send(StandIn.conversation("m", "q"));
		assertNull(heard.reply);
		assertEquals(ChatApi.CUT_OFF, heard.error);
	}
	@Test public void headerlessPartialStreamStillFailsWithoutCompletion()
	{
		server.answer("/v1/responses", new StandIn.Answer(200,
			event("{\"type\":\"response.output_text.delta\",\"delta\":\"Partial\"}"), null));
		StandIn.Heard heard = send(StandIn.conversation("m", "q"));
		assertNull(heard.reply);
		assertEquals(ChatApi.CUT_OFF, heard.error);
	}
	@Test public void headerlessJsonIsNotMistakenForStreamingSuccess()
	{
		server.answer("/v1/responses", new StandIn.Answer(200, "{\"status\":\"completed\",\"output\":[]}", null));
		StandIn.Heard heard = send(StandIn.conversation("m", "q"));
		assertNull(heard.reply);
		assertTrue(heard.error.contains("unexpected response format"));
	}
	@Test public void headerlessStreamCanStartWithBomAndKeepalive()
	{
		server.answer("/v1/responses", new StandIn.Answer(200, "\uFEFF: keepalive\r\n\r\n" + completed("Hi"), null));
		StandIn.Heard heard = send(StandIn.conversation("m", "q"));
		assertNull(heard.error);
		assertEquals("Hi", heard.reply.text);
	}
	@Test public void lateUsageFailureDoesNotBecomeSuccessOrApiFallback()
	{
		server.answer("/v1/responses", StandIn.events(event("{\"type\":\"response.output_text.delta\",\"delta\":\"Partial\"}"),
			event("{\"type\":\"response.failed\",\"response\":{\"error\":{\"code\":\"subscription_sharing_usage_limit_exceeded\"}}}")));
		StandIn.Heard heard = send(StandIn.conversation("m", "q"));
		assertNull(heard.reply);
		assertTrue(heard.error.contains("No API-key fallback"));
		assertEquals(1, server.bodies.size());
	}
	@Test public void permissionFailureDoesNotRetryWithPaidCredentials()
	{
		server.answer("/v1/responses", StandIn.json(403, "{}"));
		StandIn.Heard heard = send(StandIn.conversation("m", "q"));
		assertNull(heard.reply);
		assertTrue(heard.error.contains("isn't available"));
		assertEquals(1, server.bodies.size());
	}
	@Test public void listsOnlyVisibleAccountModelsInServerOrder() throws Exception
	{
		server.answer("/v1/models", StandIn.json(200, "{\"models\":[{\"slug\":\"second\",\"visibility\":\"list\"},{\"slug\":\"hidden\",\"visibility\":\"hide\"},{\"slug\":\"first\",\"visibility\":\"list\"}]}"));
		CompletableFuture<List<String>> result = new CompletableFuture<>();
		api().listModels(new ChatApi.ModelsListener() {
			public void onModels(List<String> ids) { result.complete(ids); }
			public void onError(String message) { result.completeExceptionally(new IOException(message)); }
		});
		assertEquals(List.of("second", "first"), result.get(5, TimeUnit.SECONDS));
	}
	@Test public void namespacedToolsKeepThePrivacyGateAndCarryResults()
	{
		String call = "{\"type\":\"function_call\",\"namespace\":\"osrs\",\"name\":\"wiki_search\",\"arguments\":\"{\\\"query\\\":\\\"Vorkath\\\"}\",\"call_id\":\"call1\"}";
		server.answer("/v1/responses", StandIn.events(event("{\"type\":\"response.completed\",\"response\":{\"output\":[" + call + "]}}")), StandIn.events(completed("Found it")));
		ChatApi.Conversation c = StandIn.conversation("m", "q");
		c.tools.add(StandIn.tool("wiki_search"));
		AtomicInteger tools = new AtomicInteger();
		c.toolRunner = (name, arguments, done) -> { tools.incrementAndGet(); done.accept(ChatApi.ToolResult.ok("Wiki result")); };
		StandIn.Heard heard = send(c);
		assertNull(heard.error);
		assertEquals("Found it", heard.reply.text);
		assertEquals(1, tools.get());
		assertEquals("namespace", server.bodies.get(0).getAsJsonArray("tools").get(0).getAsJsonObject().get("type").getAsString());
		assertTrue(server.bodies.get(1).get("input").toString().contains("function_call_output"));
		assertTrue(server.bodies.get(1).get("input").toString().contains("Wiki result"));
	}
	@Test public void neverExecutesAnUnadvertisedAccountTool()
	{
		server.answer("/v1/responses", StandIn.events(event("{\"type\":\"response.completed\",\"response\":{\"output\":[{\"type\":\"function_call\",\"name\":\"get_bank\",\"arguments\":\"{}\",\"call_id\":\"c\"}]}}")));
		ChatApi.Conversation c = StandIn.conversation("m", "q");
		c.toolRunner = (name, args, done) -> fail("Must not share bank");
		assertNotNull(send(c).error);
		assertEquals(1, server.bodies.size());
	}
	@Test public void cancelledBeforeTokenLookupSendsNothing() throws Exception
	{
		java.util.concurrent.CountDownLatch block = new java.util.concurrent.CountDownLatch(1);
		executor.submit(() -> StandIn.await(block));
		StandIn.Heard heard = new StandIn.Heard();
		ChatApi.Pending pending = api().send(StandIn.conversation("m", "q"), heard);
		pending.cancel();
		block.countDown();
		executor.submit(() -> { }).get(5, TimeUnit.SECONDS);
		assertTrue(server.bodies.isEmpty());
		assertEquals(0, heard.answers.get());
	}
}

