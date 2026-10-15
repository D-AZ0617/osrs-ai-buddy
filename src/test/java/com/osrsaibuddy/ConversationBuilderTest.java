package com.osrsaibuddy;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** What a chat sends: the messages, character notes, replayed replies, and the summary of a long chat's oldest messages. */
public class ConversationBuilderTest
{
	private static final JsonArray RAW = new Gson().fromJson("[{\"role\":\"assistant\",\"content\":["
		+ "{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"s\"},{\"type\":\"text\",\"text\":\"A1\"}]}]", JsonArray.class);

	private static Chat.Message add(Chat chat, Chat.Role role, String text)
	{
		Chat.Message m = new Chat.Message(role, text);
		chat.messages.add(m);
		return m;
	}

	private static Chat.Message user(Chat chat, String text)
	{
		return add(chat, Chat.Role.USER, text);
	}

	private static Chat.Message reply(Chat chat, String text)
	{
		Chat.Message m = add(chat, Chat.Role.ASSISTANT, text);
		m.who = "Claude";
		return m;
	}

	/** {@code pairs} questions and answers: Q1, A1, Q2, A2... */
	private static Chat chatOf(int pairs)
	{
		Chat chat = new Chat("long");
		for (int i = 1; i <= pairs; i++)
		{
			user(chat, "Q" + i);
			reply(chat, "A" + i);
		}
		return chat;
	}

	private static ChatApi.Conversation send(Chat chat, Chat.Message message, boolean shareCharacter, String context)
	{
		return ConversationBuilder.conversation(chat, message, "claude-opus-5-5", "Be brief.", shareCharacter, context);
	}

	private static List<String> texts(ChatApi.Conversation c)
	{
		List<String> texts = new ArrayList<>();
		for (ChatApi.Turn t : c.turns)
		{
			texts.add((t.user ? "user: " : "assistant: ") + t.text);
		}
		return texts;
	}

	@Test
	public void onlyAnsweredQuestionsAndRepliesAreSent()
	{
		Chat chat = new Chat("x");
		// A reply with no question before it (the question was cleared away) can't start a conversation.
		reply(chat, "orphan");
		user(chat, "Q1");
		reply(chat, "A1");
		user(chat, "Q2").unanswered = true;
		add(chat, Chat.Role.NOTE, "Stopped.");
		add(chat, Chat.Role.ERROR, "Anthropic is overloaded.");
		Chat.Message q3 = user(chat, "Q3");

		ChatApi.Conversation c = send(chat, q3, false, null);
		assertEquals("claude-opus-5-5", c.model);
		assertEquals("Be brief.", c.system);
		assertEquals(List.of("user: Q1", "assistant: A1", "user: Q3"), texts(c));
		assertTrue(c.tools.isEmpty());
		assertEquals(0, c.maxTokens);
	}

	@Test
	public void theCharacterNoteGoesOnlyWhenItChanged()
	{
		Chat chat = new Chat("x");
		Chat.Message q1 = user(chat, "Q1");
		assertEquals(List.of("user: [C1]\n\nQ1"), texts(send(chat, q1, true, "[C1]")));
		assertEquals("[C1]", q1.context);
		reply(chat, "A1");

		// The same details again: the provider has them already.
		Chat.Message q2 = user(chat, "Q2");
		assertEquals(List.of("user: [C1]\n\nQ1", "assistant: A1", "user: Q2"), texts(send(chat, q2, true, "[C1]")));
		assertNull(q2.context);
		reply(chat, "A2");

		// Changed (a level up): sent again.
		Chat.Message q3 = user(chat, "Q3");
		send(chat, q3, true, "[C2]");
		assertEquals("[C2]", q3.context);
		reply(chat, "A3");

		// Not readable this time (logged out): nothing goes with it.
		Chat.Message q4 = user(chat, "Q4");
		send(chat, q4, true, null);
		assertNull(q4.context);
	}

