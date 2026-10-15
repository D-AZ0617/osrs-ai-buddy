package com.osrsaibuddy;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Redrawing a streaming reply: the newest text is always shown, but not more often than the gap allows. */
public class ThrottleTest
{
	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
	/** For the test's own waits and offers, apart from the throttle's scheduler. */
	private final ScheduledExecutorService clock = Executors.newSingleThreadScheduledExecutor();
	/** Stands in for the EDT: one thread, tasks in order. */
	private final ExecutorService edt = Executors.newSingleThreadExecutor();
	private final List<String> passed = new CopyOnWriteArrayList<>();

	@After
	public void stop()
	{
		scheduler.shutdownNow();
		clock.shutdownNow();
		edt.shutdownNow();
	}

	/** Waits for the EDT stand-in to run what it has, including passes queued after a wait. */
	private void settle(long millis) throws Exception
	{
		clock.schedule(() ->
		{
		}, millis, TimeUnit.MILLISECONDS).get(5, TimeUnit.SECONDS);
		edt.submit(() ->
		{
		}).get(5, TimeUnit.SECONDS);
	}

	@Test
	public void theFirstValueGoesAtOnceAndTheNewestComesLast() throws Exception
	{
		Throttle<String> throttle = new Throttle<>(edt, scheduler, 50, passed::add);
		throttle.offer("a");
		settle(0);
		assertEquals(List.of("a"), passed);

		// A quick burst: one pass after the gap, with the newest text.
		for (int i = 0; i < 200; i++)
		{
			throttle.offer("a" + i);
		}
		assertEquals("a199", throttle.latest());
		settle(150);
		// Usually one pass for the whole burst; a machine that stalls mid-burst may let one more through, but the
		// newest text always comes last.
		assertEquals("a", passed.get(0));
		assertEquals("a199", passed.get(passed.size() - 1));
		assertTrue(passed.toString(), passed.size() <= 3);
	}

	@Test
	public void passesAreSpacedOut() throws Exception
	{
		Throttle<String> throttle = new Throttle<>(edt, scheduler, 40, passed::add);
		AtomicInteger offered = new AtomicInteger();
		long start = System.nanoTime();
		// A fast stream: a new text every millisecond for 400ms.
		ScheduledFuture<?> stream = clock.scheduleAtFixedRate(() -> throttle.offer("t" + offered.getAndIncrement()),
			0, 1, TimeUnit.MILLISECONDS);
		clock.schedule(() -> stream.cancel(false), 400, TimeUnit.MILLISECONDS).get(5, TimeUnit.SECONDS);
		String last = throttle.latest();
		settle(120);
		long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
		// Hundreds offered, at most one pass per gap (and the first at once).
		assertTrue(passed.size() + " passes in " + elapsed + "ms", passed.size() <= elapsed / 40 + 2);
		assertTrue(passed.size() >= 2);
		assertTrue(offered.get() > passed.size());
		assertEquals(last, passed.get(passed.size() - 1));
	}
}

