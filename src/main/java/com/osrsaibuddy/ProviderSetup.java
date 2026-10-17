package com.osrsaibuddy;

import com.google.gson.Gson;
import okhttp3.OkHttpClient;

/** One authorized ChatGPT plan connection. No API keys or alternate providers. */
final class ProviderSetup
{
    private final OsrsAiBuddyConfig config;
    private final ChatGptAuth chatgpt;
    java.util.function.Supplier<java.util.List<String>> availableModels = java.util.Collections::emptyList;
    ProviderSetup(OsrsAiBuddyConfig config, ChatGptAuth chatgpt) { this.config = config; this.chatgpt = chatgpt; }
    String connectionProblem()
    {
        return chatgpt != null && chatgpt.connected() ? null : "Sign in from the chat menu to connect ChatGPT.";
    }
    String problem()
    {
        String connection = connectionProblem();
        return connection != null ? connection : model().isEmpty() ? "No supported lightweight model is available yet. Refresh the model list. Buddy will not fall back to a flagship model." : null;
    }
    ChatApi api(OkHttpClient http, Gson gson) { return problem() == null ? build(http, gson) : null; }
    ChatApi testApi(OkHttpClient http, Gson gson) { return connectionProblem() == null ? build(http, gson) : null; }
    private ChatApi build(OkHttpClient http, Gson gson)
    {
        String connection = chatgpt.connectionId();
        return new ChatGptPlanApi(http, gson, () -> {
            if (!connection.equals(chatgpt.connectionId())) { throw new java.io.IOException("Account changed"); }
            return chatgpt.accessToken();
        }, chatgpt.requestExecutor());
    }
    String model() { return BuddyModels.selected(config.chatgptPlanModel().trim(), availableModels.get()); }
    String modelKey() { return "chatgptPlanModel"; }
    String service() { return "ChatGPT"; }
    boolean keyed() { return true; }
    boolean keyUnchecked() { return false; }
    String connection() { return "chatgpt-plan:" + (chatgpt == null ? "signed-out" : chatgpt.connectionId()); }
}