	@Test
	public void aNoteThatWasntSeenIsSentAgain()
	{
		// It went with a question that failed, so the provider never saw it.
		Chat chat = new Chat("x");
		Chat.Message q1 = user(chat, "Q1");
		q1.context = "[C1]";
		q1.unanswered = true;
		Chat.Message q2 = user(chat, "Q2");
		assertEquals(List.of("user: [C1]\n\nQ2"), texts(send(chat, q2, true, "[C1]")));

		// It went with a message the summary now covers.
		reply(chat, "A2");
		q2.summarized = true;
		chat.messages.get(chat.messages.size() - 1).summarized = true;
		chat.summary = "Talked about Agility.";
		Chat.Message q3 = user(chat, "Q3");
		send(chat, q3, true, "[C1]");
		assertEquals("[C1]", q3.context);

		// A question sent again (Retry) has its note decided again too.
		reply(chat, "A3");
		Chat.Message q4 = user(chat, "Q4");
		q4.context = "[C1]";
		send(chat, q4, true, "[C1]");
		assertNull(q4.context);
	}

	@Test
	public void withSharingOffEarlierNotesStayOutAndRepliesGoAsText()
	{
		Chat chat = new Chat("x");
		Chat.Message q1 = user(chat, "Q1");
		q1.context = "[C1]";
		Chat.Message a1 = reply(chat, "A1");
		a1.rawMessages = RAW;
		a1.rawKey = "key";
		Chat.Message q2 = user(chat, "Q2");

		ChatApi.Conversation off = send(chat, q2, false, null);
		assertEquals(List.of("user: Q1", "assistant: A1", "user: Q2"), texts(off));
		assertNull("Q1 changed, so A1 can't go back as it came", off.turns.get(1).rawMessages);

		ChatApi.Conversation on = send(chat, q2, true, "[C1]");
		assertEquals(List.of("user: [C1]\n\nQ1", "assistant: A1", "user: Q2"), texts(on));
		assertSame(RAW, on.turns.get(1).rawMessages);
		assertEquals("the provider checks the key", "key", on.turns.get(1).rawKey);
	}

	@Test
	public void repliesGoBackAsTheyCameOnlyUnderTheSameSummary()
	{
		Chat chat = new Chat("x");
		Chat.Message q1 = user(chat, "Q1");
		ChatApi.Reply r = new ChatApi.Reply();
		r.text = "A1";
		r.model = "claude-opus-5-5";
		r.rawMessages = RAW;
		r.rawKey = "key";
		send(chat, q1, false, null);
		Chat.Message a1 = reply(chat, "A1");
		ConversationBuilder.recordReply(a1, q1, r);
		assertSame(RAW, a1.rawMessages);
		assertEquals("key", a1.rawKey);
		assertEquals(0, a1.summaryVersion);

		assertTrue(ConversationBuilder.replayable(chat, a1, false));
		assertFalse("character notes left out", ConversationBuilder.replayable(chat, a1, true));
		Chat.Message q2 = user(chat, "Q2");
		assertSame(RAW, send(chat, q2, false, null).turns.get(1).rawMessages);

		// A newer summary changed what came before A1.
		chat.summaryVersion = 1;
		assertFalse(ConversationBuilder.replayable(chat, a1, false));
		assertNull(send(chat, q2, false, null).turns.get(1).rawMessages);
		assertEquals("the question records the summary it went with", 1, q2.summaryVersion);

		// A reply made since then goes back as it came.
		Chat.Message a2 = reply(chat, "A2");
		ConversationBuilder.recordReply(a2, q2, r);
		assertEquals(1, a2.summaryVersion);
		Chat.Message q3 = user(chat, "Q3");
		ChatApi.Conversation c = send(chat, q3, false, null);
		assertNull(c.turns.get(1).rawMessages);
		assertSame(RAW, c.turns.get(3).rawMessages);

		// A reply built on plain-text history: everything goes as text from now on.
		ConversationBuilder.forgetRaw(chat);
		assertNull(a2.rawMessages);
		assertNull(a2.rawKey);
		assertNull(send(chat, q3, false, null).turns.get(3).rawMessages);
	}

