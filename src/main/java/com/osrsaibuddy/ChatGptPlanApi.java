package com.osrsaibuddy;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Responses over OAuth, strictly separate from the API-key provider. Never falls back to paid API billing. */
final class ChatGptPlanApi implements ChatApi
{
	interface Token { String get() throws IOException; }
	private final OkHttpClient http;
	private final Gson gson;
	private final Token token;
	private final Executor executor;
	private final HttpUrl base;

	ChatGptPlanApi(OkHttpClient http, Gson gson, Token token, Executor executor)
	{
		this(http, gson, token, executor, okhttp3.HttpUrl.get("https://api.openai.com/v1/"));
	}

	/** Endpoint injection is package-private for local stand-in tests only. */
	ChatGptPlanApi(OkHttpClient http, Gson gson, Token token, Executor executor, HttpUrl base)
	{
		this.http = http;
		this.gson = gson;
		this.token = token;
		this.executor = executor;
		this.base = base;
	}

	@Override public String displayName() { return "ChatGPT (using your plan)"; }

	@Override public Pending listModels(ModelsListener listener)
	{
		Pending pending = new Pending();
		executor.execute(() -> {
			try
			{
				if (pending.isCancelled()) { return; }
				Call call = http.newCall(new Request.Builder().url(base.resolve("models"))
					.header("Authorization", "Bearer " + token.get()).build());
				pending.set(call);
				try (Response response = call.execute())
				{
					if (pending.isCancelled()) { return; }
					if (!response.isSuccessful()) { listener.onError(httpError(response.code())); return; }
					JsonObject body = ChatApi.fromJson(gson, response.body().string(), JsonObject.class);
					List<String> models = new ArrayList<>();
					if (body.has("models"))
					{
						for (JsonElement e : body.getAsJsonArray("models"))
						{
							JsonObject model = e.getAsJsonObject();
							if ("list".equals(ChatGptAuth.text(model, "visibility")) && !ChatGptAuth.text(model, "slug").isEmpty())
							{ models.add(ChatGptAuth.text(model, "slug")); }
						}
					}
					listener.onModels(models);
				}
			}
			catch (Exception e) { if (!pending.isCancelled()) { listener.onError("Couldn't load your ChatGPT plan models. Check your connection or sign in again."); } }
		});
		return pending;
	}

	@Override public Pending send(Conversation conversation, Listener listener)
	{
		Run run = new Run(conversation, listener);
		run.request();
		return run.pending;
	}

	static JsonObject body(Conversation conversation, JsonArray produced)
	{
		JsonObject body = new JsonObject();
		body.addProperty("model", conversation.model);
		body.addProperty("instructions", conversation.system);
		body.addProperty("store", false);
		body.addProperty("stream", true);
		JsonArray include = new JsonArray();
		include.add("reasoning.encrypted_content");
		body.add("include", include);
		JsonArray input = new JsonArray();
		for (Turn turn : conversation.turns)
		{
			JsonObject message = new JsonObject();
			message.addProperty("role", turn.user ? "user" : "assistant");
			message.addProperty("content", turn.text);
			input.add(message);
		}
		input.addAll(produced);
		body.add("input", input);
		if (!conversation.tools.isEmpty())
		{
			JsonArray functions = new JsonArray();
			for (ToolSpec spec : conversation.tools)
			{
				JsonObject function = new JsonObject();
				function.addProperty("type", "function");
				function.addProperty("name", spec.name);
				function.addProperty("description", spec.description);
				function.addProperty("strict", false);
				function.add("parameters", spec.inputSchema);
				functions.add(function);
			}
			JsonObject namespace = new JsonObject();
			namespace.addProperty("type", "namespace");
			namespace.addProperty("name", "osrs");
			namespace.addProperty("description", "OSRS look-ups allowed by the player's sharing settings.");
			namespace.add("tools", functions);
			JsonArray tools = new JsonArray();
			tools.add(namespace);
			body.add("tools", tools);
		}
		return body;
	}

