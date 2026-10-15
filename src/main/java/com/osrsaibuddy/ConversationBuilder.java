package com.osrsaibuddy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What a chat sends: which of its messages go to the provider and how, and the summary that's sent instead of the
 * oldest ones once a chat gets long. No threads or files here; the plugin calls it on the Swing EDT, where chats live.
 */
final class ConversationBuilder
{
	/** Once more messages than this would be sent, the oldest are summarised... */
	static final int SUMMARY_HIGH = 40;
	/** ...and at least this many of the newest are kept as they are. */
	static final int SUMMARY_LOW = 16;
	/**
	 * Plenty for a summary of at most 250 words. Thinking models count their reasoning against it too, so it leaves
	 * room for that; only what's used is billed.
	 */
	static final int SUMMARY_MAX_TOKENS = 4000;
	static final String SUMMARY_PROMPT = "Summarise this conversation between an Old School RuneScape player and an "
		+ "assistant so the assistant can carry on without the original messages. Keep facts about the player's account, "
		+ "goals, decisions, open questions, and anything the player asked to remember. Plain text, at most 250 words.";

	private ConversationBuilder()
	{
	}

	// ------------------------------------------------------------------
	// The request
	// ------------------------------------------------------------------

	/**
	 * The request for {@code message}, the player's newest: the chat's messages that are still sent, with character
	 * notes and the summary where they go. Records on {@code message} what goes with it (its note and the summary it's
	 * sent under), since from now on that's part of what the provider has seen. {@code shareCharacter}: the setting
	 * when the message was sent; {@code context}: the character details read for it, if any.
	 */
	static ChatApi.Conversation conversation(Chat chat, Chat.Message message, String model, String system,
		boolean shareCharacter, String context)
	{
		ChatApi.Conversation conversation = new ChatApi.Conversation();
		conversation.model = model;
		conversation.system = system;
		List<Chat.Message> history = history(chat);

		// Character details go with this message unless the latest ones the provider will see are the same. Decided
		// from what's actually sent: an earlier note may have gone with a failed request or been summarised away.
		boolean newNote = shareCharacter && context != null && !context.equals(latestNote(history, message));
		message.context = newNote ? context : null;
		message.summaryVersion = chat.summaryVersion;

		// With sharing turned off, earlier notes stay out too. That changes earlier turns, so cached replies can't be
		// replayed as they were.
		boolean notesLeftOut = false;
		for (Chat.Message m : history)
		{
			notesLeftOut |= !shareCharacter && m.context != null;
		}
		String prefix = chat.summary == null ? null : summaryPrefix(chat.summary);
		for (Chat.Message m : history)
		{
			boolean user = m.role == Chat.Role.USER;
			String text = shareCharacter && m.context != null ? m.context + "\n\n" + m.text : m.text;
			if (user && prefix != null)
			{
				// The summary goes with the oldest message still sent, in the same words until the next summary, so
				// everything before later replies stays the same (for prompt caching and replay).
				text = prefix + text;
				prefix = null;
			}
			ChatApi.Turn t = new ChatApi.Turn(user, text);
			if (replayable(chat, m, notesLeftOut))
			{
				replay(t, m);
			}
			conversation.turns.add(t);
		}
		return conversation;
	}

	/**
	 * The messages the next request sends, oldest first: the player's questions that weren't left unanswered, and the
	 * replies that finished, except those the summary covers. It always starts with the player, as providers require.
	 */
	static List<Chat.Message> history(Chat chat)
	{
		List<Chat.Message> history = new ArrayList<>();
		for (Chat.Message m : chat.messages)
		{
			if (stillSent(m))
			{
				history.add(m);
			}
		}
		while (!history.isEmpty() && history.get(0).role != Chat.Role.USER)
		{
			history.remove(0);
		}
		return history;
	}

	/**
	 * Whether a message goes with the next request (if the chat's start doesn't have to give way: see {@link #history}):
	 * a question that wasn't left unanswered, or a reply that finished, and not covered by the summary. Notes and errors
	 * are for the player only.
	 */
	static boolean stillSent(Chat.Message m)
	{
		boolean sent = m.role == Chat.Role.USER && !m.unanswered || m.role == Chat.Role.ASSISTANT && !m.unfinished;
		return sent && !m.summarized;
	}

	/** The character note the provider will have seen last before {@code message}, or null. */
	private static String latestNote(List<Chat.Message> history, Chat.Message message)
	{
		String seen = null;
		for (Chat.Message m : history)
		{
			if (m != message && m.context != null)
			{
				seen = m.context;
			}
		}
		return seen;
	}

	// ------------------------------------------------------------------
	// Sending cached replies back as they came
	// ------------------------------------------------------------------

	/**
	 * Whether an earlier reply may go back exactly as it came, reasoning included (the provider also checks it was
	 * made with the same model, instructions and tools). The cached-response format accepts that only when everything before it is unchanged: not
	 * once a newer summary is sent in place of older messages, and not with character notes left out.
	 */
	static boolean replayable(Chat chat, Chat.Message m, boolean notesLeftOut)
	{
		return !notesLeftOut && m.summaryVersion == chat.summaryVersion;
	}

