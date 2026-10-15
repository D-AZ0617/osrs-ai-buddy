package com.osrsaibuddy;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import javax.swing.AbstractAction;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JToolTip;
import javax.swing.KeyStroke;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.DefaultEditorKit;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;

/**
 * Where the player writes, at the bottom of the panel as in chat apps: a short note when a message can't go yet, the
 * input box (saying "Ask anything..." while it's empty), and under it the model picker and one round button. The button
 * sends; while a reply is on its way it stops it, and while a long chat's summary is being made it skips that. Enter
 * sends and Shift+Enter starts a new line. The box grows with what's typed in it, up to a few lines, then scrolls.
 * Swing EDT only.
 */
final class Composer extends JPanel
{
	static final String PLACEHOLDER = "Ask anything…";
	static final String SEND_TIP = "Send (Enter)";
	static final String STOP_TIP = "Stop";
	/** While a long chat is summarised before the question goes, the button skips the summary instead. */
	static final String SKIP_TIP = "Skip the summary and send the whole chat this time. Press Stop after that to "
		+ "stop the question too.";
	/** The box is this many lines tall when it's empty, and grows with the text up to {@link #MAX_LINES}. */
	static final int MIN_LINES = 2;
	/** Past this, the text scrolls inside the box instead of pushing the transcript away. */
	static final int MAX_LINES = 6;

	/** What the button does now. */
	enum Mode
	{
		SEND, STOP, SKIP
	}

	/** What the composer asks the panel to do. */
	interface Actions
	{
		/** Sends {@code text}; false if it can't go right now (it stays in the box). */
		boolean send(String text);

		/** Stops the reply on its way, or skips the summary being made. */
		void stop();

		/** The player chose {@code model} in the picker. */
		void chooseModel(String model);

		/** The player asked for the provider's list of models again. */
		void refreshModels();

		/** The input box is about to change height: the transcript above it gets shorter or taller. */
		void inputResizing();
	}

	/** A short note above the box, such as why a message can't be sent yet; hidden when there's none. */
	final JTextArea note = PanelStyle.textArea("");
	final Prompt input = new Prompt();
	/** The input box's scroll pane: as tall as the text in it, within {@link #MIN_LINES} and {@link #MAX_LINES}. */
	final JScrollPane scroll = new JScrollPane(input)
	{
		@Override
		public Dimension getPreferredSize()
		{
			Dimension d = super.getPreferredSize();
			d.height = inputHeight();
			return d;
		}
	};
	final ModelPicker models;
	final ActionButton action = new ActionButton();
	private final RoundBox box = new RoundBox(new BorderLayout(), PanelStyle.FIELD_COLOR, PanelStyle.OUTLINE_COLOR);
	private final Actions actions;
	/** There's a chat to write in. */
	private boolean usable = true;
	/** The input box's height for the text as it last was, to tell when typing makes it grow or shrink. */
	private int shownHeight = -1;
	/** The box's height is checked once the change being made now is in: no need to ask again until then. */
	private boolean resizePending;

