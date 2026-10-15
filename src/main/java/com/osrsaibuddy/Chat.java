package com.osrsaibuddy;

import com.google.gson.JsonArray;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** One conversation in the panel. Kept in memory while the plugin runs. Only touched on the Swing EDT. */
class Chat
{
	enum Role
	{
		USER, ASSISTANT, NOTE, ERROR
	}

	static class Message
	{
		final Role role;
		final String text;
		final long time;
		/** Who wrote an ASSISTANT reply: ChatGPT, using the authorized plan. */
		String who;
		/** Character details sent along with this USER message, if any. Part of what the provider saw. */
		String context;
		/** A USER message whose request failed or was stopped. Left out of later requests. */
		boolean unanswered;
		/**
		 * An ASSISTANT reply that never finished: what was shown of it before it was stopped or broke off, kept for the
		 * player to read (the note or error after it says why). Never sent.
		 */
		boolean unfinished;
		/** Covered by the chat's {@link Chat#summary}: still shown, but no longer sent. */
		boolean summarized;
		/**
		 * The chat's {@link Chat#summaryVersion} when this was sent or answered. A reply goes back exactly as it came
		 * only while that's unchanged: a new summary changes what came before it.
		 */
		int summaryVersion;
		/**
		 * Cached-response compatibility: the messages this reply was made of (its look-ups included), exactly as exchanged, and the
		 * {@link ChatApi#promptKey} they were made under. The original replay format expects them back unchanged on the next turn, as long as
		 * nothing before them changed. In memory only.
		 */
		JsonArray rawMessages;
		String rawKey;
		/**
		 * What was looked up or shared while this was being answered, one line each ("Searched the Wiki for ..."): on
		 * the reply, or on the error or note that ended the request. Empty or null when nothing was.
		 */
		List<String> activity;

		Message(Role role, String text)
		{
			this(role, text, System.currentTimeMillis());
		}

		Message(Role role, String text, long time)
		{
			this.role = role;
			this.text = text;
			this.time = time;
		}
	}

	final String id;
    long createdAt = System.currentTimeMillis();
	String name;
	/** Whether {@link #name} was made up by us, so the first message can replace it with something better. */
	boolean defaultName = true;
	/** The player named this chat themselves, so the name can appear in notifications (it isn't their question). */
	boolean namedByPlayer;
	final List<Message> messages = new ArrayList<>();
	/**
	 * What the oldest messages were summarised to, sent instead of them (see {@link ConversationBuilder}); null until a
	 * chat gets long.
	 */
	String summary;
	/** Goes up with every new summary. */
	int summaryVersion;
	/**
	 * How many of the chat's oldest messages weren't kept when it was saved in an earlier session, and how many of those
	 * the summary covers (see {@link ChatStore}). Messages still sent to the assistant are never among them.
	 */
	int leftOut;
	int leftOutSummarized;
	/** The note shown in their place, made when the chat was loaded; not saved itself. Null when none were left out. */
	Message leftOutNote;

	/** The request in flight, or null. */
	ChatApi.Pending pending;
	/** When the request in flight started: the summary, then the question itself. */
	long runStartedAt;
	/**
	 * While the oldest messages are being summarised before a question goes out: carries on without the summary, which
	 * is what Stop does then. Null the rest of the time.
	 */
	Runnable skipSummary;

	// What the panel shows of the request in flight, while it runs.

	/** Who's answering: ChatGPT, using the authorized plan. */
	String answering;
	/** The reply so far, as it streams in; null until its first words. */
	String liveText;
	/** What has been looked up or shared for it so far; it goes with the message that ends the request. */
	List<String> liveActivity = new ArrayList<>();
	/** The model has stopped writing to look things up, and hasn't carried on yet. */
	boolean lookingUp;
	/** The reply's text when the look-ups started: only text beyond it means the model is writing again. */
	String lookupAfter;
	/** The provider was busy: why ("ChatGPT is busy"), and when it's asked again. */
	String retryWhy;
	long retryAt;

	Chat(String name)
	{
		this(UUID.randomUUID().toString(), name);
	}

	/** A chat brought back from {@link ChatStore}. */
	Chat(String id, String name)
	{
		this.id = id;
		this.name = name;
	}

	boolean isRunning()
	{
		return pending != null;
	}

	boolean isSummarizing()
	{
		return skipSummary != null;
	}

	/** A new request starts (or the last one ended): nothing of it to show yet. */
	void resetLive()
	{
		liveText = null;
		liveActivity = new ArrayList<>();
		lookingUp = false;
		lookupAfter = null;
		retryWhy = null;
		retryAt = 0;
	}
}
