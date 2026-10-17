package com.osrsaibuddy;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class ConversationLifecycleTest
{
    @Test public void spammingPlusReusesOneUnpersistedDraft()
    {
        List<Chat> saved = new ArrayList<>(); Chat draft = ConversationLifecycle.draft(saved, null);
        for (int i = 0; i < 50; i++) { assertSame(draft, ConversationLifecycle.draft(saved, draft)); }
        assertTrue(saved.isEmpty()); assertEquals("New Conversation", draft.name);
        assertFalse(ChatStore.toJson(new com.google.gson.Gson(), saved, draft).contains(draft.id));
    }
    @Test public void firstSubmissionCommitsExactlyOneConversation()
    {
        List<Chat> saved = new ArrayList<>(); Chat draft = ConversationLifecycle.draft(saved, null);
        ConversationLifecycle.commit(saved, draft); draft.messages.add(new Chat.Message(Chat.Role.USER, "Question"));
        ConversationLifecycle.commit(saved, draft); assertEquals(1, saved.size());
        assertNotSame(draft, ConversationLifecycle.draft(saved, draft));
    }
    @Test public void legacyEmptyNumberedChatsAreRemovedButRealHistoryAndNamedChatsRemain()
    {
        List<Chat> saved = new ArrayList<>();
        Chat ghost = new Chat("Chat 99"); Chat real = new Chat("Chat 1"); real.messages.add(new Chat.Message(Chat.Role.USER, "Keep me"));
        Chat named = new Chat("My plans"); named.namedByPlayer = true;
        saved.add(ghost); saved.add(real); saved.add(named);
        ConversationLifecycle.removeLegacyPlaceholders(saved);
        assertEquals(java.util.Arrays.asList(real, named), saved);
    }
}
