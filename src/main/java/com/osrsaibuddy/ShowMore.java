package com.osrsaibuddy;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.util.function.Function;
import java.util.function.Predicate;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.text.BadLocationException;
import javax.swing.text.StyleConstants;

/**
 * A muted line about a message with more to it than fits there, such as "Sent your character details" or "Shared your
 * bank · Looked up 3 things", the way chat apps show what a reply did: a chevron after the line says it opens, a click
 * on the line shows the rest in a block under it (the chevron turns up), and another click hides it again. Both are
 * plain text, which never renders HTML: they can hold words the model chose. The line sits left, centred or right, like
 * what it's about, and its words can be chosen for the width it gets (see {@link #show(Function, String)}); the block
 * is always left-aligned, set in a little.
 */
final class ShowMore extends JPanel
{
	/** Kept after the line's last word for the chevron: spaces that never break from the word before them. */
	static final String CHEVRON_ROOM = "    ";
	/** Pixels of the line's width its words leave free: the text is laid out a bit differently than it's measured. */
	static final int SPARE_WIDTH = 2;
	private static final int CHEVRON_SIZE = 9;
	/** The line under the mouse, when it opens: brighter, as a button would be. */
	private static final Color HOVER_COLOR = new Color(225, 225, 225);

	final Line line = new Line();
	final MessageView more = new MessageView();
	/** For the line, while it has more to show. */
	private final String tip;
	/** For the line while it hasn't, or null. */
	private String plainTip;
	/** The line's words for the room it has, as whether a text fits it; null while there's nothing to show. */
	private Function<Predicate<String>, String> text;
	private boolean hovered;

	/** {@code alignment}: where the line sits, StyleConstants.ALIGN_LEFT, ALIGN_CENTER or ALIGN_RIGHT. */
	ShowMore(String tip, int alignment)
	{
		super(new StackLayout(0));
		this.tip = tip;
		setOpaque(false);
		setVisible(false);
		for (MessageView v : new MessageView[]{line, more})
		{
			v.setTextFont(PanelStyle.SMALL_FONT);
			v.setTextColor(PanelStyle.MUTED_COLOR);
			add(v);
		}
		line.setAlignment(alignment);
		// Set in a little, so it reads as the line's own and not as what comes after it.
		more.setBorder(new EmptyBorder(2, 8, 2, alignment == StyleConstants.ALIGN_LEFT ? 0 : 8));
		more.setVisible(false);
		MouseAdapter mouse = new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				if (SwingUtilities.isLeftMouseButton(e) && hasMore())
				{
					more.setVisible(!more.isVisible());
					showLine();
					// Taller or shorter now: measured again, with the transcript around it.
					revalidate();
					repaint();
				}
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				hovered = true;
				showLine();
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				hovered = false;
				showLine();
			}
		};
		line.addMouseListener(mouse);
	}

	/**
	 * Shows {@code text} (null: nothing at all), with {@code details} a click away (null: just the line). Shown or
	 * hidden, the details stay as they were when they change: a list still growing over a reply on its way doesn't
	 * close on the player.
	 */
	void show(String text, String details)
	{
		show(text == null || text.isEmpty() ? null : fits -> text, details);
	}

	/** As {@link #show(String, String)}, with the line's words chosen for the room it has: {@code text} picks them. */
	void show(Function<Predicate<String>, String> text, String details)
	{
		this.text = text;
		String d = text == null || details == null ? "" : details;
		more.setPlainText(d);
		if (d.isEmpty())
		{
			more.setVisible(false);
		}
		setVisible(text != null);
		showLine();
	}

	/** The line's tooltip while it has nothing more to show, such as when its message was sent. */
	void setPlainTip(String tip)
	{
		plainTip = tip;
		showLine();
	}

	/** The line's words as shown, without the room for the chevron. */
	String lineText()
	{
		String s = line.getSource();
		return s.endsWith(CHEVRON_ROOM) ? s.substring(0, s.length() - CHEVRON_ROOM.length()) : s;
	}

	/** Whether the rest shows under the line. */
	boolean isOpen()
	{
		return more.isVisible();
	}

	/** The chevron after the line: down while the rest is hidden, up while it shows; null when there's no rest. */
	Glyph.Shape chevron()
	{
		return !hasMore() ? null : isOpen() ? Glyph.Shape.CHEVRON_UP : Glyph.Shape.CHEVRON_DOWN;
	}

	private boolean hasMore()
	{
		return !more.getSource().isEmpty();
	}

	private void showLine()
	{
		boolean any = hasMore();
		line.setCursor(Cursor.getPredefinedCursor(any ? Cursor.HAND_CURSOR : Cursor.TEXT_CURSOR));
		line.setToolTipText(any ? tip : plainTip);
		line.setTextColor(any && hovered ? HOVER_COLOR : PanelStyle.MUTED_COLOR);
		line.fit();
		line.repaint();
	}

	/**
	 * The line: its words for the width it has, and the chevron drawn after the last of them when there's more to show.
	 * Its words are chosen again when it gets another width.
	 */
	final class Line extends MessageView
	{
		@Override
		public void setBounds(int x, int y, int width, int height)
		{
			boolean widthChanged = width != getWidth();
			super.setBounds(x, y, width, height);
			if (widthChanged)
			{
				fit();
			}
		}

		/** Chooses the words for the width the line has now; any fit until it has one. */
		void fit()
		{
			if (text == null)
			{
				setPlainText("");
				return;
			}
			String room = hasMore() ? CHEVRON_ROOM : "";
			Insets in = getInsets();
			int width = getWidth() - in.left - in.right - SPARE_WIDTH;
			FontMetrics fm = getFontMetrics(PanelStyle.SMALL_FONT);
			setPlainText(text.apply(s -> width <= 0 || fm.stringWidth(s + room) <= width) + room);
		}

		@Override
		String plainText()
		{
			return lineText();
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			super.paintComponent(g);
			Glyph.Shape chevron = chevron();
			if (chevron == null)
			{
				return;
			}
			try
			{
				// Where the room kept for it starts: just after the last word, on whichever row that is.
				Rectangle2D at = modelToView2D(getDocument().getLength() - CHEVRON_ROOM.length());
				if (at != null)
				{
					int x = (int) Math.round(at.getX()) + 3;
					int y = (int) Math.round(at.getCenterY() - CHEVRON_SIZE / 2.0);
					Color color = hovered ? HOVER_COLOR : PanelStyle.MUTED_COLOR;
					new Glyph(chevron, CHEVRON_SIZE, color).paintIcon(this, g, x, y);
				}
			}
			catch (BadLocationException e)
			{
				// The text changed under the paint: the next one draws it.
			}
		}
	}
}
