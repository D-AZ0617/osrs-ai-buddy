package com.osrsaibuddy;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Point;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import javax.swing.text.StyleConstants;

/**
 * One message in the transcript, drawn the way chat apps draw them: the player's questions in a bubble on the right,
 * replies across the full width with one line above them saying what was shared and looked up, and small actions
 * under them, notes small and centred, errors in a red box. A row is made for one kind of message and shown again as
 * the chat changes, so a new message doesn't redraw every earlier one. Swing EDT only.
 */
abstract class MessageRow extends JPanel
{
	/** The player's bubbles take at most this share of the width, as in chat apps: the rest shows whose they are. */
	static final float BUBBLE_SHARE = 0.85f;

	/** What a row's buttons do, for the panel. */
	interface Actions
	{
		/** Retry on {@code m}, the chat's last message: sends its question again, or writes its reply again. */
		void retry(Chat.Message m);
	}

	/** The message shown, for the buttons; null for the reply on its way. */
	Chat.Message message;

	MessageRow(int gap)
	{
		super(new StackLayout(gap));
		setOpaque(false);
	}

	/** A row for a message of {@code m}'s kind. */
	static MessageRow of(Chat.Message m, Actions actions)
	{
		switch (m.role)
		{
			case USER:
				return new Question(actions);
			case ASSISTANT:
				return new Reply(actions);
			case ERROR:
				return new Problem(actions);
			default:
				return new Note(actions);
		}
	}

	/**
	 * Shows {@code m}. {@code latest}: it's the chat's latest reply, whose actions always show (an older one's show while
	 * the mouse is over it). {@code retry}: Retry goes on it, the chat's last message, which can be retried.
	 */
	abstract void show(Chat.Message m, boolean latest, boolean retry);

	/** Whether Retry is offered on this row now. */
	abstract boolean offersRetry();

	// ------------------------------------------------------------------
	// The kinds of message
	// ------------------------------------------------------------------

	/** The player's message: a bubble on the right, hugging short text, and what was sent with it under it. */
	static final class Question extends MessageRow
	{
		final Bubble bubble = new Bubble();
		/** Under a message sent with the character details: says so, and shows them when clicked. */
		final ShowMore shared = new ShowMore("Click to see what was sent with this message", StyleConstants.ALIGN_RIGHT);
		/** Only when RuneLite closed while the question waited, with nothing after it to carry Retry. */
		final FlatButton retry;
		private final JPanel retryRow = buttonRow(FlowLayout.RIGHT);

		Question(Actions actions)
		{
			super(3);
			retry = retryButton("Send this question again", actions, this);
			retryRow.add(retry);
			add(bubble);
			add(shared);
			add(retryRow);
		}

		@Override
		void show(Chat.Message m, boolean latest, boolean retry)
		{
			message = m;
			// What the assistant no longer sees as written is drawn fainter: the summary note stands in for it.
			bubble.body.setTextColor(m.summarized ? PanelStyle.MUTED_COLOR : PanelStyle.TEXT_COLOR);
			bubble.body.setPlainText(m.text);
			tip(PanelText.tooltip(m), bubble, bubble.body);
			shared.show(m.context == null || m.context.isEmpty() ? null : "Sent your character details", m.context);
			retryRow.setVisible(retry);
		}

		@Override
		boolean offersRetry()
		{
			return retryRow.isVisible();
		}
	}

	/**
	 * A reply: across the full width with no box, what was looked up or shared for it above it, and Copy and Retry
	 * under it. Also the reply on its way, with a line saying what it's doing while there are no new words.
	 */
	static final class Reply extends MessageRow
	{
		final ShowMore activity = activityLine(StyleConstants.ALIGN_LEFT);
		final MessageView body = new MessageView();
		/** "Thinking..." and the like under the reply on its way; under a reply that broke off, that it didn't finish. */
		final MessageView status = mutedLine(StyleConstants.ALIGN_LEFT);
		final ActionBar actions;
		private boolean latest;
		private boolean hovered;

		Reply(Actions handler)
		{
			super(4);
			actions = new ActionBar(this, handler);
			body.setTextFont(PanelStyle.TEXT_FONT);
			add(activity);
			add(body);
			add(status);
			add(actions);
			trackHover(this, over ->
			{
				hovered = over;
				actions.setShown(latest || hovered);
			});
		}

		@Override
		void show(Chat.Message m, boolean latest, boolean retry)
		{
			message = m;
			this.latest = latest;
			showActivity(activity, m.activity);
			body.setTextColor(m.summarized ? PanelStyle.MUTED_COLOR : PanelStyle.TEXT_COLOR);
			body.setMarkdown(m.text);
			body.setVisible(true);
			tip(PanelText.tooltip(m), body);
			status.setPlainText(m.unfinished ? "This reply didn't finish." : "");
			status.setVisible(m.unfinished);
			actions.setVisible(true);
			actions.retry.setToolTipText(m.unfinished ? "Send the question again"
				: "Write this reply again: the same question goes again");
			actions.show(latest || hovered, retry);
		}

