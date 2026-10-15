package com.osrsaibuddy;

import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * "Save chat history": the saved chats are opened once and brought back, saves wait for that and then follow the chats,
 * and turning the setting off deletes the saved copy. The disk is a stand-in; the EDT is a queue the test runs.
 */
public class ChatSaverTest
{
	private final Gson gson = new Gson();
	/** Stands in for the EDT: tasks wait here until the test runs them, as invokeLater's do. */
	private final BlockingQueue<Runnable> edt = new LinkedBlockingQueue<>();
	private final Scheduler executor = new Scheduler();
	private final FakeDisk disk = new FakeDisk();
	private final FakeHost host = new FakeHost();
	private final ChatSaver saver = new ChatSaver(disk, gson, edt::add, executor, host);

	@After
	public void stop()
	{
		executor.shutdownNow();
	}

	/**
	 * The plugin's executor, with a record of how long each save was asked to wait (in seconds; work run at once is
	 * scheduled in nanoseconds), which it doesn't wait, though.
	 */
	private static final class Scheduler extends ScheduledThreadPoolExecutor
	{
		final List<Long> delays = new CopyOnWriteArrayList<>();

		Scheduler()
		{
			super(1);
		}

		@Override
		public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit)
		{
			if (unit == TimeUnit.SECONDS)
			{
				delays.add(delay);
			}
			return super.schedule(command, 0, unit);
		}
	}

	private static final class FakeDisk implements ChatSaver.Disk
	{
		ChatFile.Opened opened = new ChatFile.Opened(ChatFile.State.OWNER, null);
		final AtomicInteger opens = new AtomicInteger();
		final AtomicInteger deletes = new AtomicInteger();
		/** What each save wrote, in order. */
		final List<String> writes = new CopyOnWriteArrayList<>();

		@Override
		public ChatFile.Opened open()
		{
			opens.incrementAndGet();
			return opened;
		}

		@Override
		public void write(String json, long number, BooleanSupplier wanted)
		{
			if (wanted.getAsBoolean())
			{
				writes.add(json);
			}
		}

		@Override
		public void delete()
		{
			deletes.incrementAndGet();
		}
	}

	private static final class FakeHost implements ChatSaver.Host
	{
		volatile boolean remember = true;
		final List<Chat> chats = new ArrayList<>();
		Chat current;
		Chat restored;
		final List<String> notes = new ArrayList<>();

		@Override
		public boolean rememberChats()
		{
			return remember;
		}

		@Override
		public List<Chat> chats()
		{
			return chats;
		}

		@Override
		public Chat current()
		{
			return current;
		}

		@Override
		public void restored(Chat shown)
		{
			restored = shown;
			current = shown;
		}

		@Override
		public void note(String text)
		{
			notes.add(text);
		}
	}

	/** Waits for the file work queued so far to be done. */
	private void drain() throws Exception
	{
		executor.submit(() ->
		{
		}).get(5, TimeUnit.SECONDS);
	}

	/** Waits for the file's answer to reach the EDT, then runs everything waiting there. */
	private void awaitEdt() throws InterruptedException
	{
		Runnable r = edt.poll(5, TimeUnit.SECONDS);
		assertNotNull("nothing reached the EDT", r);
		r.run();
		Runnable more;
		while ((more = edt.poll()) != null)
		{
			more.run();
		}
	}

	private Chat chat(String id, String name, String... texts)
	{
		Chat c = new Chat(id, name);
		for (String t : texts)
		{
			c.messages.add(new Chat.Message(Chat.Role.USER, t));
		}
		return c;
	}

	/** Saved chats on disk: "a" and "b", with "b" open. */
	private ChatStore.Loaded saved()
	{
		Chat a = chat("a", "Agility", "best course?");
		Chat b = chat("b", "Bank", "what to sell?");
		return ChatStore.fromJson(gson, ChatStore.toJson(gson, List.of(a, b), b));
	}

	@Test
	public void savedChatsComeBackAndAreSavedFromThenOn() throws Exception
	{
		Chat fresh = chat("new", "Chat 1");
		host.chats.add(fresh);
		host.current = fresh;
		disk.opened = new ChatFile.Opened(ChatFile.State.OWNER, saved());

		saver.apply();
		awaitEdt();
		assertEquals(1, disk.opens.get());
		// The empty chat made at start-up gave way; the one that was open is shown.
		assertEquals(2, host.chats.size());
		assertEquals("a", host.chats.get(0).id);
		assertEquals("b", host.restored.id);

		// Then saved: everything there is now.
		drain();
		assertEquals(1, disk.writes.size());
		ChatStore.Loaded written = ChatStore.fromJson(gson, disk.writes.get(0));
		assertEquals(2, written.chats.size());
		assertEquals("b", written.current.id);
		assertEquals(Long.valueOf(1), executor.delays.get(0));

		// Opened once only.
		saver.apply();
		drain();
		assertEquals(1, disk.opens.get());
		assertEquals(2, disk.writes.size());
	}

	@Test
	public void aSaveWhileTheChatsAreOpeningWaitsForThem() throws Exception
	{
		Chat asked = chat("new", "Chat 1", "a question asked at once");
		host.chats.add(asked);
		host.current = asked;
		disk.opened = new ChatFile.Opened(ChatFile.State.OWNER, saved());
		saver.apply();
		drain();
		// The chats on disk aren't in yet: saving now would write over them with this one alone.
		saver.saveSoon();
		drain();
		assertTrue(disk.writes.isEmpty());

		awaitEdt();
		drain();
		assertEquals(1, disk.writes.size());
		ChatStore.Loaded written = ChatStore.fromJson(gson, disk.writes.get(0));
		assertEquals("all three", 3, written.chats.size());
		assertSame("the chat in use stays open", asked, host.restored);
	}

	@Test
	public void chatsOwnedByAnotherWindowAreNeverWritten() throws Exception
	{
		host.chats.add(chat("new", "Chat 1", "q"));
		disk.opened = new ChatFile.Opened(ChatFile.State.OTHER_WINDOW, null);
		saver.apply();
		awaitEdt();
		assertEquals(List.of(ChatSaver.OTHER_WINDOW), host.notes);
		saver.saveSoon();
		saver.saveNow();
		assertNull(saver.close());
		drain();
		assertTrue(disk.writes.isEmpty());

		// An unreadable file is left alone too.
		FakeDisk unreadable = new FakeDisk();
		unreadable.opened = new ChatFile.Opened(ChatFile.State.UNREADABLE, null);
		ChatSaver other = new ChatSaver(unreadable, gson, edt::add, executor, host);
		other.apply();
		awaitEdt();
		assertEquals(ChatSaver.UNREADABLE, host.notes.get(host.notes.size() - 1));
		other.saveNow();
		drain();
		assertTrue(unreadable.writes.isEmpty());
	}

	@Test
	public void turningRememberChatsOffDeletesTheSavedCopy() throws Exception
	{
		host.chats.add(chat("new", "Chat 1", "q"));
		saver.apply();
		awaitEdt();
		drain();
		int written = disk.writes.size();

		host.remember = false;
		saver.apply();
		drain();
		assertEquals(1, disk.deletes.get());
		saver.saveSoon();
		drain();
		assertEquals("nothing saved while it's off", written, disk.writes.size());

		// Back on: saved again, without opening the file again.
		host.remember = true;
		saver.apply();
		drain();
		assertEquals(written + 1, disk.writes.size());
		assertEquals(1, disk.opens.get());
	}

	@Test
	public void closingSavesAtOnce() throws Exception
	{
		host.chats.add(chat("new", "Chat 1", "q"));
		saver.apply();
		awaitEdt();
		drain();
		ScheduledFuture<?> last = saver.close();
		assertNotNull("RuneLite waits for this", last);
		last.get(5, TimeUnit.SECONDS);
		assertEquals(Long.valueOf(0), executor.delays.get(executor.delays.size() - 1));
		saver.saveSoon();
		assertEquals("every change from now on, at once", Long.valueOf(0), executor.delays.get(executor.delays.size() - 1));
	}

	@Test
	public void savedChatsGoFirstWithoutDuplicates()
	{
		ChatStore.Loaded loaded = saved();
		List<Chat> chats = new ArrayList<>();
		Chat named = chat("named", "Named");
		named.namedByPlayer = true;
		Chat empty = chat("empty", "Chat 1");
		Chat again = chat("a", "Agility", "best course?");
		chats.add(named);
		chats.add(empty);
		chats.add(again);

		Chat shown = ChatSaver.restore(chats, empty, loaded);
		// "a" is already here; the empty chat gives way, the one the player named doesn't.
		assertEquals(List.of("b", "named", "a"), ids(chats));
		assertSame("the one that was open, as the shown one went", loaded.current, shown);

		// Nothing new: nothing changes.
		assertNull(ChatSaver.restore(chats, named, loaded));
		assertEquals(3, chats.size());
	}

	private static List<String> ids(List<Chat> chats)
	{
		List<String> ids = new ArrayList<>();
		for (Chat c : chats)
		{
			ids.add(c.id);
		}
		return ids;
	}
}