	static String httpError(int status)
	{
		if (status == 401) { return "Your ChatGPT session needs sign-in again. Use Continue with ChatGPT in the menu."; }
		if (status == 403) { return "ChatGPT plan access isn't available or permitted for this account. Review access in ChatGPT Settings."; }
		if (status == 429) { return "Your ChatGPT plan limit was reached, or the service is busy. Check Manage ChatGPT usage and try later."; }
		return "ChatGPT plan request failed (HTTP " + status + "). No API-key fallback was used.";
	}

	private final class Run
	{
		final Conversation conversation;
		final Listener listener;
		final Pending pending = new Pending();
		final AtomicBoolean finished = new AtomicBoolean();
		final JsonArray produced = new JsonArray();
		final Usage usage = new Usage();
		String text = "";
		int rounds;
		int calls;

		Run(Conversation conversation, Listener listener) { this.conversation = conversation; this.listener = listener; }
		boolean stopped() { return pending.isCancelled() || finished.get(); }
		void fail(String message)
		{
			if (!pending.isCancelled() && finished.compareAndSet(false, true))
			{
				Failure failure = new Failure(message);
				failure.model = conversation.model;
				failure.usage.add(usage);
				listener.onError(failure);
			}
		}

		void request()
		{
			executor.execute(() -> {
				if (stopped()) { return; }
				try
				{
					String access = token.get();
					if (stopped()) { return; }
					Call call = http.newCall(new Request.Builder().url(base.resolve("responses"))
						.header("Authorization", "Bearer " + access).header("Accept", "text/event-stream")
						.post(RequestBody.create(JSON, gson.toJson(body(conversation, produced)))).build());
					pending.set(call);
					call.enqueue(new Callback() {
						@Override public void onFailure(Call c, IOException e) { fail("Couldn't reach ChatGPT. Check your connection and try again."); }
						@Override public void onResponse(Call c, Response response)
						{
							try (Response r = response)
							{
								if (stopped()) { return; }
								if (!r.isSuccessful()) { fail(httpError(r.code())); return; }
								if (r.body() == null || (!Sse.isEventStream(r) && !Sse.hasEventFraming(r)))
								{ fail("ChatGPT returned an unexpected response format. Please retry."); return; }
								JsonObject[] completed = {null};
								java.util.Map<Integer, JsonObject> completedItems = new java.util.TreeMap<>();
								StringBuilder partial = new StringBuilder();
								String before = text;
								Sse.read(r.body().source(), (event, data) -> {
									if (stopped()) { return false; }
									JsonObject value = ChatApi.fromJson(gson, data, JsonObject.class);
									String type = ChatGptAuth.text(value, "type");
									if ("response.output_item.done".equals(type) && value.has("output_index")
										&& value.has("item") && value.get("item").isJsonObject())
									{
										completedItems.put(value.get("output_index").getAsInt(), value.getAsJsonObject("item"));
									}
									if ("response.output_text.delta".equals(type))
									{
										partial.append(ChatGptAuth.text(value, "delta"));
										listener.onPartial(before + partial);
									}
									if ("response.completed".equals(type)) { completed[0] = value.getAsJsonObject("response"); return false; }
									if ("response.failed".equals(type) || "response.incomplete".equals(type) || "error".equals(type))
									{
										fail("ChatGPT couldn't complete the reply. Check your plan usage and connection, then retry. No API-key fallback was used.");
									return false;
									}
									return true;
								});
								if (stopped()) { return; }
								if (completed[0] == null) { fail(CUT_OFF); return; }
								// Some plan streams deliver final items only in output_item.done; their terminal output is empty.
								if ((!completed[0].has("output") || completed[0].getAsJsonArray("output").size() == 0)
									&& !completedItems.isEmpty())
								{
									JsonArray output = new JsonArray();
									completedItems.values().forEach(output::add);
									completed[0].add("output", output);
								}
								complete(completed[0]);
							}
							catch (Exception e) { fail("ChatGPT returned an unreadable or interrupted reply. Please retry."); }
						}
					});
				}
				catch (Exception e) { fail("Your ChatGPT connection isn't ready. Continue with ChatGPT from the menu again."); }
			});
		}

