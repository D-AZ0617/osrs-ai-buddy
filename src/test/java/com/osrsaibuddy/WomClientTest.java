package com.osrsaibuddy;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class WomClientTest
{
    private HttpServer server;
    private WomClient wom;
    private int status = 200;
    private String body = "{\"latestSnapshot\":{\"data\":{\"bosses\":{\"zulrah\":{\"kills\":-1}}}}}";
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> auth = new AtomicReference<>();
    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicReference<String> userAgent = new AtomicReference<>();
    private CountDownLatch entered;
    private CountDownLatch release;

    @Before public void start() throws Exception
    {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet(); path.set(exchange.getRequestURI().toASCIIString());
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            method.set(exchange.getRequestMethod()); userAgent.set(exchange.getRequestHeaders().getFirst("User-Agent"));
            if (entered != null) { entered.countDown(); try { release.await(3, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Location", "/unexpected-redirect");
            exchange.sendResponseHeaders(status, bytes.length);
            try { exchange.getResponseBody().write(bytes); } finally { exchange.close(); }
        });
        server.start();
        wom = new WomClient(new OkHttpClient(), new Gson(), HttpUrl.get("http://127.0.0.1:" + server.getAddress().getPort() + "/v2/"));
    }
    @After public void stop() { wom.clear(); if (release != null) { release.countDown(); } server.stop(0); }

    @Test public void readOnlyEncodedUsernameNoCredentialsAndPreservedUnknownMetrics() throws Exception
    {
        JsonElement result = wom.get("Example Name", "profile", "week");
        assertEquals(-1, AccountContextTools.select(result, "latestSnapshot.data.bosses.zulrah.kills").getAsInt());
        assertEquals("/v2/players/Example%20Name", path.get()); assertEquals("GET", method.get());
        assertNull(auth.get()); assertTrue(userAgent.get().contains("D-AZ0617/osrs-ai-buddy"));
    }

    @Test public void cachesButDoesNotShareMutableCacheObjects() throws Exception
    {
        wom.get("Example", "profile", "week").getAsJsonObject().addProperty("polluted", true);
        assertFalse(wom.get("Example", "profile", "week").getAsJsonObject().has("polluted"));
        assertEquals(1, calls.get());
    }

    @Test public void rateSpacesDifferentUncachedRequests() throws Exception
    {
        wom.get("Example", "profile", "week");
        try { wom.get("Example", "achievements", "week"); fail(); }
        catch (java.io.IOException e) { assertTrue(e.getMessage().contains("spaced")); }
        assertEquals(1, calls.get());
    }

    @Test public void missingProfileIsUnavailableNotZero() throws Exception
    {
        status = 404;
        try { wom.get("Example", "profile", "week"); fail(); }
        catch (java.io.IOException e) { assertTrue(e.getMessage().contains("does not create")); }
    }

    @Test public void doesNotFollowRedirects() throws Exception
    {
        status = 302;
        try { wom.get("Example", "profile", "week"); fail(); }
        catch (java.io.IOException e) { assertTrue(e.getMessage().contains("302")); }
        assertEquals(1, calls.get());
    }

    @Test public void rejectsLargeResponses() throws Exception
    {
        body = " ".repeat(1024 * 1024 + 1);
        try { wom.get("Example", "profile", "week"); fail(); }
        catch (java.io.IOException e) { assertTrue(e.getMessage().contains("limit")); }
    }

    @Test public void disablingCancelsInFlightResult() throws Exception
    {
        entered = new CountDownLatch(1); release = new CountDownLatch(1);
        CompletableFuture<Boolean> discarded = CompletableFuture.supplyAsync(() -> {
            try { wom.get("Example", "profile", "week"); return false; }
            catch (java.io.IOException e) { return true; }
        });
        assertTrue(entered.await(2, TimeUnit.SECONDS)); wom.clear(); release.countDown();
        assertTrue(discarded.get(3, TimeUnit.SECONDS));
    }

    @Test public void accountChangeCancelsQueuedRequestsBeforeSending() throws Exception
    {
        long captured = wom.generation(); wom.clear();
        try { wom.get("Old account", "profile", "week", captured); fail(); }
        catch (java.io.IOException e) { assertTrue(e.getMessage().contains("queued")); }
        assertEquals(0, calls.get());
    }

    @Test public void achievementsAndGainsUseDocumentedReadOnlyRoutes() throws Exception
    {
        body = "[]";
        wom.get("Example", "achievement_progress", "week");
        assertEquals("/v2/players/Example/achievements/progress", path.get());
        wom = new WomClient(new OkHttpClient(), new Gson(), HttpUrl.get("http://127.0.0.1:" + server.getAddress().getPort() + "/v2/"));
        wom.get("Example", "gains", "month");
        assertEquals("/v2/players/Example/gained?period=month", path.get());
        assertEquals("GET", method.get());
    }

    @Test public void serverRateLimitStartsCooldown() throws Exception
    {
        status = 429;
        try { wom.get("Example", "profile", "week"); fail(); }
        catch (java.io.IOException e) { assertTrue(e.getMessage().contains("one minute")); }
        try { wom.get("Example", "achievements", "week"); fail(); }
        catch (java.io.IOException e) { assertTrue(e.getMessage().contains("spaced")); }
        assertEquals(1, calls.get());
    }

    @Test public void invalidDatasetCannotMakeArbitraryRequest() throws Exception
    {
        try { wom.get("Example", "../../other-host", "week"); fail(); }
        catch (java.io.IOException e) { assertTrue(e.getMessage().contains("Unknown")); }
        assertEquals(0, calls.get());
    }
}
