package com.osrsaibuddy;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JToolTip;
import javax.swing.border.EmptyBorder;

/**
 * A small button that's just its icon and words until the mouse is over it, then a soft rounded patch behind them, as
 * in chat apps. It draws that itself, so it looks the same with any look and feel. Optionally filled, as a floating
 * circle. Its words and tooltip are always OSRS AI Buddy's own.
 */
class FlatButton extends JButton
{
	private Color fill;
	private Color outline;
	private boolean round;

	FlatButton(String text, Icon icon, String tip)
	{
		super(text, icon);
		// Set before any text could be: buttons never render HTML here.
		putClientProperty("html.disable", Boolean.TRUE);
		setToolTipText(tip);
		setContentAreaFilled(false);
		setBorderPainted(false);
		setFocusPainted(false);
		setOpaque(false);
		setRolloverEnabled(true);
		setFont(PanelStyle.SMALL_FONT);
		setForeground(PanelStyle.MUTED_COLOR);
		setIconTextGap(3);
		setBorder(new EmptyBorder(3, 4, 3, 5));
		setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
	}

	/** Always drawn on a filled background, a circle if {@code round}: for a button that floats over other things. */
	FlatButton filled(Color fill, Color outline, boolean round)
	{
		this.fill = fill;
		this.outline = outline;
		this.round = round;
		return this;
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		boolean hot = isEnabled() && (getModel().isRollover() || getModel().isPressed());
		Color back = hot ? PanelStyle.BUBBLE_COLOR : fill;
		if (back != null || outline != null)
		{
			Graphics2D g2 = PanelStyle.smooth(g);
			Shape shape = round
				? new Ellipse2D.Float(0.5f, 0.5f, getWidth() - 1f, getHeight() - 1f)
				: new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 1f, getHeight() - 1f, 8, 8);
			if (back != null)
			{
				g2.setColor(back);
				g2.fill(shape);
			}
			if (outline != null)
			{
				g2.setColor(outline);
				g2.draw(shape);
			}
			g2.dispose();
		}
		super.paintComponent(g);
	}

	@Override
	public JToolTip createToolTip()
	{
		JToolTip tip = super.createToolTip();
		tip.putClientProperty("html.disable", Boolean.TRUE);
		return tip;
	}
}
