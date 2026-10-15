package com.osrsaibuddy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Per-message, bounded, opt-in tools. Game reads and account checks always happen on the client thread. */
final class AccountContextTools implements ChatApi.ToolRunner
{
    interface Source
    {
        String identity();
        String username();
        JsonObject read(String section);
    }
    private final Source source;
    private final Consumer<Runnable> clientThread;
    private final ScheduledExecutorService executor;
    private final WomClient wom;
    private final BooleanSupplier progressAllowed;
    private final BooleanSupplier womAllowed;
    private final BooleanSupplier wanted;
    private final Consumer<String> activity;
    private final CompletableFuture<String> account;
    private final long womGeneration;

    AccountContextTools(Source source, Consumer<Runnable> clientThread, ScheduledExecutorService executor,
        WomClient wom, BooleanSupplier progressAllowed, BooleanSupplier womAllowed,
        BooleanSupplier wanted, Consumer<String> activity)
    {
        this.source = source; this.clientThread = clientThread; this.executor = executor; this.wom = wom;
        this.progressAllowed = progressAllowed; this.womAllowed = womAllowed;
        this.wanted = wanted; this.activity = activity;
        this.womGeneration = wom == null ? 0 : wom.generation();
        account = onClient(source::identity);
    }

    static boolean handles(String name) { return "get_account_progress".equals(name) || "get_wom_data".equals(name); }

    List<ChatApi.ToolSpec> specs()
    {
        List<ChatApi.ToolSpec> specs = new ArrayList<>();
        if (progressAllowed.getAsBoolean())
        {
            specs.add(new ChatApi.ToolSpec("get_account_progress",
                "Read current account progress: overview, skills (XP and boosted levels), quests, combat_achievements, collection_log (partial obtained items), collection_sections, unlocks, currencies. Paginate with offset/limit; search filters names. Unseen collection items are UNKNOWN. Task identifiers are internal names. Diaries, bank, gear and Slayer use their separate tools.",
                schema("section", new String[]{"overview", "skills", "quests", "combat_achievements", "collection_log", "collection_sections", "unlocks", "currencies"})));
        }
        if (womAllowed.getAsBoolean())
        {
            specs.add(new ChatApi.ToolSpec("get_wom_data",
                "Read existing Wise Old Man data for THIS logged-in player only. profile includes latest snapshot: skill XP/ranks, boss kills/ranks, activities/clues, EHP/EHB. Other datasets: gains, achievements, achievement_progress, records, competitions, groups (first 50), name_changes. Historical data may be stale or missing; -1 means unranked/unknown, never zero. No player tracking/updates are created. Calls spaced 3.1s; cached for 2 minutes. Arrays paginated locally. Optional field selects a dot path (e.g. latestSnapshot.data.bosses) before pagination; period day/week/month/year for gains.",
                schema("dataset", new String[]{"profile", "gains", "achievements", "achievement_progress", "records", "competitions", "groups", "name_changes"})));
        }
        return specs;
    }

    private static JsonObject schema(String selector, String[] values)
    {
        JsonObject props = new JsonObject();
        JsonObject choice = new JsonObject(); choice.addProperty("type", "string");
        JsonArray choices = new JsonArray(); for (String value : values) { choices.add(value); }
        choice.add("enum", choices); props.add(selector, choice);
        for (String key : new String[]{"search", "field", "period"})
        { JsonObject p = new JsonObject(); p.addProperty("type", "string"); props.add(key, p); }
        for (String key : new String[]{"offset", "limit"})
        { JsonObject p = new JsonObject(); p.addProperty("type", "integer"); p.addProperty("minimum", key.equals("limit") ? 1 : 0); props.add(key, p); }
        JsonObject schema = new JsonObject(); schema.addProperty("type", "object"); schema.add("properties", props);
        JsonArray required = new JsonArray(); required.add(selector); schema.add("required", required);
        schema.addProperty("additionalProperties", false); return schema;
    }

