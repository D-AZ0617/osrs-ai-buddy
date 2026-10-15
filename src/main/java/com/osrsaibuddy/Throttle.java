package com.osrsaibuddy;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Passes on the newest of a quick stream of values, such as a reply's text as it streams in, at most once every
 * {@code gapMillis}: each pass redraws the reply, so the values in between are skipped. Values come from any thread;
 * passes run on {@code edt}, one at a time, and the last value offered is always passed on.
 */
final class Throttle<T>
{
	private final Executor edt;
	private final ScheduledExecutorService scheduler;
	private final long gapNanos;
	private final Consumer<T> pass;
	private final AtomicReference<T> latest = new AtomicReference<>();
	/** A pass is on its way: it will take whatever is newest when it runs. */
	private final AtomicBoolean queued = new AtomicBoolean();
	/** When the next pass may run, in {@link System#nanoTime()} time. */
	private volatile long nextAt = System.nanoTime();

	/** {@code scheduler}: only for the short waits between passes. */
	Throttle(Executor edt, ScheduledExecutorService scheduler, long gapMillis, Consumer<T> pass)
	{
		this.edt = edt;
		this.scheduler = scheduler;
		this.gapNanos = TimeUnit.MILLISECONDS.toNanos(gapMillis);
		this.pass = pass;
	}

	/** Any thread. */
	void offer(T value)
	{
		latest.set(value);
		if (!queued.compareAndSet(false, true))
		{
			return;
		}
		long wait = nextAt - System.nanoTime();
		if (wait <= 0)
		{
			edt.execute(this::pass);
			return;
		}
		try
		{
			scheduler.schedule(() -> edt.execute(this::pass), wait, TimeUnit.NANOSECONDS);
		}
		catch (RejectedExecutionException e)
		{
			// RuneLite is closing: no waiting, then.
			edt.execute(this::pass);
		}
	}

	/** The newest value offered so far, or null. Any thread. */
	T latest()
	{
		return latest.get();
	}

	private void pass()
	{
		// Cleared before reading: a value offered from now on queues another pass rather than being missed.
		queued.set(false);
		nextAt = System.nanoTime() + gapNanos;
		pass.accept(latest.get());
	}
}
