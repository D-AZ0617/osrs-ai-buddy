package com.osrsaibuddy;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * What's kept of the chats between RuneLite sessions, when "Keep conversations on this PC" is on: names, messages (with what was
 * looked up for each reply), and the summary sent instead of a long chat's oldest messages; nothing else. API keys
 * aren't part of a chat, and cached replayable reasoning stays in memory only. Converting happens on the Swing EDT
 * (where chats live); reading and writing the file happen elsewhere. Files from before a field was added still load:
 * it's just missing (null, 0 or false). Fields that are no longer used (each reply's token counts) are skipped.
 */
final class ChatStore
{
	static final String FILE_NAME = "chats.json";
	/**
	 * Older messages of a long chat aren't kept, as long as they're no longer sent to the assistant (summarised, or
	 * notes and errors); a note takes their place. Messages that are still sent are kept however many there are.
	 */
	static final int MAX_MESSAGES = 200;

	private ChatStore()
	{
	}

	static final class Saved
	{
		int version = 1;
		String current;
		/** Null in a file that isn't ours. */
		List<SavedChat> chats;
	}

	static final class SavedChat
	{
        long createdAt;
		String id;
		String name;
		boolean defaultName;
		boolean namedByPlayer;
		String summary;
        List<String> wikiSources;
		int summaryVersion;
		/** See {@link Chat#leftOut}: over every save so far. */
		int leftOut;
		int leftOutSummarized;
		List<SavedMessage> messages = new ArrayList<>();
	}

	static final class SavedMessage
	{
		String role;
		String text;
		long time;
		String who;
		String context;
		boolean unanswered;
		boolean unfinished;
		boolean summarized;
		/** Null when nothing was looked up or shared. */
		List<String> activity;
	}

	/** EDT. */
	static String toJson(Gson gson, List<Chat> chats, Chat current)
	{
		Saved saved = new Saved();
		saved.current = current == null || !chats.contains(current) ? null : current.id;
		saved.chats = new ArrayList<>();
		for (Chat c : chats)
		{
			SavedChat sc = new SavedChat();
			sc.id = c.id;
            sc.createdAt = c.createdAt;
			sc.name = c.name;
			sc.defaultName = c.defaultName;
			sc.namedByPlayer = c.namedByPlayer;
			sc.summary = c.summary;
            sc.wikiSources = new ArrayList<>(c.wikiSources);
			sc.summaryVersion = c.summaryVersion;
			List<Chat.Message> messages = new ArrayList<>(c.messages);
			messages.remove(c.leftOutNote);
			int cut = cut(messages);
			int summarized = 0;
			for (Chat.Message m : messages.subList(0, cut))
			{
				summarized += m.summarized ? 1 : 0;
			}
			sc.leftOut = c.leftOut + cut;
			sc.leftOutSummarized = c.leftOutSummarized + summarized;
			for (Chat.Message m : messages.subList(cut, messages.size()))
			{
				SavedMessage sm = new SavedMessage();
				sm.role = m.role.name();
				sm.text = m.text;
				sm.time = m.time;
				sm.who = m.who;
				sm.context = m.context;
				// A question still waiting when RuneLite closes won't be answered.
				sm.unanswered = m.unanswered || c.isRunning() && m.role == Chat.Role.USER && m == lastUserMessage(c);
				sm.unfinished = m.unfinished;
				sm.summarized = m.summarized;
				sm.activity = m.activity == null || m.activity.isEmpty() ? null : new ArrayList<>(m.activity);
				sc.messages.add(sm);
			}
			saved.chats.add(sc);
		}
		return gson.newBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(saved);
	}

	/**
	 * How many of the oldest messages to leave out so that at most {@link #MAX_MESSAGES} are kept: only from the start
	 * of the chat, and only up to the first message that's still sent.
	 */
	private static int cut(List<Chat.Message> messages)
	{
		int over = messages.size() - MAX_MESSAGES;
		int cut = 0;
		while (cut < over && !ConversationBuilder.stillSent(messages.get(cut)))
		{
			cut++;
		}
		return cut;
	}

	static final class Loaded
	{
		final List<Chat> chats = new ArrayList<>();
		/** The chat that was open, if it's among them. */
		Chat current;
	}

