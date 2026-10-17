package com.osrsaibuddy;

import com.google.gson.Gson;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.util.Filepath;

/**
 * The saved chats on disk, in the plugin's own folder. One RuneLite window at a time owns them: with several open, the
 * first keeps its lock for as long as it runs, and the others never read, write or delete the file, so they can't
 * overwrite each other. Every method does file work, so call them off the Swing and client threads.
 */
@Slf4j
final class ChatFile implements ChatSaver.Disk
{
	enum State
	{
		/** This window loads and saves the chats. */
		OWNER,
		/** Another RuneLite window does. */
		OTHER_WINDOW,
		/** The file is there but couldn't be read: it's left alone rather than written over. */
		UNREADABLE
	}

	static final class Opened
	{
		final State state;
		/** The saved chats, or null for none. */
		final ChatStore.Loaded loaded;

		Opened(State state, ChatStore.Loaded loaded)
		{
			this.state = state;
			this.loaded = loaded;
		}
	}

	private static final String LOCK_NAME = "chats.lock";

	private final Callable<Filepath> folder;
	private final Gson gson;
	/** Held until RuneLite exits, once taken: turning the plugin off and on can't hand the chats to another window. */
	private FileChannel lockChannel;
	private FileLock lock;
	private State state;
	/** The number of the newest save written, so an older one never lands after it. */
	private long written;

	ChatFile(Callable<Filepath> folder, Gson gson)
	{
		this.folder = folder;
		this.gson = gson;
	}

	/** Takes ownership if no other window has it, and reads the saved chats. Only the first call does anything. */
	@Override
	public synchronized Opened open()
	{
		if (state != null)
		{
			return new Opened(state, null);
		}
		try
		{
			Filepath dir = folder.call();
			dir.createDirectories();
			if (!lock(dir))
			{
				state = State.OTHER_WINDOW;
				return new Opened(state, null);
			}
			Filepath file = dir.joinSegment(ChatStore.FILE_NAME);
			if (!file.isFile())
			{
				state = State.OWNER;
				return new Opened(state, null);
			}
			String json;
			try
			{
				json = read(file);
			}
			catch (IOException | RuntimeException e)
			{
				log.warn("couldn't read the saved chats; they won't be written over", e);
				state = State.UNREADABLE;
				return new Opened(state, null);
			}
			ChatStore.Loaded loaded = ChatStore.fromJson(gson, json);
            if (loaded != null)
            {
                try { ChatTranscripts.sync(dir, gson, json); }
                catch (Exception e) { log.debug("couldn't refresh readable conversation copies", e); }
            }
			if (loaded == null)
			{
				// Damaged: kept aside for anyone who wants to look, and a fresh file started.
				Filepath aside = dir.joinSegment("chats-damaged-" + System.currentTimeMillis() + ".json");
				file.moveTo(aside);
				log.warn("the saved chats were damaged; moved them to {}", aside.getFileName());
			}
			state = State.OWNER;
			return new Opened(state, loaded);
		}
		catch (Exception e)
		{
			log.warn("couldn't open the saved chats", e);
			state = State.UNREADABLE;
			return new Opened(state, null);
		}
	}

	private boolean lock(Filepath dir) throws IOException
	{
		FileChannel channel = dir.joinSegment(LOCK_NAME).openFileChannel(StandardOpenOption.CREATE, StandardOpenOption.WRITE);
		FileLock taken;
		try
		{
			taken = channel.tryLock();
		}
		catch (OverlappingFileLockException e)
		{
			taken = null;
		}
		if (taken == null)
		{
			channel.close();
			return false;
		}
		lockChannel = channel;
		lock = taken;
		return true;
	}

	private static String read(Filepath file) throws IOException
	{
		StringBuilder sb = new StringBuilder();
		char[] buffer = new char[8192];
		try (BufferedReader r = file.openBufferedReader())
		{
			for (int n; (n = r.read(buffer)) > 0; )
			{
				sb.append(buffer, 0, n);
			}
		}
		return sb.toString();
	}

	/**
	 * Writes save number {@code number}, unless this window doesn't own the chats, a newer save was already written,
	 * or {@code wanted} says saving has been turned off since.
	 */
	@Override
	public synchronized void write(String json, long number, BooleanSupplier wanted)
	{
		if (state != State.OWNER || number < written || !wanted.getAsBoolean())
		{
			return;
		}
		Filepath temp = null;
		try
		{
			Filepath dir = folder.call();
			dir.createDirectories();
			// Written whole, then moved into place: a crash mid-save can't leave half a file.
			temp = dir.createTempFile("chats", ".tmp");
			temp.write(json);
			Filepath file = dir.joinSegment(ChatStore.FILE_NAME);
			try
			{
				temp.moveTo(file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			}
			catch (AtomicMoveNotSupportedException e)
			{
				temp.moveTo(file, StandardCopyOption.REPLACE_EXISTING);
			}
			temp = null;
			written = number;
            ChatTranscripts.sync(dir, gson, json);
		}
		catch (Exception e)
		{
			log.debug("couldn't save the chats", e);
		}
		finally
		{
			if (temp != null)
			{
				try
				{
					temp.deleteIfExists();
				}
				catch (IOException e)
				{
					log.debug("couldn't remove a temporary file", e);
				}
			}
		}
	}

	/**
	 * Deletes the saved chats, and any temporary file a save left behind, unless another RuneLite window owns them: they
	 * are that window's to keep or delete. The lock, if held, stays; if this window hasn't taken it, it's taken only
	 * while deleting, so it doesn't keep a window that saves its chats from owning them later.
	 */
	@Override
	public synchronized void delete()
	{
		boolean borrowed = false;
		try
		{
			Filepath dir = folder.call();
			if (!dir.isDirectory())
			{
				return;
			}
			if (lock == null)
			{
				if (!lock(dir))
				{
					return;
				}
				borrowed = true;
			}
			dir.joinSegment(ChatStore.FILE_NAME).deleteIfExists();
            ChatTranscripts.sync(dir, gson, "{\"chats\":[]}");
			List<Filepath> temps;
			try (Stream<Filepath> files = dir.walk(1))
			{
				temps = files.filter(f -> f.isFile() && f.getFileName().startsWith("chats") && f.getFileName().endsWith(".tmp"))
					.collect(Collectors.toList());
			}
			for (Filepath f : temps)
			{
				f.deleteIfExists();
			}
		}
		catch (Exception e)
		{
			log.debug("couldn't delete the saved chats", e);
		}
		finally
		{
			if (borrowed)
			{
				unlock();
			}
		}
	}

	/** Gives the lock back (closing its channel releases it). */
	private void unlock()
	{
		try
		{
			lockChannel.close();
		}
		catch (IOException e)
		{
			log.debug("couldn't release the saved chats' lock", e);
		}
		lockChannel = null;
		lock = null;
	}
}
