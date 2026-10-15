package com.osrsaibuddy;

import java.awt.Dimension;
import javax.swing.JComponent;
import javax.swing.JPanel;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The transcript's layout: children stacked at full width (or hugging their content on their side), and only the ones
 * that changed measured again.
 */
public class StackLayoutTest
{
	/**
	 * A child that counts how often it's measured. Off screen nothing counts as laid out (that takes a window), so it
	 * keeps track of that itself, the way it would on screen.
	 */
	private static class Counted extends JComponent
	{
		int height;
		int measured;
		private boolean laidOut;

		Counted(int height)
		{
			this.height = height;
		}

		@Override
		public Dimension getPreferredSize()
		{
			measured++;
			return new Dimension(10, height);
		}

		@Override
		public boolean isValid()
		{
			return laidOut;
		}

		@Override
		public void invalidate()
		{
			laidOut = false;
			super.invalidate();
		}

		@Override
		protected void validateTree()
		{
			super.validateTree();
			laidOut = true;
		}
	}

	/** Lays itself out the way Swing does on screen, which marks what was laid out as valid. */
	private static final class Root extends JPanel
	{
		Root()
		{
			super(new StackLayout(2));
		}

		void layOut()
		{
			synchronized (getTreeLock())
			{
				validateTree();
			}
		}
	}

	@Test
	public void onlyWhatChangedIsMeasuredAgain()
	{
		Root root = new Root();
		Counted first = new Counted(10);
		Counted live = new Counted(20);
		root.add(first);
		root.add(live);
		root.setSize(100, 500);
		root.layOut();
		assertEquals(100, first.getWidth());
		assertEquals(10, first.getHeight());
		assertEquals(12, live.getY());
		assertEquals(20, live.getHeight());

		// The reply streaming in grows: only it is measured again.
		first.measured = 0;
		live.measured = 0;
		live.height = 35;
		live.invalidate();
		root.layOut();
		assertEquals(0, first.measured);
		assertTrue(live.measured > 0);
		assertEquals(35, live.getHeight());
		assertEquals(10 + 2 + 35, root.getPreferredSize().height);

		// Another width: everything is measured again.
		root.setSize(80, 500);
		root.layOut();
		assertTrue(first.measured > 0);
		assertEquals(80, first.getWidth());
	}

	/** A child that's only as wide as it needs, up to what it's given. */
	private static final class Narrow extends Counted implements StackLayout.Fitted
	{
		final int natural;

		Narrow(int natural, int height)
		{
			super(height);
			this.natural = natural;
		}

		@Override
		public int fittedWidth(int available)
		{
			return Math.min(natural, available * 85 / 100);
		}
	}

	@Test
	public void aFittedChildHugsItsContentOnItsSide()
	{
		Root root = new Root();
		Counted full = new Counted(10);
		Narrow bubble = new Narrow(40, 20);
		bubble.setAlignmentX(1f);
		Narrow wide = new Narrow(500, 20);
		wide.setAlignmentX(1f);
		Narrow left = new Narrow(30, 5);
		left.setAlignmentX(0f);
		root.add(full);
		root.add(bubble);
		root.add(wide);
		root.add(left);
		root.setSize(200, 500);
		root.layOut();
		assertEquals(200, full.getWidth());
		assertEquals("as wide as its content", 40, bubble.getWidth());
		assertEquals("on the right", 160, bubble.getX());
		assertEquals("never wider than its share", 170, wide.getWidth());
		assertEquals(30, wide.getX());
		assertEquals(0, left.getX());
		assertEquals(30, left.getWidth());

		// Unchanged, it isn't measured again at its own width either.
		bubble.measured = 0;
		root.invalidate();
		root.layOut();
		assertEquals(0, bubble.measured);
		assertEquals(160, bubble.getX());
	}
}