	/** The chats in a saved file, in their order, or null if the file is damaged (not JSON, or not ours). */
	static Loaded fromJson(Gson gson, String json)
	{
		Loaded loaded = new Loaded();
		Saved saved;
		try
		{
			saved = gson.fromJson(json, Saved.class);
		}
		catch (JsonParseException | IllegalStateException e)
		{
			return null;
		}
		if (saved == null || saved.chats == null)
		{
			return null;
		}
		for (SavedChat sc : saved.chats)
		{
			if (sc == null || sc.id == null || sc.name == null)
			{
				continue;
			}
			Chat c = new Chat(sc.id, sc.name);
            if (sc.createdAt > 0) { c.createdAt = sc.createdAt; }
			c.defaultName = sc.defaultName;
			c.namedByPlayer = sc.namedByPlayer;
			c.summary = sc.summary == null || sc.summary.trim().isEmpty() ? null : sc.summary;
			c.summaryVersion = sc.summaryVersion;
            c.wikiSources.addAll(WikiAttribution.valid(sc.wikiSources));
			c.leftOut = Math.max(0, sc.leftOut);
			c.leftOutSummarized = Math.max(0, Math.min(c.leftOut, sc.leftOutSummarized));
			if (sc.messages != null)
			{
				for (SavedMessage sm : sc.messages)
				{
					Chat.Role role = role(sm == null ? null : sm.role);
					if (role == null || sm.text == null)
					{
						continue;
					}
					Chat.Message m = new Chat.Message(role, sm.text, sm.time);
					m.who = sm.who;
					m.context = sm.context;
					m.unanswered = sm.unanswered;
					m.unfinished = sm.unfinished && role == Chat.Role.ASSISTANT;
					// Without its summary, a summarised message is sent again rather than lost.
					m.summarized = sm.summarized && c.summary != null;
					m.activity = activity(sm.activity);
					c.messages.add(m);
				}
			}
			// The last question never got its answer if nothing came after it.
			if (!c.messages.isEmpty() && c.messages.get(c.messages.size() - 1).role == Chat.Role.USER)
			{
				c.messages.get(c.messages.size() - 1).unanswered = true;
			}
			if (c.leftOut > 0)
			{
				// Where the start of the chat was, so the player knows it's gone and why.
				long time = c.messages.isEmpty() ? System.currentTimeMillis() : c.messages.get(0).time;
				c.leftOutNote = new Chat.Message(Chat.Role.NOTE, leftOutNote(c.leftOut, c.leftOutSummarized), time);
				c.messages.add(0, c.leftOutNote);
			}
			loaded.chats.add(c);
			if (sc.id.equals(saved.current))
			{
				loaded.current = c;
			}
		}
		return loaded;
	}

	/** What the note in place of the messages that weren't kept says. */
	static String leftOutNote(int leftOut, int summarized)
	{
		String covered = summarized > 0 ? ": the summary covers " + (leftOut == 1 ? "it." : "them.") : ".";
		if (leftOut == 1)
		{
			return "The oldest message of this chat wasn't kept when it was saved (OSRS AI Buddy saves the latest "
				+ MAX_MESSAGES + " of each chat). It wasn't being sent to the assistant any more" + covered;
		}
		return "The " + leftOut + " oldest messages of this chat weren't kept when it was saved (OSRS AI Buddy saves the "
			+ "latest " + MAX_MESSAGES + " of each chat). None of them were being sent to the assistant any more" + covered;
	}

	/** The saved lines, without any a hand-edited file left empty; null for none. */
	private static List<String> activity(List<String> saved)
	{
		if (saved == null)
		{
			return null;
		}
		List<String> lines = new ArrayList<>();
		for (String line : saved)
		{
			if (line != null && !line.trim().isEmpty())
			{
				lines.add(line);
			}
		}
		return lines.isEmpty() ? null : lines;
	}

	private static Chat.Role role(String name)
	{
		if (name == null)
		{
			return null;
		}
		for (Chat.Role r : Chat.Role.values())
		{
			if (r.name().equals(name))
			{
				return r;
			}
		}
		return null;
	}

	private static Chat.Message lastUserMessage(Chat c)
	{
		for (int i = c.messages.size() - 1; i >= 0; i--)
		{
			if (c.messages.get(i).role == Chat.Role.USER)
			{
				return c.messages.get(i);
			}
		}
		return null;
	}
}