	private static void replay(ChatApi.Turn t, Chat.Message m)
	{
		t.rawMessages = m.rawMessages;
		t.rawKey = m.rawKey;
	}

	/** Keeps on a new reply what's needed to send it back exactly as it came next time. */
	static void recordReply(Chat.Message answer, Chat.Message question, ChatApi.Reply reply)
	{
		answer.rawMessages = reply.rawMessages;
		answer.rawKey = reply.rawKey;
		answer.summaryVersion = question.summaryVersion;
	}

	/** A reply was built on the earlier ones as plain text: keep sending them that way, or it won't match. */
	static void forgetRaw(Chat chat)
	{
		for (Chat.Message m : chat.messages)
		{
			m.rawMessages = null;
			m.rawKey = null;
		}
	}

	// ------------------------------------------------------------------
	// Summaries
	// ------------------------------------------------------------------

	/** Put before the oldest message still sent. */
	static String summaryPrefix(String summary)
	{
		return "[Summary of earlier messages in this chat: " + summary + "]\n\n";
	}

	/**
	 * The oldest messages to summarise before the next request, or none. Once more than {@link #SUMMARY_HIGH} would be
	 * sent, it's all but the newest {@link #SUMMARY_LOW} or so: the cut is made before one of the player's messages,
	 * so what's still sent starts with the player.
	 */
	static List<Chat.Message> planSummary(Chat chat)
	{
		return planSummary(chat, false);
	}

	/**
	 * {@code tooLong}: the provider said the chat is too long for its model, however few messages it has (long replies
	 * and look-ups add up). Then everything before the player's newest message is summarised.
	 */
	static List<Chat.Message> planSummary(Chat chat, boolean tooLong)
	{
		List<Chat.Message> history = history(chat);
		if (!tooLong && history.size() <= SUMMARY_HIGH)
		{
			return Collections.emptyList();
		}
		int cut = history.size() - (tooLong ? 1 : SUMMARY_LOW);
		while (cut > 0 && history.get(cut).role != Chat.Role.USER)
		{
			cut--;
		}
		return new ArrayList<>(history.subList(0, Math.max(0, cut)));
	}

	/**
	 * The request that summarises {@code old}, with the chat's summary so far, if any: the same model, no tools. The
	 * messages go as text without character notes; the next message carries the latest note again if it's needed.
	 */
	static ChatApi.Conversation summaryConversation(Chat chat, List<Chat.Message> old, String model)
	{
		ChatApi.Conversation c = new ChatApi.Conversation();
		c.model = model;
		c.system = SUMMARY_PROMPT;
		c.maxTokens = SUMMARY_MAX_TOKENS;
		StringBuilder text = new StringBuilder();
		if (chat.summary != null)
		{
			text.append("The summary so far:\n\n").append(chat.summary).append("\n\nThe conversation since then, to add to it:");
		}
		else
		{
			text.append("The conversation to summarise:");
		}
		for (Chat.Message m : old)
		{
			text.append("\n\n").append(m.role == Chat.Role.USER ? "Player: " : "Assistant: ").append(m.text);
		}
		c.turns.add(new ChatApi.Turn(true, text.toString()));
		return c;
	}

	/**
	 * Sends {@code summary} instead of {@code old} from now on. A note says so and shows the summary, so nothing leaves
	 * the conversation unseen: just before {@code question}, the message it was made for, where the player sees it (the
	 * messages it replaces can be far up the transcript). Returns the note.
	 */
	static Chat.Message applySummary(Chat chat, List<Chat.Message> old, String summary, Chat.Message question)
	{
		chat.summary = summary.trim();
		chat.summaryVersion++;
		for (Chat.Message m : old)
		{
			m.summarized = true;
		}
		// The new summary covers the old one too, so it's sent instead of every message summarised so far, including
		// any that weren't kept when the chat was saved.
		int count = chat.leftOutSummarized;
		for (Chat.Message m : chat.messages)
		{
			count += m.summarized ? 1 : 0;
		}
		String text = (count == 1 ? "Summary of the earlier message, sent instead of it:"
			: "Summary of the " + count + " earlier messages, sent instead of them:") + "\n\n" + chat.summary;
		Chat.Message note = new Chat.Message(Chat.Role.NOTE, text);
		addBefore(chat, question, note);
		return note;
	}

	/** Adds {@code note} just before {@code message}, or at the end if that isn't in the chat (any more). */
	static void addBefore(Chat chat, Chat.Message message, Chat.Message note)
	{
		int at = message == null ? -1 : chat.messages.indexOf(message);
		chat.messages.add(at < 0 ? chat.messages.size() : at, note);
	}

	/** The note when there's no summary this time. {@code reason}: the provider's error, or why it was skipped. */
	static String summaryFailed(String reason)
	{
		String r = reason == null || reason.trim().isEmpty() ? "something went wrong" : ChatApi.shorten(reason, 200);
		if (r.endsWith(".") && !r.endsWith("..."))
		{
			r = r.substring(0, r.length() - 1);
		}
		return "Couldn't summarise the earlier messages (" + r + "), so the whole chat was sent this time.";
	}
}
