package com.osrsaibuddy;

import com.google.gson.Gson;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import static org.junit.Assert.*;

public class WikiCacheTest
{
    private CompletableFuture<String> search(WikiClient wiki, String query)
    {
        CompletableFuture<String> result = new CompletableFuture<>();
        wiki.search(query, 6, () -> true, new WikiClient.Listener<WikiClient.Search>() {
            public void onResult(WikiClient.Search value) { result.complete("ok"); }
            public void onError(String code, String message) { result.complete(code == null ? message : code); }
        });
        return result;
    }

    @Test public void cacheExpiresAndOptOutStillBlocksCachedResults() throws Exception
    {
        StandIn server = new StandIn();
        try {
            server.answer("/api.php", StandIn.json(200, "{\"query\":{\"search\":[]}}"));
            AtomicLong now = new AtomicLong(1000);
            java.util.concurrent.atomic.AtomicBoolean allowed = new java.util.concurrent.atomic.AtomicBoolean(true);
            WikiClient wiki = new WikiClient(new okhttp3.OkHttpClient(), new Gson(), allowed::get, server.url("/api.php"), 2000, now::get);
            assertEquals("ok", search(wiki, "Slayer").get(5, TimeUnit.SECONDS));
            assertEquals("ok", search(wiki, "Slayer").get(5, TimeUnit.SECONDS));
            assertEquals(1, server.uris.size());
            allowed.set(false);
            assertEquals(WikiClient.TURNED_OFF, search(wiki, "Slayer").get(5, TimeUnit.SECONDS));
            allowed.set(true); now.addAndGet(300001);
            assertEquals("ok", search(wiki, "Slayer").get(5, TimeUnit.SECONDS));
            assertEquals(2, server.uris.size());
        } finally { server.stop(); }
    }

    @Test public void mediaWikiMaxlagErrorsAlsoPauseFurtherRequests() throws Exception
    {
        StandIn server = new StandIn();
        try {
            server.answer("/api.php", StandIn.json(200, "{\"error\":{\"code\":\"maxlag\"}}"));
            WikiClient wiki = new WikiClient(new okhttp3.OkHttpClient(), new Gson(), () -> true, server.url("/api.php"), 2000, () -> 1000);
            assertEquals("maxlag", search(wiki, "One").get(5, TimeUnit.SECONDS));
            assertEquals("cooldown", search(wiki, "Two").get(5, TimeUnit.SECONDS));
            assertEquals(1, server.uris.size());
        } finally { server.stop(); }
    }

    @Test public void retryAfterStopsQueuedTrafficUntilTheServerDelayExpires() throws Exception
    {
        StandIn server = new StandIn();
        try {
            server.answer("/api.php", StandIn.json(429, "{}").header("Retry-After", "120"), StandIn.json(200, "{\"query\":{\"search\":[]}}"));
            AtomicLong now = new AtomicLong(1000);
            WikiClient wiki = new WikiClient(new okhttp3.OkHttpClient(), new Gson(), () -> true, server.url("/api.php"), 2000, now::get);
            CompletableFuture<String> first = search(wiki, "One");
            CompletableFuture<String> queued = search(wiki, "Two");
            assertNotEquals("ok", first.get(5, TimeUnit.SECONDS));
            assertEquals("cooldown", queued.get(5, TimeUnit.SECONDS));
            assertEquals(1, server.uris.size());
            now.addAndGet(120001);
            assertEquals("ok", search(wiki, "Three").get(5, TimeUnit.SECONDS));
            assertEquals(2, server.uris.size());
            assertEquals(121000, WikiClient.retryTime("120", 1000));
            assertEquals(61000, WikiClient.retryTime("invalid", 1000));
            assertEquals(120000, WikiClient.retryTime("Thu, 1 Jan 1970 00:02:00 GMT", 1000));
        } finally { server.stop(); }
    }
}
