package com.osrsaibuddy;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.LayoutManager;
import javax.swing.JTextArea;
import net.runelite.client.ui.PluginPanel;

/**
 * Stacks visible children top to bottom at the container's full width. Unlike BoxLayout, each child's height is
 * asked for at that width, so wrapping text areas get the right height on the first layout instead of one line. A
 * {@link Fitted} child can be narrower, such as a bubble around a short message: it's placed by its alignment (see
 * {@link Component#getAlignmentX}: 0 left, 1 right).
 */
class StackLayout implements LayoutManager
{
	/** A child that may be narrower than the container. */
	interface Fitted
	{
		/** How wide it is when the container has {@code available} pixels for it: at most that. */
		int fittedWidth(int available);
	}

	/** Width to assume before the sidebar has been laid out: the panel minus OsrsAiBuddyPanel's border. */
	private static final int FALLBACK_WIDTH = PluginPanel.PANEL_WIDTH + PluginPanel.SCROLLBAR_WIDTH - 16;

	private final int gap;

	StackLayout(int gap)
	{
		this.gap = gap;
	}

	@Override
	public void addLayoutComponent(String name, Component comp)
	{
	}

	@Override
	public void removeLayoutComponent(Component comp)
	{
	}

	@Override
	public Dimension preferredLayoutSize(Container parent)
	{
		Insets in = parent.getInsets();
		int outer = parent.getWidth() > 0 ? parent.getWidth() : FALLBACK_WIDTH;
		int w = Math.max(0, outer - in.left - in.right);
		int h = in.top + in.bottom;
		int n = 0;
		for (Component c : parent.getComponents())
		{
			if (c.isVisible())
			{
				h += height(c, width(c, w));
				n++;
			}
		}
		if (n > 1)
		{
			h += gap * (n - 1);
		}
		return new Dimension(outer, h);
	}

	@Override
	public Dimension minimumLayoutSize(Container parent)
	{
		return preferredLayoutSize(parent);
	}

	@Override
	public void layoutContainer(Container parent)
	{
		Insets in = parent.getInsets();
		int w = Math.max(0, parent.getWidth() - in.left - in.right);
		int y = in.top;
		for (Component c : parent.getComponents())
		{
			if (!c.isVisible())
			{
				continue;
			}
			int cw = width(c, w);
			int h = height(c, cw);
			int x = in.left + Math.round((w - cw) * Math.max(0f, Math.min(1f, c.getAlignmentX())));
			c.setBounds(x, y, cw, h);
			y += h + gap;
		}
	}

	/** The width {@code c} gets when the container has {@code w} for it: all of it, unless it's {@link Fitted}. */
	static int width(Component c, int w)
	{
		return c instanceof Fitted ? Math.max(0, Math.min(w, ((Fitted) c).fittedWidth(w))) : w;
	}

	/**
	 * The height {@code c} gets at width {@code w}. A child that hasn't changed since the last layout (still valid, and
	 * as wide) keeps the height it has: while a reply streams in, only its own bubble is measured again, not every
	 * message above it.
	 */
	private static int height(Component c, int w)
	{
		if (c.isValid() && c.getWidth() == w && c.getHeight() > 0)
		{
			return c.getHeight();
		}
		return heightFor(c, w);
	}

	/** Preferred height of {@code c} when it is {@code w} wide. */
	static int heightFor(Component c, int w)
	{
		if (c instanceof JTextArea)
		{
			// A wrapped text area computes its preferred height from its current size.
			c.setSize(w, Short.MAX_VALUE);
		}
		else if (c instanceof Container)
		{
			// Lay the children out at this width first, so nested text areas know theirs.
			c.setSize(w, Short.MAX_VALUE);
			((Container) c).doLayout();
		}
		return c.getPreferredSize().height;
	}
}
