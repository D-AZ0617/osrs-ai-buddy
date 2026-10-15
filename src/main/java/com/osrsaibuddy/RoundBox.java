package com.osrsaibuddy;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.LayoutManager;
import java.awt.geom.RoundRectangle2D;
import java.util.Objects;
import javax.swing.JPanel;

/** A panel drawn as a box with rounded corners: filled, outlined, or both. Its children go inside its border. */
class RoundBox extends JPanel
{
	private Color fill;
	private Color outline;

	RoundBox(LayoutManager layout, Color fill, Color outline)
	{
		super(layout);
		this.fill = fill;
		this.outline = outline;
		// The corners outside the box show what's behind it.
		setOpaque(false);
	}

	void setColors(Color fill, Color outline)
	{
		if (!Objects.equals(fill, this.fill) || !Objects.equals(outline, this.outline))
		{
			this.fill = fill;
			this.outline = outline;
			repaint();
		}
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		Graphics2D g2 = PanelStyle.smooth(g);
		RoundRectangle2D box = new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 1f, getHeight() - 1f,
			PanelStyle.ARC, PanelStyle.ARC);
		if (fill != null)
		{
			g2.setColor(fill);
			g2.fill(box);
		}
		if (outline != null)
		{
			g2.setColor(outline);
			g2.draw(box);
		}
		g2.dispose();
	}
}
