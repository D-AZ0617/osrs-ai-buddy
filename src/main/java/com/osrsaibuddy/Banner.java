package com.osrsaibuddy;

import java.awt.BorderLayout;
import java.awt.Color;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.border.EmptyBorder;

/**
 * A short message under the panel's header, shown only when there's something to say: what's missing before a
 * message can be sent, or what Test connection found. Plain text, in the colour of its news (green, orange, red, or
 * muted while a Test runs), with a close button when it can be put away.
 */
final class Banner extends RoundBox
{
	final JTextArea text = PanelStyle.textArea("");
	final FlatButton close = new FlatButton(null, new Glyph(Glyph.Shape.CLOSE, 10), "Close");
	private String shown = "";

	/** {@code closed}: the player closed it. */
	Banner(Runnable closed)
	{
		super(new BorderLayout(4, 0), PanelStyle.FIELD_COLOR, null);
		setBorder(new EmptyBorder(6, 8, 6, 4));
		text.setFont(PanelStyle.SMALL_FONT);
		add(text, BorderLayout.CENTER);
		close.setBorder(new EmptyBorder(2, 2, 2, 2));
		close.addActionListener(e -> closed.run());
		JPanel corner = new JPanel(new BorderLayout());
		corner.setOpaque(false);
		corner.add(close, BorderLayout.NORTH);
		add(corner, BorderLayout.EAST);
		setVisible(false);
	}

	/** Shows {@code message} in {@code color}, with a close button if {@code closable}; null hides the banner. */
	void show(String message, Color color, boolean closable)
	{
		String m = message == null ? "" : message;
		if (!m.equals(shown))
		{
			shown = m;
			text.setText(m);
		}
		text.setForeground(color);
		setColors(PanelStyle.FIELD_COLOR, color == null ? null : faded(color));
		close.setVisible(closable);
		setVisible(!m.isEmpty());
	}

	/** The banner's outline: its colour, but quieter than its words. */
	private static Color faded(Color c)
	{
		return new Color(c.getRed(), c.getGreen(), c.getBlue(), 110);
	}
}
