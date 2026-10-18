package com.osrsaibuddy;

import com.google.gson.Gson;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class WikiAttributionTest
{
    @Test public void creditsDeduplicatePagesAndRejectUntrustedDestinations()
    {
        String page = "https://oldschool.runescape.wiki/w/Abyssal_whip";
        String notice = WikiAttribution.notice(Arrays.asList(page + "#Stats", page,
            "https://example.com/w/Bad", "file:///etc/passwd", "https://oldschool.runescape.wiki.evil.test/w/X"));
        assertTrue(notice.contains("[Contributors](" + page + "?action=history)"));
        assertTrue(notice.contains(WikiAttribution.LICENSE));
        assertTrue(notice.contains("adapted/summarized"));
        assertFalse(notice.contains("example.com"));
        assertEquals(1, WikiAttribution.valid(Arrays.asList(page, page + "#Stats")).size());
    }

    @Test public void attributionSurvivesSaveRestoreAndSummarization()
    {
        Gson gson = new Gson(); Chat chat = new Chat("Goals");
        chat.wikiSources.add("https://oldschool.runescape.wiki/w/Slayer");
        Chat.Message question = new Chat.Message(Chat.Role.USER, "What next?"); chat.messages.add(question);
        ConversationBuilder.applySummary(chat, Collections.emptyList(), "Train Slayer", question);
        Chat loaded = ChatStore.fromJson(gson, ChatStore.toJson(gson, Collections.singletonList(chat), chat)).chats.get(0);
        assertEquals(chat.wikiSources, loaded.wikiSources);
        assertTrue(loaded.summary.contains("action=history"));
        assertTrue(loaded.summary.contains(WikiAttribution.LICENSE));
    }

    @Test public void noRetrievalDoesNotProduceFabricatedAttribution()
    {
        assertEquals("", WikiAttribution.notice(Collections.emptyList()));
        ChatApi.ToolResult result = WikiAttribution.result("Snippet", Collections.singletonList("https://oldschool.runescape.wiki/w/Slayer"));
        assertTrue(result.content.contains("Snippet"));
        assertEquals(1, result.wikiSources.size());
    }
}
