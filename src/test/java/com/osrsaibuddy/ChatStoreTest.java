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

/** What "Save chat history" keeps, and how it comes back. */
public class ChatStoreTest
{
	private final Gson gson = new Gson();

	@Test
	public void chatsComeBackAsTheyWere()
	{
		Chat first = new Chat("Agility");
		first.defaultName = false;
		Chat.Message q = new Chat.Message(Chat.Role.USER, "best way to train Agility at 50?");
		q.context = "[Character: Zezima, combat level 3]";
		first.messages.add(q);
		Chat.Message a = new Chat.Message(Chat.Role.ASSISTANT, "Rooftop courses.");
		a.who = "Claude";
		a.rawMessages = gson.fromJson("[{\"role\":\"assistant\",\"content\":[{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"s\"}]}]", JsonArray.class);
		a.rawKey = "claude-opus-5-5";
		first.messages.add(a);
		Chat second = new Chat("Named");
		second.namedByPlayer = true;

		String json = ChatStore.toJson(gson, List.of(first, second), second);
		ChatStore.Loaded loaded = ChatStore.fromJson(gson, json);

		assertEquals(2, loaded.chats.size());
		assertSame("the chat that was open", loaded.chats.get(1), loaded.current);
		Chat back = loaded.chats.get(0);
		assertEquals(first.id, back.id);
		assertEquals("Agility", back.name);
		assertFalse(back.defaultName);
		assertTrue(loaded.chats.get(1).namedByPlayer);
		assertEquals(2, back.messages.size());
		assertEquals(Chat.Role.USER, back.messages.get(0).role);
		assertEquals(q.time, back.messages.get(0).time);
		assertEquals("[Character: Zezima, combat level 3]", back.messages.get(0).context);
		assertEquals("Claude", back.messages.get(1).who);
		// cached replayable reasoning isn't saved; the chat carries on as plain text.
		assertNull(back.messages.get(1).rawMessages);
		assertNull(back.messages.get(1).rawKey);
		assertFalse(json.contains("signature"));
	}

	@Test
	public void anUnfinishedReplyStaysUnfinished()
	{
		Chat c = new Chat("x");
		Chat.Message q = new Chat.Message(Chat.Role.USER, "q");
		q.unanswered = true;
		c.messages.add(q);
		Chat.Message partial = new Chat.Message(Chat.Role.ASSISTANT, "Half a");
		partial.unfinished = true;
		c.messages.add(partial);
		c.messages.add(new Chat.Message(Chat.Role.NOTE, "Stopped."));
		Chat back = ChatStore.fromJson(gson, ChatStore.toJson(gson, List.of(c), c)).chats.get(0);
		assertTrue(back.messages.get(1).unfinished);
		assertTrue("never sent", ConversationBuilder.history(back).isEmpty());
		assertSame(back.messages.get(0), RequestRunner.retryable(back));
	}

	@Test
	public void aQuestionWithoutAnAnswerIsMarkedSo()
	{
		Chat c = new Chat("x");
		c.messages.add(new Chat.Message(Chat.Role.USER, "still waiting when RuneLite closed"));
		c.pending = new ChatApi.Pending();
		Chat back = ChatStore.fromJson(gson, ChatStore.toJson(gson, List.of(c), c)).chats.get(0);
		assertTrue(back.messages.get(0).unanswered);
		assertFalse(back.isRunning());
	}

	@Test
	public void aQuestionSummarisedBecauseTheChatWasTooLongCanBeRetried()
	{
		// RuneLite closed while a chat too long for the model was being summarised before the question went again.
		Chat c = new Chat("x");
		c.messages.add(new Chat.Message(Chat.Role.USER, "q1"));
		c.messages.add(new Chat.Message(Chat.Role.ASSISTANT, "a1"));
		Chat.Message q = new Chat.Message(Chat.Role.USER, "q2");
		c.messages.add(q);
		c.messages.add(new Chat.Message(Chat.Role.NOTE, "This chat was too long for claude-opus-5-5, so the earlier "
			+ "messages are summarised first and the question is sent again."));
		c.pending = new ChatApi.Pending();
		Chat back = ChatStore.fromJson(gson, ChatStore.toJson(gson, List.of(c), c)).chats.get(0);
		assertTrue(back.messages.get(2).unanswered);
		assertSame(back.messages.get(2), RequestRunner.retryable(back));
	}

