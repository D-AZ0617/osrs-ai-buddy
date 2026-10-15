package com.osrsaibuddy;

import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * "Save chat history": opens the saved chats once and brings them back, saves the chats a moment after they change, and
 * deletes the saved copy when the setting is turned off. Runs on the Swing EDT, where the chats live; the file work
 * goes to {@code executor}, and what it found comes back through {@code edt}.
 */
final class ChatSaver
{
	/** The saved chats on disk: {@link ChatFile} in the game, a stand-in in tests. File work: on the executor only. */
	interface Disk
	{
		/** Takes ownership of the saved chats if no other window has it, and reads them. Only the first call does. */
		ChatFile.Opened open();

		/** Writes save number {@code number}, unless a newer one was written or {@code wanted} says not to any more. */
		void write(String json, long number, BooleanSupplier wanted);

		void delete();
	}

	/** What the saver needs from the plugin. On the EDT, except {@link #rememberChats}. */
	interface Host
	{
		/** "Save chat history", right now. Any thread. */
		boolean rememberChats();

		/** The chats, in order: what a save writes, and where saved chats are brought back to. */
		List<Chat> chats();

		/** The chat that's open. */
		Chat current();

		/** Saved chats were brought back, and {@code current} is the one to show. */
		void restored(Chat current);

		/** Says why this window's chats aren't being remembered. */
		void note(String text);
	}

	static final String OTHER_WINDOW = "Chats in this window won't be remembered: OSRS AI Buddy already remembers chats in "
		+ "another RuneLite window.";
	static final String UNREADABLE = "Your saved chats couldn't be read, so this session won't save over them. "
		+ "Restarting RuneLite may help.";
	/** Changes this close together make one save. */
	private static final long SAVE_DELAY_SECONDS = 1;

	private final Disk disk;
	private final Gson gson;
	/** Runs a task on the EDT later, never right away: SwingUtilities::invokeLater. */
	private final Executor edt;
	/** Shared with RuneLite: short tasks only. */
	private final ScheduledExecutorService executor;
	private final Host host;
	/** Whether this window owns the saved chats; null until the file has been opened. */
	private ChatFile.State state;
	/** The saved chats are being opened. A save now would write over them, so it waits: one follows the opening. */
	private boolean loading;
	/** The next save, if one is waiting. */
	private ScheduledFuture<?> pendingSave;
	private long saveCount;
	/** RuneLite is closing: save at once instead of a moment later. */
	private boolean closing;

	ChatSaver(Disk disk, Gson gson, Executor edt, ScheduledExecutorService executor, Host host)
	{
		this.disk = disk;
		this.gson = gson;
		this.edt = edt;
		this.executor = executor;
		this.host = host;
	}

	/** Makes the saved chats match the setting: open (and load) them, save, or delete them. */
	void apply()
	{
		if (!host.rememberChats())
		{
			deleteSaved();
		}
		else if (state == null)
		{
			open();
		}
		else if (state == ChatFile.State.OWNER)
		{
			saveSoon();
		}
		else
		{
			showNote();
		}
	}

	/** Opens the saved chats in the background, brings them back, and from then on saves this window's. */
	private void open()
	{
		if (loading)
		{
			return;
		}
		loading = true;
		executor.execute(() ->
		{
			ChatFile.Opened opened = disk.open();
			edt.execute(() -> opened(opened));
		});
	}

	private void opened(ChatFile.Opened opened)
	{
		loading = false;
		state = opened.state;
		if (opened.loaded != null)
		{
			Chat shown = restore(host.chats(), host.current(), opened.loaded);
			if (shown != null)
			{
				host.restored(shown);
			}
		}
		showNote();
		// Brings the file up to date with the chats from before it was opened, if any.
		saveSoon();
	}

	/**
	 * Puts saved chats back in front of {@code chats}, leaving out any that are already there; the empty chat made at
	 * start-up gives way to them. Returns the chat to show, or null when there was nothing to bring back.
	 */
	static Chat restore(List<Chat> chats, Chat current, ChatStore.Loaded loaded)
	{
		List<Chat> fresh = new ArrayList<>();
		for (Chat c : loaded.chats)
		{
			if (chats.stream().noneMatch(existing -> existing.id.equals(c.id)))
			{
				fresh.add(c);
			}
		}
		if (fresh.isEmpty())
		{
			return null;
		}
		chats.removeIf(c -> c.messages.isEmpty() && !c.isRunning() && !c.namedByPlayer);
		chats.addAll(0, fresh);
		if (current != null && chats.contains(current))
		{
			return current;
		}
		return loaded.current != null && chats.contains(loaded.current) ? loaded.current : fresh.get(fresh.size() - 1);
	}

	/** Says why this window's chats aren't being remembered, if they aren't. */
	private void showNote()
	{
		if (!host.rememberChats())
		{
			return;
		}
		if (state == ChatFile.State.OTHER_WINDOW)
		{
			host.note(OTHER_WINDOW);
		}
		else if (state == ChatFile.State.UNREADABLE)
		{
			host.note(UNREADABLE);
		}
	}

	/** Saves the chats a moment from now; more changes in the meantime make it one save. */
	void saveSoon()
	{
		save(SAVE_DELAY_SECONDS);
	}

	void saveNow()
	{
		save(0);
	}

	/** RuneLite is closing: saves now, and from now on every change at once. The save, or null if there's none. */
	ScheduledFuture<?> close()
	{
		closing = true;
		return save(0);
	}

	/** The save scheduled, or null if there's nothing to save to (yet). */
	private ScheduledFuture<?> save(long delaySeconds)
	{
		if (!host.rememberChats() || loading || state != ChatFile.State.OWNER)
		{
			return null;
		}
		// Taken now, on the EDT, where the chats live.
		String json = ChatStore.toJson(gson, host.chats(), host.current());
		long number = ++saveCount;
		if (pendingSave != null)
		{
			pendingSave.cancel(false);
		}
		pendingSave = executor.schedule(() -> disk.write(json, number, host::rememberChats),
			closing ? 0 : delaySeconds, TimeUnit.SECONDS);
		return pendingSave;
	}

	/** "Save chat history" is off: no saves waiting, and no saved copy left. */
	private void deleteSaved()
	{
		if (pendingSave != null)
		{
			pendingSave.cancel(false);
			pendingSave = null;
		}
		executor.execute(disk::delete);
	}
}
