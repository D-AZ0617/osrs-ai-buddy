package com.osrsaibuddy;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import javax.swing.JButton;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The panel's drawn icons: each draws its own shape, in its square, and fainter on a disabled button. */
public class GlyphTest
{
	private static final int SIZE = 16;

	/** The icon drawn on a clear square: how opaque each pixel is. */
	private static int[] draw(Glyph.Shape shape, boolean enabled)
	{
		JButton owner = new JButton();
		owner.setForeground(Color.WHITE);
		owner.setEnabled(enabled);
		BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		new Glyph(shape, SIZE).paintIcon(owner, g, 0, 0);
		g.dispose();
		int[] alpha = new int[SIZE * SIZE];
		for (int i = 0; i < alpha.length; i++)
		{
			alpha[i] = image.getRGB(i % SIZE, i / SIZE) >>> 24;
		}
		return alpha;
	}

	private static long ink(int[] alpha)
	{
		long sum = 0;
		for (int a : alpha)
		{
			sum += a;
		}
		return sum;
	}

	@Test
	public void eachShapeDrawsItsOwnIcon()
	{
		Glyph.Shape[] shapes = Glyph.Shape.values();
		int[][] drawn = new int[shapes.length][];
		for (int i = 0; i < shapes.length; i++)
		{
			drawn[i] = draw(shapes[i], true);
			assertTrue(shapes[i] + " draws something", ink(drawn[i]) > 0);
			for (int j = 0; j < i; j++)
			{
				assertFalse(shapes[i] + " looks like " + shapes[j], Arrays.equals(drawn[i], drawn[j]));
			}
		}
		assertEquals(SIZE, new Glyph(Glyph.Shape.SEND, SIZE).getIconWidth());
		assertEquals(SIZE, new Glyph(Glyph.Shape.SEND, SIZE).getIconHeight());
	}

	@Test
	public void aDisabledButtonsIconIsFainter()
	{
		assertTrue(ink(draw(Glyph.Shape.SEND, false)) < ink(draw(Glyph.Shape.SEND, true)) / 2);
	}

	@Test
	public void sendPointsUpAndJumpPointsDown()
	{
		// The arrowheads: Send's ink sits higher than the jump button's.
		assertTrue(centreY(draw(Glyph.Shape.SEND, true)) < centreY(draw(Glyph.Shape.DOWN, true)));
	}

	@Test
	public void chevronsPointTheWayTheySay()
	{
		// The tip is where the middle column's ink is: low for down, high for up.
		assertTrue(inkRow(draw(Glyph.Shape.CHEVRON_DOWN, true), SIZE / 2) > SIZE / 2);
		assertTrue(inkRow(draw(Glyph.Shape.CHEVRON_UP, true), SIZE / 2) < SIZE / 2);
	}

	@Test
	public void retryEndsInAnArrowheadWhereItsCircleIsOpen()
	{
		int[] retry = draw(Glyph.Shape.RETRY, true);
		// The head, at the top right; under it, the gap the arrow goes round to.
		assertTrue(retry[3 * SIZE + 13] > 170);
		for (int y = 7; y <= 8; y++)
		{
			assertEquals("open at the right, row " + y, 0, retry[y * SIZE + 14]);
		}
	}

	/** The first row from the top with ink in column {@code x}. */
	private static int inkRow(int[] alpha, int x)
	{
		for (int y = 0; y < SIZE; y++)
		{
			if (alpha[y * SIZE + x] > 100)
			{
				return y;
			}
		}
		return -1;
	}

	private static double centreY(int[] alpha)
	{
		double weighted = 0;
		for (int i = 0; i < alpha.length; i++)
		{
			weighted += alpha[i] * (i / SIZE);
		}
		return weighted / ink(alpha);
	}
}