	@Test
	public void longChatsKeepTheirLatestMessagesAndSaySo()
	{
		Chat c = new Chat("long");
		c.summary = "The player is training Agility.";
		for (int i = 0; i < 250; i++)
		{
			Chat.Message m = new Chat.Message(i % 2 == 0 ? Chat.Role.USER : Chat.Role.ASSISTANT, "m" + i);
			m.summarized = i < 150;
			c.messages.add(m);
		}
		Chat back = ChatStore.fromJson(gson, ChatStore.toJson(gson, List.of(c), c)).chats.get(0);
		assertEquals(ChatStore.MAX_MESSAGES + 1, back.messages.size());
		assertEquals("m50", back.messages.get(1).text);
		assertEquals("m249", back.messages.get(back.messages.size() - 1).text);
		Chat.Message note = back.messages.get(0);
		assertEquals(Chat.Role.NOTE, note.role);
		assertSame(note, back.leftOutNote);
		assertEquals("The 50 oldest messages of this chat weren't kept when it was saved (OSRS AI Buddy saves the latest 200 "
			+ "of each chat). None of them were being sent to the assistant any more: the summary covers them.", note.text);
		assertEquals("sent as before", 100, ConversationBuilder.history(back).size());

		// Saved again: one note, for every message left out so far.
		back.messages.add(new Chat.Message(Chat.Role.NOTE, "Stopped."));
		Chat again = ChatStore.fromJson(gson, ChatStore.toJson(gson, List.of(back), back)).chats.get(0);
		assertEquals(51, again.leftOut);
		assertEquals(51, again.leftOutSummarized);
		assertEquals(ChatStore.MAX_MESSAGES + 1, again.messages.size());
		assertTrue(again.messages.get(0).text.startsWith("The 51 oldest messages"));

		// The next summary still counts them.
		List<Chat.Message> old = new ArrayList<>(ConversationBuilder.history(again).subList(0, 2));
		Chat.Message summary = ConversationBuilder.applySummary(again, old, "Still Agility.", null);
		assertTrue(summary.text, summary.text.startsWith("Summary of the 152 earlier messages"));
	}

	@Test
	public void messagesStillSentAreAlwaysKept()
	{
		// A chat whose summaries kept failing: everything is still sent, so everything is kept.
		Chat c = new Chat("long");
		for (int i = 0; i < 250; i++)
		{
			c.messages.add(new Chat.Message(i % 2 == 0 ? Chat.Role.USER : Chat.Role.ASSISTANT, "m" + i));
		}
		Chat back = ChatStore.fromJson(gson, ChatStore.toJson(gson, List.of(c), c)).chats.get(0);
		assertEquals(250, back.messages.size());
		assertEquals("m0", back.messages.get(0).text);
		assertNull(back.leftOutNote);

		// Old notes and errors go first, up to the first message still sent.
		Chat noisy = new Chat("noisy");
		for (int i = 0; i < 30; i++)
		{
			noisy.messages.add(new Chat.Message(Chat.Role.ERROR, "e" + i));
		}
		noisy.messages.addAll(c.messages.subList(0, 190));
		back = ChatStore.fromJson(gson, ChatStore.toJson(gson, List.of(noisy), noisy)).chats.get(0);
		assertEquals(ChatStore.MAX_MESSAGES + 1, back.messages.size());
		assertEquals("e20", back.messages.get(1).text);
		assertTrue(back.messages.get(0).text, back.messages.get(0).text.endsWith("None of them were being sent to the assistant any more."));
	}