		/**
		 * The reply on its way. {@code text}: null until its first words; {@code lines}: what it has looked up so far;
		 * {@code line}: what it's doing, or null while it writes.
		 */
		void showLive(String who, String text, List<String> lines, String line)
		{
			message = null;
			showActivity(activity, lines);
			body.setTextColor(PanelStyle.TEXT_COLOR);
			body.setMarkdown(text);
			body.setVisible(text != null);
			tip(who == null ? null : who + " is answering", body);
			status.setPlainText(line == null ? "" : line);
			status.setVisible(line != null);
			// Nothing to copy or retry until it's in.
			actions.setVisible(false);
		}

		@Override
		boolean offersRetry()
		{
			return actions.isVisible() && actions.offersRetry();
		}
	}

	/**
	 * A note from OSRS AI Buddy (a summary, "Stopped.", a chat too long for the model): small, muted and centred, between the
	 * messages rather than one of them. A summary's note shows the summary a click away.
	 */
	static final class Note extends MessageRow
	{
		final ShowMore activity = activityLine(StyleConstants.ALIGN_CENTER);
		final ShowMore text = new ShowMore("Click to see the summary that's sent instead of them", StyleConstants.ALIGN_CENTER);
		final FlatButton retry;
		private final JPanel retryRow = buttonRow(FlowLayout.CENTER);

		Note(Actions actions)
		{
			super(2);
			setBorder(new EmptyBorder(2, 12, 2, 12));
			retry = retryButton("Send the question again", actions, this);
			retryRow.add(retry);
			add(activity);
			add(text);
			add(retryRow);
		}

		@Override
		void show(Chat.Message m, boolean latest, boolean retry)
		{
			message = m;
			showActivity(activity, m.activity);
			PanelText.Note note = PanelText.note(m.text);
			text.setPlainTip(PanelText.tooltip(m));
			text.show(note.line, note.details);
			retryRow.setVisible(retry);
		}

		@Override
		boolean offersRetry()
		{
			return retryRow.isVisible();
		}
	}

	/** An error: in a red outlined box, with Retry inside it when the question can go again. */
	static final class Problem extends MessageRow
	{
		final ShowMore activity = activityLine(StyleConstants.ALIGN_LEFT);
		final MessageView body = new MessageView();
		final FlatButton retry;
		private final JPanel retryRow = buttonRow(FlowLayout.LEFT);

		Problem(Actions actions)
		{
			super(4);
			retry = retryButton("Send the question again", actions, this);
			retry.setForeground(PanelStyle.TEXT_COLOR);
			retryRow.add(retry);
			RoundBox box = new RoundBox(new StackLayout(4), new Color(255, 107, 107, 22), PanelStyle.ERROR_COLOR);
			box.setBorder(new EmptyBorder(6, 8, 6, 8));
			body.setTextColor(PanelStyle.ERROR_COLOR);
			box.add(body);
			box.add(retryRow);
			add(activity);
			add(box);
		}

		@Override
		void show(Chat.Message m, boolean latest, boolean retry)
		{
			message = m;
			showActivity(activity, m.activity);
			body.setPlainText(m.text);
			tip(PanelText.tooltip(m), body);
			retryRow.setVisible(retry);
		}

		@Override
		boolean offersRetry()
		{
			return retryRow.isVisible();
		}
	}

	// ------------------------------------------------------------------
	// Parts
	// ------------------------------------------------------------------

	/** The player's words in a rounded bubble, as wide as they need up to {@link #BUBBLE_SHARE} of the row. */
	static final class Bubble extends RoundBox implements StackLayout.Fitted
	{
		final MessageView body = new MessageView();

		Bubble()
		{
			super(new BorderLayout(), PanelStyle.BUBBLE_COLOR, null);
			setBorder(new EmptyBorder(6, 10, 6, 10));
			setAlignmentX(RIGHT_ALIGNMENT);
			body.setTextFont(PanelStyle.TEXT_FONT);
			add(body, BorderLayout.CENTER);
		}

		@Override
		public int fittedWidth(int available)
		{
			int padding = getInsets().left + getInsets().right;
			return Math.min(body.naturalWidth() + padding, Math.round(available * BUBBLE_SHARE));
		}
	}