	Composer(Actions actions)
	{
		super(new StackLayout(6));
		this.actions = actions;
		models = new ModelPicker(new ModelPicker.Actions()
		{
			@Override
			public void choose(String model)
			{
				actions.chooseModel(model);
			}

			@Override
			public void refresh()
			{
				actions.refreshModels();
			}
		});
		setOpaque(false);

		note.setFont(PanelStyle.SMALL_FONT);
		note.setForeground(PanelStyle.ERROR_COLOR);
		note.setVisible(false);
		add(note);

		input.setLineWrap(true);
		input.setWrapStyleWord(true);
		input.setFont(PanelStyle.TEXT_FONT);
		input.setOpaque(false);
		input.setForeground(PanelStyle.TEXT_COLOR);
		input.setCaretColor(PanelStyle.TEXT_COLOR);
		input.setBorder(new EmptyBorder(0, 0, 0, 0));
		input.setToolTipText("Enter sends, Shift+Enter adds a line");
		input.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "ai-chat-send");
		input.getInputMap().put(KeyStroke.getKeyStroke("shift ENTER"), DefaultEditorKit.insertBreakAction);
		input.getActionMap().put("ai-chat-send", new AbstractAction()
		{
			@Override
			public void actionPerformed(ActionEvent e)
			{
				send();
			}
		});
		input.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				changed();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				changed();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				changed();
			}
		});
		// The box's outline brightens while it has the caret, as text fields do.
		input.addFocusListener(new FocusAdapter()
		{
			@Override
			public void focusGained(FocusEvent e)
			{
				box.setColors(PanelStyle.FIELD_COLOR, ColorScheme.LIGHT_GRAY_COLOR);
			}

			@Override
			public void focusLost(FocusEvent e)
			{
				box.setColors(PanelStyle.FIELD_COLOR, PanelStyle.OUTLINE_COLOR);
			}
		});
		scroll.setBorder(null);
		scroll.setOpaque(false);
		scroll.getViewport().setOpaque(false);
		scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.getVerticalScrollBar().setUnitIncrement(lineHeight());
		box.setBorder(new EmptyBorder(6, 8, 6, 4));
		box.add(scroll, BorderLayout.CENTER);
		add(box);

		JPanel controls = new JPanel(new BorderLayout(6, 0));
		controls.setOpaque(false);
		controls.add(models, BorderLayout.CENTER);
		action.addActionListener(e ->
		{
			if (!action.settled())
			{
				return;
			}
			if (action.mode == Mode.SEND)
			{
				send();
			}
			else
			{
				actions.stop();
			}
		});
		controls.add(action, BorderLayout.EAST);
		add(controls);
		refreshAction();
	}

	/** The text changed: Send may have something to send now, and the box may need another height. */
	private void changed()
	{
		refreshAction();
		if (resizePending)
		{
			return;
		}
		// Measured once the text area has taken the change in too: this listener is told before it is (Swing tells the
		// latest listener first), so the text's height right now is still the height before the change. Once for a
		// change made in two steps, as setText's removing then inserting.
		resizePending = true;
		SwingUtilities.invokeLater(() ->
		{
			resizePending = false;
			int height = inputHeight();
			if (height != shownHeight)
			{
				shownHeight = height;
				// The scroll pane is laid out on its own (it's a validate root): the whole composer is, with the panel.
				revalidate();
				actions.inputResizing();
			}
		});
	}

	/**
	 * How tall the input box is for the text in it, at its width: from {@link #MIN_LINES} to {@link #MAX_LINES} lines.
	 * The text area wraps to the box's width, so its own preferred height is what the text needs.
	 */
	int inputHeight()
	{
		int text = input.getPreferredSize().height;
		return Math.max(linesHeight(MIN_LINES), Math.min(linesHeight(MAX_LINES), text));
	}

	/**
	 * How tall the text area is with {@code lines} lines in it: measured as its own preferred height is, by the lines
	 * and the space around them, so that a box of six lines shows six whole lines.
	 */
	int linesHeight(int lines)
	{
		Insets in = input.getInsets();
		return lines * lineHeight() + in.top + in.bottom;
	}

	private int lineHeight()
	{
		return input.getFontMetrics(input.getFont()).getHeight();
	}

	private void send()
	{
		String text = input.getText().trim();
		if (text.isEmpty())
		{
			return;
		}
		if (actions.send(text))
		{
			input.setText("");
			showNote(null);
		}
	}

	/**
	 * Brings the button in line with the chat: {@code usable}, there's a chat to write in; {@code running}, a reply is
	 * on its way; {@code summarising}, a long chat's summary is being made first.
	 */
	void setState(boolean usable, boolean running, boolean summarising)
	{
		this.usable = usable;
		input.setEnabled(usable);
		action.setMode(!running ? Mode.SEND : summarising ? Mode.SKIP : Mode.STOP);
		refreshAction();
	}

	/** Send needs something to send; Stop and Skip are always there to press. */
	private void refreshAction()
	{
		action.setEnabled(action.mode != Mode.SEND || usable && !input.getText().trim().isEmpty());
	}

	/** Puts {@code text} in the box, ready to finish and send: never sends it. */
	void fill(String text)
	{
		input.setText(text);
		input.setCaretPosition(input.getDocument().getLength());
		input.requestFocusInWindow();
	}

	/** Put a message that couldn't be sent back in the box, after anything already there. */
	void restoreDraft(String text)
	{
		String current = input.getText().trim();
		if (current.isEmpty())
		{
			input.setText(text);
		}
		else if (!current.contains(text))
		{
			input.setText(current + "\n\n" + text);
		}
	}

	/** A short note above the box; null clears it. */
	void showNote(String text)
	{
		note.setText(text == null ? "" : text);
		note.setVisible(text != null && !text.isEmpty());
		revalidate();
	}

	/** The input box: plain text, with {@link #PLACEHOLDER} drawn in it, muted, while it's empty. */
	static final class Prompt extends JTextArea
	{
		@Override
		protected void paintComponent(Graphics g)
		{
			super.paintComponent(g);
			if (getDocument().getLength() > 0)
			{
				return;
			}
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g2.setColor(PanelStyle.MUTED_COLOR);
			g2.setFont(getFont());
			FontMetrics fm = g2.getFontMetrics();
			Insets in = getInsets();
			g2.drawString(PLACEHOLDER, in.left, in.top + fm.getAscent());
			g2.dispose();
		}

		@Override
		public JToolTip createToolTip()
		{
			JToolTip tip = super.createToolTip();
			tip.putClientProperty("html.disable", Boolean.TRUE);
			return tip;
		}
	}

	/**
	 * The round button beside the model picker: an arrow to send, a square to stop, or "Skip" while a summary is being
	 * made. Drawn whole by itself (shape, icon and word), so it's the same with any look and feel.
	 */
	static final class ActionButton extends JButton
	{
		/** A click this soon after the button changed what it does was meant for what it did before. */
		static final long SETTLE_MILLIS = 500;
		private static final int SIZE = 26;
		private static final Color ON = new Color(235, 235, 235);
		private static final Color INK = ColorScheme.DARKER_GRAY_COLOR;
		Mode mode;
		/** When the button last changed what it does, in {@link System#currentTimeMillis()} time; 0 before then. */
		long changedAt;

		ActionButton()
		{
			putClientProperty("html.disable", Boolean.TRUE);
			setContentAreaFilled(false);
			setBorderPainted(false);
			setFocusPainted(false);
			setOpaque(false);
			setRolloverEnabled(true);
			setFont(PanelStyle.SMALL_FONT);
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			setMode(Mode.SEND);
		}

		void setMode(Mode mode)
		{
			if (mode == this.mode)
			{
				return;
			}
			if (this.mode != null)
			{
				changedAt = System.currentTimeMillis();
			}
			this.mode = mode;
			// The words are for screen readers and tests: the button draws an icon for Send and Stop.
			setText(mode == Mode.SEND ? "Send" : mode == Mode.STOP ? "Stop" : "Skip");
			setToolTipText(mode == Mode.SEND ? SEND_TIP : mode == Mode.STOP ? STOP_TIP : SKIP_TIP);
			revalidate();
			repaint();
		}

		/**
		 * Whether a click now is meant for what the button does now. Send turns into Stop the moment a question goes, and
		 * Stop back into Send the moment the reply is in: without this, a double-click on Send would stop the question
		 * it had just sent (which may be paid for by then), and Stop pressed just as the reply came in would send what's
		 * in the box.
		 */
		boolean settled()
		{
			return System.currentTimeMillis() - changedAt >= SETTLE_MILLIS;
		}

		@Override
		public Dimension getPreferredSize()
		{
			if (mode == Mode.SKIP)
			{
				return new Dimension(getFontMetrics(getFont()).stringWidth(getText()) + 20, SIZE);
			}
			return new Dimension(SIZE, SIZE);
		}

		@Override
		public Dimension getMinimumSize()
		{
			return getPreferredSize();
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			Graphics2D g2 = PanelStyle.smooth(g);
			int w = getWidth();
			int y = (getHeight() - SIZE) / 2;
			Color fill = !isEnabled() ? PanelStyle.OUTLINE_COLOR : getModel().isRollover() ? Color.WHITE : ON;
			g2.setColor(fill);
			if (mode == Mode.SKIP)
			{
				g2.fill(new RoundRectangle2D.Float(0, y, w, SIZE, SIZE, SIZE));
				g2.setColor(INK);
				g2.setFont(getFont());
				g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
				FontMetrics fm = g2.getFontMetrics();
				g2.drawString(getText(), (w - fm.stringWidth(getText())) / 2, y + (SIZE + fm.getAscent() - fm.getDescent()) / 2);
			}
			else
			{
				int x = w - SIZE;
				g2.fill(new Ellipse2D.Float(x, y, SIZE, SIZE));
				int icon = 14;
				new Glyph(mode == Mode.SEND ? Glyph.Shape.SEND : Glyph.Shape.STOP, icon, INK)
					.paintIcon(this, g2, x + (SIZE - icon) / 2, y + (SIZE - icon) / 2);
			}
			g2.dispose();
		}

		@Override
		public JToolTip createToolTip()
		{
			JToolTip tip = super.createToolTip();
			tip.putClientProperty("html.disable", Boolean.TRUE);
			return tip;
		}
	}
}
