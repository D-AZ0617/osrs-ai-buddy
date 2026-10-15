package com.osrsaibuddy;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.*;

public class AccountContextToolsTest
{
    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(2);
    private final AtomicBoolean allowed = new AtomicBoolean(true);
    private final AtomicBoolean wanted = new AtomicBoolean(true);
    private final BlockingQueue<Runnable> client = new LinkedBlockingQueue<>();
    private String owner = "account-a";
    private int reads;
    private final AccountContextTools.Source source = new AccountContextTools.Source()
    {
        public String identity() { return owner; }
        public String username() { return "Example"; }
        public JsonObject read(String section) { reads++; JsonObject out = new JsonObject(); out.addProperty("section", section); return out; }
    };
    @After public void close() { executor.shutdownNow(); }
    private AccountContextTools tools()
    {
        return new AccountContextTools(source, client::add, executor, null, allowed::get, () -> false, wanted::get, ignored -> {});
    }
    private JsonObject input() { JsonObject in = new JsonObject(); in.addProperty("section", "overview"); return in; }

    @Test public void disabledSharingDoesNotOfferOrReadTools() throws Exception
    {
        allowed.set(false);
        AccountContextTools tools = tools(); assertTrue(tools.specs().isEmpty());
        CompletableFuture<ChatApi.ToolResult> result = new CompletableFuture<>();
        tools.run("get_account_progress", input(), result::complete);
        assertTrue(result.get().error); assertEquals(0, reads);
    }

    @Test public void switchAccountBeforeQueuedReadDiscardsResult() throws Exception
    {
        AccountContextTools tools = tools(); client.remove().run();
        CompletableFuture<ChatApi.ToolResult> result = new CompletableFuture<>();
        tools.run("get_account_progress", input(), result::complete);
        owner = "account-b"; client.remove().run();
        assertTrue(result.get(2, TimeUnit.SECONDS).error); assertEquals(0, reads);
    }

    @Test public void disablingBeforeReadDoesNotRead() throws Exception
    {
        AccountContextTools tools = tools(); client.remove().run();
        CompletableFuture<ChatApi.ToolResult> result = new CompletableFuture<>();
        tools.run("get_account_progress", input(), result::complete);
        allowed.set(false); client.remove().run();
        assertTrue(result.get(2, TimeUnit.SECONDS).error); assertEquals(0, reads);
    }

    @Test public void stoppedRequestDoesNotRead() throws Exception
    {
        AccountContextTools tools = tools(); client.remove().run(); wanted.set(false);
        CompletableFuture<ChatApi.ToolResult> result = new CompletableFuture<>();
        tools.run("get_account_progress", input(), result::complete);
        assertTrue(result.get().error); assertEquals(0, reads);
    }

    @Test public void successfulReadReportsSourceAndActivity() throws Exception
    {
        AccountContextTools tools = tools(); client.remove().run();
        CompletableFuture<ChatApi.ToolResult> result = new CompletableFuture<>();
        tools.run("get_account_progress", input(), result::complete);
        client.remove().run();
        Runnable delivery = client.poll(2, TimeUnit.SECONDS); assertNotNull(delivery); delivery.run();
        ChatApi.ToolResult answer = result.get(2, TimeUnit.SECONDS);
        assertFalse(answer.error); assertTrue(answer.content.contains("RuneLite")); assertEquals(1, reads);
    }

    @Test public void accountSwitchAfterReadBlocksDelivery() throws Exception
    {
        AccountContextTools tools = tools(); client.remove().run();
        CompletableFuture<ChatApi.ToolResult> result = new CompletableFuture<>();
        tools.run("get_account_progress", input(), result::complete);
        client.remove().run();
        Runnable delivery = client.poll(2, TimeUnit.SECONDS); assertNotNull(delivery);
        owner = "account-b"; delivery.run();
        assertTrue(result.get(2, TimeUnit.SECONDS).error); assertEquals(1, reads);
    }

    @Test public void disablingAfterReadBlocksDelivery() throws Exception
    {
        AccountContextTools tools = tools(); client.remove().run();
        CompletableFuture<ChatApi.ToolResult> result = new CompletableFuture<>();
        tools.run("get_account_progress", input(), result::complete);
        client.remove().run();
        Runnable delivery = client.poll(2, TimeUnit.SECONDS); assertNotNull(delivery);
        allowed.set(false); delivery.run();
        assertTrue(result.get(2, TimeUnit.SECONDS).error);
    }

    @Test public void arraysCanBeSearchedAndPagedWithoutInventingMissingEntries()
    {
        JsonArray rows = new JsonArray();
        for (int i = 0; i < 125; i++) { rows.add("TASK_" + i); }
        JsonObject input = new JsonObject(); input.addProperty("offset", 100); input.addProperty("limit", 20);
        JsonObject page = AccountContextTools.page(rows, input).getAsJsonObject();
        assertEquals(125, page.get("totalMatches").getAsInt());
        assertEquals(120, page.get("nextOffset").getAsInt());
        assertEquals(20, page.getAsJsonArray("rows").size());
        input.addProperty("offset", 0); input.addProperty("search", "task_124");
        assertEquals(1, AccountContextTools.page(rows, input).getAsJsonObject().getAsJsonArray("rows").size());
    }

    @Test(expected = IllegalArgumentException.class) public void rejectsOversizedPage()
    { JsonObject in = new JsonObject(); in.addProperty("limit", 101); AccountContextTools.page(new JsonArray(), in); }

    @Test(expected = IllegalArgumentException.class) public void missingFieldIsUnknown()
    { AccountContextTools.select(new JsonObject(), "latestSnapshot.data.bosses"); }

    @Test public void explicitProgressAllowlistHasBroadCoverage()
    {
        assertTrue(AccountFlags.forSection("combat_achievements").length >= 399);
        assertTrue(AccountFlags.forSection("collection_sections").length >= 125);
        assertTrue(AccountFlags.forSection("unlocks").length >= 37);
        assertTrue(AccountFlags.forSection("currencies").length >= 6);
    }
}