		void complete(JsonObject response)
		{
			if (response.has("usage") && response.get("usage").isJsonObject())
			{
				JsonObject u = response.getAsJsonObject("usage");
				long input = number(u, "input_tokens");
				long cached = u.has("input_tokens_details") && u.get("input_tokens_details").isJsonObject()
					? number(u.getAsJsonObject("input_tokens_details"), "cached_tokens") : 0;
				usage.input += Math.max(0, input - cached);
				usage.cacheRead += cached;
				usage.output += number(u, "output_tokens");
			}
			List<JsonObject> functions = new ArrayList<>();
			StringBuilder answer = new StringBuilder();
			if (response.has("output"))
			{
				for (JsonElement item : response.getAsJsonArray("output"))
				{
					JsonObject output = item.getAsJsonObject();
					produced.add(output);
					if ("function_call".equals(ChatGptAuth.text(output, "type"))) { functions.add(output); }
					if ("message".equals(ChatGptAuth.text(output, "type")) && output.has("content"))
					{
						for (JsonElement content : output.getAsJsonArray("content"))
						{
							JsonObject part = content.getAsJsonObject();
							if ("output_text".equals(ChatGptAuth.text(part, "type"))) { answer.append(ChatGptAuth.text(part, "text")); }
							if ("refusal".equals(ChatGptAuth.text(part, "type"))) { answer.append(ChatGptAuth.text(part, "refusal")); }
						}
					}
				}
			}
			text += answer;
			if (functions.isEmpty())
			{
				if (text.trim().isEmpty()) { fail("ChatGPT completed without a text reply."); return; }
				if (!stopped() && finished.compareAndSet(false, true))
				{
					Reply reply = new Reply();
					reply.text = text;
					reply.model = conversation.model;
					reply.usage.add(usage);
					listener.onReply(reply);
				}
				return;
			}
			if (++rounds > MAX_TOOL_ROUNDS || (calls += functions.size()) > MAX_TOOL_CALLS) { fail(TOO_MANY_ROUNDS); return; }
			runTool(functions, 0);
		}

		void runTool(List<JsonObject> functions, int index)
		{
			if (stopped()) { return; }
			if (index == functions.size()) { request(); return; }
			JsonObject function = functions.get(index);
			String name = ChatGptAuth.text(function, "name");
			boolean offered = conversation.tools.stream().anyMatch(t -> t.name.equals(name));
			String namespace = ChatGptAuth.text(function, "namespace");
			if (!offered || (!namespace.isEmpty() && !"osrs".equals(namespace)) || conversation.toolRunner == null)
			{ fail("ChatGPT requested a tool that isn't enabled for this question."); return; }
			try
			{
				JsonObject arguments = ChatApi.fromJson(gson, ChatGptAuth.text(function, "arguments"), JsonObject.class);
				if (arguments == null) { throw new IllegalArgumentException(); }
				AtomicBoolean once = new AtomicBoolean();
				conversation.toolRunner.run(name, arguments, result -> {
					if (stopped() || !once.compareAndSet(false, true)) { return; }
					JsonObject output = new JsonObject();
					output.addProperty("type", "function_call_output");
					output.addProperty("call_id", ChatGptAuth.text(function, "call_id"));
					output.addProperty("output", result.content);
					produced.add(output);
					runTool(functions, index + 1);
				});
			}
			catch (Exception e) { fail("ChatGPT supplied invalid look-up arguments. Please retry."); }
		}
	}

	private static long number(JsonObject value, String key) { return value.has(key) && !value.get(key).isJsonNull() ? value.get(key).getAsLong() : 0; }
}
