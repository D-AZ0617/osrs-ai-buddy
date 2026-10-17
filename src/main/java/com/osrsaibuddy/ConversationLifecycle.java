package com.osrsaibuddy;

import java.util.List;

/** Unsent plus-button drafts are not conversation history. No automatic conversation creation. */
final class ConversationLifecycle
{
    static Chat draft(List<Chat> saved, Chat current)
    {
        if (current != null && !saved.contains(current) && current.messages.isEmpty() && !current.isRunning()) { return current; }
        return new Chat("New Conversation");
    }

    static void commit(List<Chat> saved, Chat chat)
    {
        if (!saved.contains(chat)) { saved.add(chat); }
    }

    static void removeLegacyPlaceholders(List<Chat> saved)
    {
        saved.removeIf(chat -> chat.messages.isEmpty() && !chat.isRunning() && !chat.namedByPlayer
            && (chat.defaultName || chat.name.matches("Chat \\d+")));
    }
}