    @Override public void run(String name, JsonObject input, Consumer<ChatApi.ToolResult> done)
    {
        boolean remote = "get_wom_data".equals(name);
        BooleanSupplier allowed = remote ? womAllowed : progressAllowed;
        if (!handles(name) || !allowed.getAsBoolean() || !wanted.getAsBoolean())
        { done.accept(ChatApi.ToolResult.error("Account sharing is disabled or the request stopped.")); return; }
        final String section;
        final String period;
        try
        {
            section = text(input, remote ? "dataset" : "section", "overview");
            period = text(input, "period", "week");
            if (!period.matches("day|week|month|year")) { throw new IllegalArgumentException("Invalid gains period"); }
        }
        catch (RuntimeException e) { done.accept(ChatApi.ToolResult.error("Invalid account tool arguments.")); return; }
        account.thenCompose(owner -> onClient(() -> {
            check(owner, allowed);
            return remote ? source.username() : source.read(section);
        }).thenApplyAsync(raw -> {
            if (!allowed.getAsBoolean() || !wanted.getAsBoolean()) { throw new IllegalStateException("Sharing stopped"); }
            try { return remote ? wom.get((String) raw, section, period, womGeneration) : (JsonElement) raw; }
            catch (java.io.IOException e) { throw new IllegalStateException(e.getMessage()); }
        }, executor).thenCompose(data -> onClient(() -> {
            check(owner, allowed);
            JsonObject result = new JsonObject();
            result.addProperty("source", remote ? "Wise Old Man; historical/public tracking, not live RuneLite" : "RuneLite");
            result.addProperty("deliveredAt", Instant.now().toString());
            result.addProperty("coverage", remote ? "Data as last tracked by WOM, possibly stale or incomplete. Negative metrics mean unranked/unknown. Collection log is not provided by WOM." : "Only game-exposed progress. Unseen collection items remain unknown.");
            // Keep snapshot timestamps even when selecting just a metric subtree.
            if (remote && data.isJsonObject())
            {
                JsonObject obj = data.getAsJsonObject();
                if (obj.has("updatedAt")) { result.add("profileUpdatedAt", obj.get("updatedAt")); }
                if (obj.has("latestSnapshot") && obj.get("latestSnapshot").isJsonObject())
                { JsonElement date = obj.getAsJsonObject("latestSnapshot").get("createdAt"); if (date != null) { result.add("snapshotAt", date); } }
            }
            result.add("data", page(select(data, text(input, "field", "")), input));
            String json = result.toString();
            if (json.length() > 60000) { throw new IllegalArgumentException("Result too large. Select a field, search, or use a smaller limit."); }
            return json;
        }))).whenComplete((json, failure) -> {
            if (failure != null) {
                Throwable cause = failure; while (cause.getCause() != null) { cause = cause.getCause(); }
                done.accept(ChatApi.ToolResult.error("Account data unavailable: " + cause.getMessage()));
            }
            else if (!allowed.getAsBoolean() || !wanted.getAsBoolean()) { done.accept(ChatApi.ToolResult.error("Sharing stopped; result discarded.")); }
            else { activity.accept("Shared your " + (remote ? "Wise Old Man " : "account ") + section); done.accept(ChatApi.ToolResult.ok(json)); }
        });
    }

    private void check(String owner, BooleanSupplier allowed)
    {
        if (owner == null || !owner.equals(source.identity()) || !allowed.getAsBoolean() || !wanted.getAsBoolean())
        { throw new IllegalStateException("Logged-in account or sharing settings changed; result discarded"); }
    }

    private <T> CompletableFuture<T> onClient(Supplier<T> read)
    {
        CompletableFuture<T> future = new CompletableFuture<>();
        clientThread.accept(() -> { if (!future.isDone()) { try { future.complete(read.get()); } catch (RuntimeException e) { future.completeExceptionally(e); } } });
        return future.orTimeout(5, TimeUnit.SECONDS);
    }

    static String text(JsonObject input, String key, String fallback)
    {
        String value = input.has(key) ? input.get(key).getAsString() : fallback;
        if (value.length() > 150) { throw new IllegalArgumentException("Argument too long"); }
        return value;
    }

    static JsonElement select(JsonElement data, String field)
    {
        if (field.isEmpty()) { return data; }
        if (!field.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+){0,7}")) { throw new IllegalArgumentException("Invalid field path"); }
        for (String key : field.split("\\."))
        {
            if (!data.isJsonObject() || !data.getAsJsonObject().has(key)) { throw new IllegalArgumentException("Requested field is unavailable"); }
            data = data.getAsJsonObject().get(key);
        }
        return data;
    }

    static JsonElement page(JsonElement data, JsonObject input)
    {
        if (data.isJsonObject() && data.getAsJsonObject().has("entries"))
        {
            JsonObject copy = data.getAsJsonObject().deepCopy();
            copy.add("entries", page(copy.get("entries"), input)); return copy;
        }
        if (!data.isJsonArray()) { return data; }
        int offset = input.has("offset") ? input.get("offset").getAsInt() : 0;
        int limit = input.has("limit") ? input.get("limit").getAsInt() : 50;
        if (offset < 0 || limit < 1 || limit > 100) { throw new IllegalArgumentException("offset >= 0; limit 1..100"); }
        String search = text(input, "search", "").toLowerCase(Locale.ROOT);
        JsonArray all = new JsonArray();
        for (JsonElement row : data.getAsJsonArray()) { if (row.toString().toLowerCase(Locale.ROOT).contains(search)) { all.add(row); } }
        JsonArray rows = new JsonArray();
        for (int i = offset; i < all.size() && i - offset < limit; i++) { rows.add(all.get(i)); }
        JsonObject out = new JsonObject(); out.addProperty("totalMatches", all.size()); out.addProperty("offset", offset);
        out.add("rows", rows);
        if ((long) offset + limit < all.size()) { out.addProperty("nextOffset", offset + limit); }
        return out;
    }
}
