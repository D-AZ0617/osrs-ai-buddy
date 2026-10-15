package com.osrsaibuddy;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.JMenuItem;
import javax.swing.JTextArea;
import javax.swing.JToolTip;
import net.runelite.client.ui.ColorScheme;

/**
 * The panel's fonts and colours, and the plain-text components it's built from. Colours come from RuneLite's own
 * {@link ColorScheme}, apart from the few it has no word for (errors, warnings, success).
 */
final class PanelStyle
{
	static final Font TEXT_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
	static final Font SMALL_FONT = TEXT_FONT.deriveFont(11f);
	static final Font TITLE_FONT = TEXT_FONT.deriveFont(Font.BOLD, 13f);
	static final Font HEADING_FONT = TEXT_FONT.deriveFont(Font.BOLD, 15f);

	static final Color TEXT_COLOR = Color.WHITE;
	static final Color MUTED_COLOR = ColorScheme.LIGHT_GRAY_COLOR;
	static final Color ERROR_COLOR = new Color(0xff6b6b);
	static final Color OK_COLOR = new Color(0x5fd068);
	static final Color WARNING_COLOR = new Color(0xffb347);
	static final Color BACKGROUND = ColorScheme.DARK_GRAY_COLOR;
	/** The player's bubbles, and what buttons show under the mouse: a little lighter than the panel. */
	static final Color BUBBLE_COLOR = ColorScheme.DARKER_GRAY_HOVER_COLOR;
	/** Boxes that hold something: the input, the banner. A little darker than the panel. */
	static final Color FIELD_COLOR = ColorScheme.DARKER_GRAY_COLOR;
	static final Color OUTLINE_COLOR = ColorScheme.MEDIUM_GRAY_COLOR;
	/** How round the corners of bubbles and boxes are. */
	static final int ARC = 12;

	private PanelStyle()
	{
	}

	/** Smooth edges for the shapes the panel draws itself. */
	static Graphics2D smooth(Graphics g)
	{
		Graphics2D g2 = (Graphics2D) g.create();
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
		return g2;
	}

	/** A menu item whose words and tooltip are plain text, never HTML. */
	static JMenuItem menuItem(String text)
	{
		JMenuItem item = new JMenuItem()
		{
			@Override
			public JToolTip createToolTip()
			{
				JToolTip tip = super.createToolTip();
				tip.putClientProperty("html.disable", Boolean.TRUE);
				return tip;
			}
		};
		// Before the text is set: the HTML renderer is picked when it is.
		item.putClientProperty("html.disable", Boolean.TRUE);
		item.setText(text);
		return item;
	}

	/** A read-only, wrapping, selectable block of plain text (a text area never renders HTML, nor does its tooltip). */
	static JTextArea textArea(String text)
	{
		JTextArea t = new JTextArea(text)
		{
			@Override
			public JToolTip createToolTip()
			{
				JToolTip tip = super.createToolTip();
				tip.putClientProperty("html.disable", Boolean.TRUE);
				return tip;
			}
		};
		t.setEditable(false);
		t.setLineWrap(true);
		t.setWrapStyleWord(true);
		t.setFont(TEXT_FONT);
		t.setBorder(null);
		t.setOpaque(false);
		return t;
	}
}