	@Test
	public void summarisingWaitsUntilTheChatIsLong()
	{
		Chat chat = chatOf(20);
		assertEquals(ConversationBuilder.SUMMARY_HIGH, ConversationBuilder.history(chat).size());
		assertTrue(ConversationBuilder.planSummary(chat).isEmpty());

		// Messages that aren't sent don't count.
		user(chat, "never answered").unanswered = true;
		add(chat, Chat.Role.NOTE, "Stopped.");
		add(chat, Chat.Role.ERROR, "Couldn't reach Anthropic.");
		assertTrue(ConversationBuilder.planSummary(chat).isEmpty());

		// One more and it's time.
		Chat.Message q21 = user(chat, "Q21");
		List<Chat.Message> old = ConversationBuilder.planSummary(chat);
		assertEquals(24, old.size());
		assertEquals("Q1", old.get(0).text);
		assertEquals("A12", old.get(old.size() - 1).text);
		assertFalse(old.contains(q21));
		// What's left starts with the player and keeps at least the newest LOW messages.
		List<Chat.Message> history = ConversationBuilder.history(chat);
		Chat.Message firstKept = history.get(old.size());
		assertEquals("Q13", firstKept.text);
		assertTrue(history.size() - old.size() >= ConversationBuilder.SUMMARY_LOW);
	}

	@Test
	public void theCutIsAlwaysBeforeOneOfThePlayersMessages()
	{
		// Replies in a row (as an old saved chat might have) move the cut back to the nearest question.
		Chat chat = new Chat("x");
		for (int i = 1; i <= 20; i++)
		{
			user(chat, "Q" + i);
		}
		for (int i = 1; i <= 21; i++)
		{
			reply(chat, "A" + i);
		}
		user(chat, "Q21");
		List<Chat.Message> old = ConversationBuilder.planSummary(chat);
		assertEquals("Q20", ConversationBuilder.history(chat).get(old.size()).text);
		assertEquals(19, old.size());

		// No question to cut before: nothing is summarised.
		Chat replies = new Chat("y");
		user(replies, "Q1");
		for (int i = 0; i < 45; i++)
		{
			reply(replies, "A" + i);
		}
		assertTrue(ConversationBuilder.planSummary(replies).isEmpty());
	}

	@Test
	public void aSummaryIsSentInsteadOfTheOldestMessages()
	{
		Chat chat = chatOf(20);
		Chat.Message stopped = user(chat, "stopped question");
		stopped.unanswered = true;
		Chat.Message q21 = user(chat, "Q21");
		List<Chat.Message> old = ConversationBuilder.planSummary(chat);
		Chat.Message note = ConversationBuilder.applySummary(chat, old, "  The player trains Agility.  ", q21);

		assertEquals("The player trains Agility.", chat.summary);
		assertEquals(1, chat.summaryVersion);
		for (Chat.Message m : old)
		{
			assertTrue(m.text, m.summarized);
		}
		assertFalse(q21.summarized);
		assertFalse("only messages that were sent are summarised", stopped.summarized);
		// The note sits right before the question it was made for, where the player sees it, and shows the summary.
		assertSame(note, chat.messages.get(chat.messages.indexOf(q21) - 1));
		assertEquals(Chat.Role.NOTE, note.role);
		assertEquals("Summary of the 24 earlier messages, sent instead of them:\n\nThe player trains Agility.", note.text);
		int a12 = chat.messages.indexOf(old.get(old.size() - 1));
		assertEquals("Q13", chat.messages.get(a12 + 1).text);

		ChatApi.Conversation c = send(chat, q21, false, null);
		assertEquals(17, c.turns.size());
		assertEquals("[Summary of earlier messages in this chat: The player trains Agility.]\n\nQ13", c.turns.get(0).text);
		assertTrue(c.turns.get(0).user);
		assertEquals("Q21", c.turns.get(16).text);
		assertTrue(ConversationBuilder.planSummary(chat).isEmpty());
	}