	/**
	 * Copy and Retry under a reply. It keeps its height while its buttons are hidden, so the transcript doesn't jump when
	 * the mouse comes over a reply and they appear.
	 */
	static final class ActionBar extends JPanel
	{
		/** How long Copy says "Copied". */
		private static final int COPIED_MILLIS = 1500;

		final FlatButton copy = new FlatButton("Copy", new Glyph(Glyph.Shape.COPY, 12), "Copy this reply as plain text");
		final FlatButton retry = new FlatButton("Retry", new Glyph(Glyph.Shape.RETRY, 12), null);
		private final Timer copied = new Timer(COPIED_MILLIS, e -> copy.setText("Copy"));
		private boolean shown;
		private boolean retryOffered;

		ActionBar(Reply reply, Actions actions)
		{
			super(new FlowLayout(FlowLayout.LEFT, 0, 0));
			setOpaque(false);
			copied.setRepeats(false);
			copy.addActionListener(e ->
			{
				reply.body.copyAll();
				copy.setText("Copied");
				copied.restart();
			});
			retry.addActionListener(e ->
			{
				if (reply.message != null)
				{
					actions.retry(reply.message);
				}
			});
			add(copy);
			add(retry);
			show(false, false);
		}

		/**
		 * Shows the buttons ({@code shown}), or hides them while keeping their place. {@code retry}: Retry is offered on
		 * this reply, and shows along with Copy.
		 */
		void show(boolean shown, boolean retry)
		{
			this.shown = shown;
			retryOffered = retry;
			copy.setVisible(shown);
			this.retry.setVisible(shown && retry);
			revalidate();
			repaint();
		}

		/** The mouse came over the reply or left it: the buttons show or hide, Retry still offered or not. */
		void setShown(boolean shown)
		{
			if (shown != this.shown)
			{
				show(shown, retryOffered);
			}
		}

		/** Whether the buttons show now (while the row is shown at all). */
		boolean isShown()
		{
			return shown;
		}

		boolean offersRetry()
		{
			return retryOffered;
		}

		@Override
		public Dimension getPreferredSize()
		{
			Dimension d = super.getPreferredSize();
			d.height = Math.max(d.height, copy.getPreferredSize().height);
			return d;
		}
	}

	// ------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------

	/**
	 * The line over a message saying what was looked up or shared for it, such as "Shared your equipment · Looked up 3
	 * things": hidden while nothing was.
	 */
	static ShowMore activityLine(int alignment)
	{
		return new ShowMore("Click to see everything that was shared and looked up for this message", alignment);
	}

	/**
	 * Shows {@code lines} (a message's {@link Chat.Message#activity}) on {@code line}: what was shared always named,
	 * then the look-ups by name or counted, as the width allows, and every line a click away.
	 */
	static void showActivity(ShowMore line, List<String> lines)
	{
		PanelText.Summary summary = PanelText.summary(lines);
		line.show(summary.isEmpty() ? null : summary::line, summary.details);
	}

	/** A small muted line of plain text. */
	static MessageView mutedLine(int alignment)
	{
		MessageView v = new MessageView();
		v.setTextFont(PanelStyle.SMALL_FONT);
		v.setTextColor(PanelStyle.MUTED_COLOR);
		v.setAlignment(alignment);
		v.setVisible(false);
		return v;
	}

	private static JPanel buttonRow(int align)
	{
		JPanel p = new JPanel(new FlowLayout(align, 0, 0));
		p.setOpaque(false);
		p.setVisible(false);
		return p;
	}

	private static FlatButton retryButton(String tip, Actions actions, MessageRow row)
	{
		FlatButton b = new FlatButton("Retry", new Glyph(Glyph.Shape.RETRY, 12), tip);
		b.addActionListener(e ->
		{
			if (row.message != null)
			{
				actions.retry(row.message);
			}
		});
		return b;
	}

	/** The message's tooltip, on each part of it the mouse can be over. */
	private static void tip(String tip, JComponent... parts)
	{
		for (JComponent c : parts)
		{
			c.setToolTipText(tip);
		}
	}

	/**
	 * Tells {@code hover} true when the mouse comes over {@code root} or anything in it, false when it leaves them all.
	 * Each part gets the listener, since the mouse over a part doesn't count as over the panel around it.
	 */
	static void trackHover(Container root, Consumer<Boolean> hover)
	{
		MouseAdapter listener = new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				hover.accept(true);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				// Into another part of the same row is still over it.
				Point p = SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), root);
				hover.accept(root.contains(p));
			}
		};
		addDeep(root, listener);
	}

	private static void addDeep(Component c, MouseAdapter listener)
	{
		c.addMouseListener(listener);
		if (c instanceof Container)
		{
			for (Component child : ((Container) c).getComponents())
			{
				addDeep(child, listener);
			}
		}
	}
}