	@Test
	public void aDamagedFileIsReportedAndOddEntriesAreSkipped()
	{
		// Damaged: reported as such (the plugin moves the file aside rather than writing over it).
		assertNull(ChatStore.fromJson(gson, "not json {"));
		assertNull(ChatStore.fromJson(gson, ""));
		assertNull(ChatStore.fromJson(gson, "{\"something\":\"else\"}"));
		ChatStore.Loaded odd = ChatStore.fromJson(gson,
			"{\"chats\":[{\"id\":\"1\",\"name\":\"ok\",\"messages\":[{\"role\":\"WHAT\",\"text\":\"skipped\"},{\"role\":\"NOTE\",\"text\":\"kept\"}]},{\"name\":\"no id\"}]}");
		assertEquals(1, odd.chats.size());
		assertEquals(1, odd.chats.get(0).messages.size());
		assertEquals("kept", odd.chats.get(0).messages.get(0).text);
		assertNull(odd.current);
	}

	@Test
	public void summariesAreRemembered()
	{
		Chat c = new Chat("long");
		Chat.Message q1 = new Chat.Message(Chat.Role.USER, "Q1");
		q1.summarized = true;
		c.messages.add(q1);
		Chat.Message a1 = new Chat.Message(Chat.Role.ASSISTANT, "A1");
		a1.summarized = true;
		c.messages.add(a1);
		c.messages.add(new Chat.Message(Chat.Role.NOTE, "Summary of the 2 earlier messages, sent instead of them:\n\nS"));
		c.messages.add(new Chat.Message(Chat.Role.USER, "Q2"));
		c.messages.add(new Chat.Message(Chat.Role.ASSISTANT, "A2"));
		c.summary = "S";
		c.summaryVersion = 3;

		Chat back = ChatStore.fromJson(gson, ChatStore.toJson(gson, List.of(c), c)).chats.get(0);
		assertEquals("S", back.summary);
		assertEquals(3, back.summaryVersion);
		assertEquals(5, back.messages.size());
		assertTrue(back.messages.get(0).summarized);
		assertTrue(back.messages.get(1).summarized);
		assertFalse(back.messages.get(3).summarized);
		assertFalse(back.messages.get(4).summarized);
		// Sent as before: the summary, then what it doesn't cover.
		List<ChatApi.Turn> turns = ConversationBuilder.conversation(back, back.messages.get(3), "m", "s", false, null).turns;
		assertEquals(2, turns.size());
		assertEquals("[Summary of earlier messages in this chat: S]\n\nQ2", turns.get(0).text);
	}

	@Test
	public void filesFromBeforeSummariesStillLoad()
	{
		// As OSRS AI Buddy 0.1 wrote it.
		String old = "{\"version\":1,\"current\":\"a\",\"chats\":[{\"id\":\"a\",\"name\":\"Old\",\"defaultName\":false,"
			+ "\"namedByPlayer\":false,\"messages\":[{\"role\":\"USER\",\"text\":\"hi\",\"time\":1,\"unanswered\":false},"
			+ "{\"role\":\"ASSISTANT\",\"text\":\"hello\",\"time\":2,\"who\":\"Claude\",\"unanswered\":false}]}]}";
		ChatStore.Loaded loaded = ChatStore.fromJson(gson, old);
		Chat back = loaded.chats.get(0);
		assertSame(back, loaded.current);
		assertNull(back.summary);
		assertEquals(0, back.summaryVersion);
		assertEquals(2, back.messages.size());
		assertFalse(back.messages.get(0).summarized);
		assertEquals(2, ConversationBuilder.history(back).size());
		// Nor what was looked up.
		assertNull(back.messages.get(1).activity);
	}

