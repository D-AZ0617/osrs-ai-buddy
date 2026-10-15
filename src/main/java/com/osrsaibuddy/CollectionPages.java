package com.osrsaibuddy;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.runelite.api.widgets.Widget;

/** Validated, account-scoped snapshots of pages the player opened. No UI actions or persistence. */
final class CollectionPages
{
    private static final Pattern COUNTS = Pattern.compile("(\\d[\\d,]*)\\s*/\\s*(\\d[\\d,]*)");
    private final Map<String, JsonArray> pages = new LinkedHashMap<>();
    void clear() { pages.clear(); }
    int size() { return pages.size(); }

    boolean capture(Widget header, Widget container)
    {
        if (header == null || container == null || header.isHidden() || container.isHidden()) { return false; }
        Widget[] headings = header.getDynamicChildren();
        Widget[] items = container.getDynamicChildren();
        if (headings == null || headings.length < 2 || headings[0] == null || items == null) { return false; }
        String title = plain(headings[0].getText());
        if (title.isEmpty() || title.length() > 150) { return false; }
        int expectedObtained = -1, expectedTotal = -1;
        for (Widget heading : headings)
        {
            if (heading == null) { continue; }
            Matcher counts = COUNTS.matcher(plain(heading.getText()));
            if (counts.find())
            {
                try {
                    expectedObtained = Integer.parseInt(counts.group(1).replace(",", ""));
                    expectedTotal = Integer.parseInt(counts.group(2).replace(",", ""));
                } catch (NumberFormatException e) { return false; }
                break;
            }
        }
        if (expectedTotal <= 0 || expectedTotal > 2000 || expectedObtained < 0 || expectedObtained > expectedTotal) { return false; }
        JsonArray rows = new JsonArray();
        int obtained = 0;
        String seenAt = Instant.now().toString();
        for (Widget item : items)
        {
            if (item == null || item.getItemId() <= 0) { continue; }
            boolean unlocked = item.getOpacity() == 0;
            if (unlocked) { obtained++; }
            JsonObject row = new JsonObject();
            row.addProperty("page", title);
            row.addProperty("itemId", item.getItemId());
            row.addProperty("status", unlocked ? "obtained" : "missing");
            row.addProperty("quantity", unlocked ? Math.max(1, item.getItemQuantity()) : 0);
            row.addProperty("observedAt", seenAt);
            rows.add(row);
        }
        // During page changes widgets can contain stale or partially loaded item data. Fail closed.
        if (rows.size() != expectedTotal || obtained != expectedObtained) { return false; }
        if (!pages.containsKey(title) && pages.size() >= 300) { return false; }
        pages.put(title, rows);
        return true;
    }

    JsonArray entries()
    {
        JsonArray out = new JsonArray();
        pages.values().forEach(rows -> rows.forEach(row -> out.add(row.deepCopy())));
        return out;
    }

    private static String plain(String text) { return text == null ? "" : text.replaceAll("<[^>]*>", "").trim(); }
}