	@Test
	public void aLaterSummaryCoversTheEarlierOneToo()
	{
		Chat chat = chatOf(20);
		Chat.Message q21 = user(chat, "Q21");
		ConversationBuilder.applySummary(chat, ConversationBuilder.planSummary(chat), "S1", q21);
		reply(chat, "A21");
		for (int i = 22; i <= 33; i++)
		{
			user(chat, "Q" + i);
			if (i < 33)
			{
				reply(chat, "A" + i);
			}
		}
		List<Chat.Message> old = ConversationBuilder.planSummary(chat);
		assertEquals("Q13", old.get(0).text);

		ChatApi.Conversation request = ConversationBuilder.summaryConversation(chat, old, "claude-opus-5-5");
		assertTrue(request.turns.get(0).text, request.turns.get(0).text.startsWith(
			"The summary so far:\n\nS1\n\nThe conversation since then, to add to it:\n\nPlayer: Q13\n\nAssistant: A13\n\n"));

		Chat.Message q33 = chat.messages.get(chat.messages.size() - 1);
		ConversationBuilder.applySummary(chat, old, "S2", q33);
		assertEquals(2, chat.summaryVersion);
		assertTrue(chat.messages.stream().anyMatch(m -> m.text.startsWith("Summary of the " + (24 + old.size()) + " earlier messages")));
		assertTrue(send(chat, chat.messages.get(chat.messages.size() - 1), false, null).turns.get(0).text
			.startsWith("[Summary of earlier messages in this chat: S2]\n\n"));
	}

	@Test
	public void theSummaryPrefixStaysTheSameUntilTheNextSummary()
	{
		Chat chat = chatOf(20);
		Chat.Message q21 = user(chat, "Q21");
		ConversationBuilder.applySummary(chat, ConversationBuilder.planSummary(chat), "S1", q21);
		// The oldest message still sent went with character details.
		ConversationBuilder.history(chat).get(0).context = "[C1]";
		String first = send(chat, q21, true, "[C1]").turns.get(0).text;
		assertEquals("[Summary of earlier messages in this chat: S1]\n\n[C1]\n\nQ13", first);

		reply(chat, "A21");
		Chat.Message q22 = user(chat, "Q22");
		ChatApi.Conversation later = send(chat, q22, true, "[C1]");
		assertEquals(first, later.turns.get(0).text);
		assertEquals("[Summary of earlier messages in this chat: S1]\n\n", ConversationBuilder.summaryPrefix("S1"));
	}

	@Test
	public void theSummaryRequest()
	{
		Chat chat = new Chat("x");
		Chat.Message q1 = user(chat, "Q1");
		q1.context = "[Character: Zezima]";
		reply(chat, "A1");
		ChatApi.Conversation c = ConversationBuilder.summaryConversation(chat, new ArrayList<>(chat.messages), "gpt-x");
		assertEquals("gpt-x", c.model);
		assertEquals(ConversationBuilder.SUMMARY_PROMPT, c.system);
		assertEquals(ConversationBuilder.SUMMARY_MAX_TOKENS, c.maxTokens);
		assertTrue(c.tools.isEmpty());
		assertNull(c.toolRunner);
		assertEquals(1, c.turns.size());
		assertTrue(c.turns.get(0).user);
		// Character notes stay out: the next message carries the latest one again if it's needed.
		assertEquals("The conversation to summarise:\n\nPlayer: Q1\n\nAssistant: A1", c.turns.get(0).text);
		assertTrue(ConversationBuilder.SUMMARY_PROMPT.endsWith("Plain text, at most 250 words."));
	}

	@Test
	public void summaryNotes()
	{
		assertEquals("Couldn't summarise the earlier messages (Anthropic is overloaded or having trouble right now. Try again "
				+ "shortly), so the whole chat was sent this time.",
			ConversationBuilder.summaryFailed("Anthropic is overloaded or having trouble right now. Try again shortly."));
		assertEquals("Couldn't summarise the earlier messages (you pressed Stop), so the whole chat was sent this time.",
			ConversationBuilder.summaryFailed("you pressed Stop"));
		assertEquals("Couldn't summarise the earlier messages (something went wrong), so the whole chat was sent this time.",
			ConversationBuilder.summaryFailed(null));

		Chat chat = new Chat("x");
		Chat.Message q1 = user(chat, "Q1");
		Chat.Message q2 = user(chat, "Q2");
		ConversationBuilder.applySummary(chat, List.of(q1), "S", q2);
		assertEquals("Summary of the earlier message, sent instead of it:\n\nS", chat.messages.get(1).text);
		assertSame(q2, chat.messages.get(2));
	}
}