	@Test
	public void lookUpsAreRemembered()
	{
		Chat c = new Chat("Vorkath");
		c.messages.add(new Chat.Message(Chat.Role.USER, "Vorkath's weaknesses?"));
		Chat.Message a = new Chat.Message(Chat.Role.ASSISTANT, "Stab and dragonbane.");
		a.who = "Claude";
		a.activity = new ArrayList<>(List.of("Searched the Wiki for \"vorkath\"", "Read the Wiki page \"Vorkath\""));
		c.messages.add(a);
		Chat.Message stopped = new Chat.Message(Chat.Role.NOTE, "Stopped.");
		stopped.activity = new ArrayList<>();
		c.messages.add(stopped);

		String json = ChatStore.toJson(gson, List.of(c), c);
		Chat.Message back = ChatStore.fromJson(gson, json).chats.get(0).messages.get(1);
		assertEquals(a.activity, back.activity);
		// Nothing looked up: nothing saved.
		assertNull(ChatStore.fromJson(gson, json).chats.get(0).messages.get(2).activity);
	}

	@Test
	public void filesWithTokenCountsStillLoad()
	{
		// As OSRS AI Buddy wrote it while it kept each reply's tokens: they're skipped, and not written again.
		String old = "{\"version\":1,\"current\":\"a\",\"chats\":[{\"id\":\"a\",\"name\":\"Vorkath\",\"messages\":["
			+ "{\"role\":\"USER\",\"text\":\"Vorkath's weaknesses?\",\"time\":1},"
			+ "{\"role\":\"ASSISTANT\",\"text\":\"Stab and dragonbane.\",\"time\":2,\"who\":\"Claude\","
			+ "\"activity\":[\"Read the Wiki page \\\"Vorkath\\\"\"],\"usage\":{\"input\":1204,\"cacheRead\":3410,"
			+ "\"cacheWrite\":12,\"output\":352,\"incomplete\":false,\"model\":\"claude-opus-5-5\"}},"
			+ "{\"role\":\"NOTE\",\"text\":\"Stopped.\",\"time\":3,\"usage\":{\"input\":0,\"cacheRead\":0,"
			+ "\"cacheWrite\":0,\"output\":0,\"incomplete\":true}}]}]}";
		ChatStore.Loaded loaded = ChatStore.fromJson(gson, old);
		Chat back = loaded.chats.get(0);
		assertSame(back, loaded.current);
		assertEquals(3, back.messages.size());
		Chat.Message reply = back.messages.get(1);
		assertEquals("Stab and dragonbane.", reply.text);
		assertEquals("Claude", reply.who);
		assertEquals(List.of("Read the Wiki page \"Vorkath\""), reply.activity);
		assertEquals("Stopped.", back.messages.get(2).text);
		assertFalse(ChatStore.toJson(gson, loaded.chats, back).contains("usage"));
	}

	@Test
	public void oddLookUpsInAFileAreTidied()
	{
		ChatStore.Loaded loaded = ChatStore.fromJson(gson, "{\"chats\":[{\"id\":\"a\",\"name\":\"x\",\"messages\":["
			+ "{\"role\":\"ASSISTANT\",\"text\":\"a\",\"activity\":[\"\",null,\"Shared your bank\"]}]}]}");
		assertEquals(List.of("Shared your bank"), loaded.chats.get(0).messages.get(0).activity);
	}

	@Test
	public void summarisedMessagesWithoutTheirSummaryAreSentAgain()
	{
		// Not something OSRS AI Buddy writes, but a hand-edited file shouldn't make messages disappear from the conversation.
		ChatStore.Loaded loaded = ChatStore.fromJson(gson, "{\"chats\":[{\"id\":\"a\",\"name\":\"x\",\"summary\":\" \","
			+ "\"messages\":[{\"role\":\"USER\",\"text\":\"Q1\",\"summarized\":true},{\"role\":\"ASSISTANT\",\"text\":\"A1\",\"summarized\":true}]}]}");
		Chat back = loaded.chats.get(0);
		assertNull(back.summary);
		assertFalse(back.messages.get(0).summarized);
		assertEquals(2, ConversationBuilder.history(back).size());
	}
}

