package com.osrsaibuddy;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Public, read-only WOM data. No authentication, player updates, tracking creation, or arbitrary URLs. */
final class WomClient
{
    private static final int MAX_BYTES = 1024 * 1024;
    private final OkHttpClient http;
    private final Gson gson;
    private final HttpUrl base;
    private final Map<String, Cached> cache = new LinkedHashMap<>();
    private Call active;
    private long nextRequest;
    private long generation;

    private static final class Cached
    {
        final long time = System.currentTimeMillis();
        final JsonElement data;
        Cached(JsonElement data) { this.data = data; }
    }

    WomClient(OkHttpClient http, Gson gson)
    {
        this(http, gson, HttpUrl.get("https://api.wiseoldman.net/v2/"));
    }

    WomClient(OkHttpClient http, Gson gson, HttpUrl base)
    {
        this.http = http.newBuilder().authenticator(okhttp3.Authenticator.NONE)
            .proxyAuthenticator(okhttp3.Authenticator.NONE).cookieJar(okhttp3.CookieJar.NO_COOKIES)
            .followRedirects(false).followSslRedirects(false).cache(null)
            .callTimeout(20, TimeUnit.SECONDS).build();
        this.gson = gson;
        this.base = base;
    }

    synchronized void clear()
    {
        generation++;
        cache.clear();
        if (active != null) { active.cancel(); active = null; }
    }

    synchronized long generation() { return generation; }

    JsonElement get(String username, String dataset, String period) throws IOException
    {
        return get(username, dataset, period, generation());
    }

    JsonElement get(String username, String dataset, String period, long expectedGeneration) throws IOException
    {
        HttpUrl.Builder url = base.newBuilder().addPathSegment("players").addPathSegment(username);
        switch (dataset)
        {
            case "profile": break;
            case "achievements": url.addPathSegment("achievements"); break;
            case "achievement_progress": url.addPathSegment("achievements").addPathSegment("progress"); break;
            case "gains": url.addPathSegment("gained").addQueryParameter("period", period); break;
            case "records": url.addPathSegment("records"); break;
            case "competitions": url.addPathSegment("competitions"); break;
            case "groups": url.addPathSegment("groups").addQueryParameter("limit", "50"); break;
            case "name_changes": url.addPathSegment("names"); break;
            default: throw new IOException("Unknown WOM dataset");
        }
        String key = url.build().toString();
        Call call;
        long version;
        synchronized (this)
        {
            if (generation != expectedGeneration) { throw new IOException("Account sharing changed; queued WOM request discarded."); }
            Cached found = cache.get(key);
            if (found != null && System.currentTimeMillis() - found.time < 120_000) { return found.data.deepCopy(); }
            if (active != null || System.currentTimeMillis() < nextRequest)
            { throw new IOException("WOM requests are spaced at least 3.1 seconds apart. Use cached data or try again shortly."); }
            nextRequest = System.currentTimeMillis() + 3100;
            version = generation;
            call = http.newCall(new Request.Builder().url(url.build())
                .header("User-Agent", "OSRS-AI-Buddy/1.0 (+https://github.com/D-AZ0617/osrs-ai-buddy)")
                .header("Accept", "application/json").build());
            active = call;
        }
        try (Response response = call.execute())
        {
            if (response.code() == 404) { throw new IOException("No existing WOM profile/data found. This plugin does not create or update public tracking. Unavailable does not mean zero progress."); }
            if (response.code() == 429)
            {
                synchronized (this) { nextRequest = Math.max(nextRequest, System.currentTimeMillis() + 60_000); }
                throw new IOException("WOM rate limit reached. Try again after one minute.");
            }
            if (!response.isSuccessful() || response.body() == null) { throw new IOException("WOM unavailable (HTTP " + response.code() + ")."); }
            response.body().source().request(MAX_BYTES + 1L);
            byte[] bytes = response.body().source().readByteArray(Math.min(response.body().source().buffer().size(), MAX_BYTES + 1L));
            if (bytes.length > MAX_BYTES) { throw new IOException("WOM response exceeds safety limit. Ask for another dataset."); }
            JsonElement data;
            try { data = ChatApi.fromJson(gson, new String(bytes, StandardCharsets.UTF_8), JsonElement.class); }
            catch (RuntimeException e) { throw new IOException("WOM returned invalid data."); }
            if (data == null || data.isJsonNull()) { throw new IOException("WOM returned no data."); }
            synchronized (this)
            {
                if (version != generation) { throw new IOException("Account sharing changed; WOM result discarded."); }
                if (cache.size() >= 32) { cache.clear(); }
                cache.put(key, new Cached(data.deepCopy()));
            }
            return data;
        }
        finally { synchronized (this) { if (active == call) { active = null; } } }
    }
}
