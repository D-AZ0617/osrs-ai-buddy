package com.osrsaibuddy;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.TransferHandler;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.basic.BasicTextPaneUI;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.Element;
import javax.swing.text.StyleConstants;
import javax.swing.text.View;
import net.runelite.client.ui.ColorScheme;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The message view off screen: the text and attributes it builds, the height it asks for in the transcript, and where
 * its links are. Runs headless.
 */
public class MessageViewTest
{
	/** A message wider than any table in these tests, in any font. */
	private static final int WIDE = 320;

	private static final String WIKI = "https://oldschool.runescape.wiki/w/Abyssal_whip";

	/** Swing components belong on the EDT, in tests too. */
	private static void onEdt(Runnable test) throws Throwable
	{
		try
		{
			SwingUtilities.invokeAndWait(test);
		}
		catch (InvocationTargetException e)
		{
			throw e.getCause();
		}
	}

	private static String text(MessageView v)
	{
		Document d = v.getDocument();
		try
		{
			return d.getText(0, d.getLength());
		}
		catch (BadLocationException e)
		{
			throw new AssertionError(e);
		}
	}

	/** The attributes of the first character of {@code word}. */
	private static AttributeSet at(MessageView v, String word)
	{
		int i = text(v).indexOf(word);
		assertTrue(word, i >= 0);
		return v.getStyledDocument().getCharacterElement(i).getAttributes();
	}

	@Test
	public void showsTheTextWithoutTheMarks() throws Throwable
	{
		onEdt(() ->
		{
			MessageView v = new MessageView();
			assertEquals("", text(v));
			v.setMarkdown("# Title\n\nHello **world**\n\n- one\n- two\n  - nested\n\n3. third\n\n> quote\n\n```\ncode\n  indented\n```\n\n"
				+ "---\n\n| a | b |\n|---|---|\n| 1 | 2 |\n\nend");
			// A table's lines are one paragraph, which it draws as a table.
			assertEquals("Title\nHello world\n\u2022\tone\n\u2022\ttwo\n\u2022\tnested\n3.\tthird\nquote\ncode\n  indented\n\n"
				+ "a | b\u2028--+--\u20281 | 2\nend", text(v));
		});
	}

	@Test
	public void stylesAndLinks() throws Throwable
	{
		onEdt(() ->
		{
			MessageView v = new MessageView();
			v.setMarkdown("Hi **bold** *it* ***both*** `code` [Wiki](" + WIKI + ") [bad](javascript:alert(1)) https://x.com\n\n"
				+ "> quoted\n\n## Head");
			assertFalse(StyleConstants.isBold(at(v, "Hi")));
			assertEquals(Color.WHITE, StyleConstants.getForeground(at(v, "Hi")));
			assertTrue(StyleConstants.isBold(at(v, "bold")));
			assertFalse(StyleConstants.isItalic(at(v, "bold")));
			assertTrue(StyleConstants.isItalic(at(v, "it")));
			assertTrue(StyleConstants.isBold(at(v, "both")) && StyleConstants.isItalic(at(v, "both")));
			assertEquals(Font.MONOSPACED, StyleConstants.getFontFamily(at(v, "code")));
			assertEquals(WIKI, at(v, "Wiki").getAttribute(MessageView.LINK));
			assertTrue(StyleConstants.isUnderline(at(v, "Wiki")));
			assertEquals("https://x.com", at(v, "https://x.com").getAttribute(MessageView.LINK));
			// Not a web address: plain text, nothing to click.
			assertNull(at(v, "bad").getAttribute(MessageView.LINK));
			assertFalse(StyleConstants.isUnderline(at(v, "bad")));
			assertNull(at(v, "Hi").getAttribute(MessageView.LINK));
			assertEquals(ColorScheme.LIGHT_GRAY_COLOR, StyleConstants.getForeground(at(v, "quoted")));
			assertTrue(StyleConstants.isBold(at(v, "Head")));
			assertTrue(StyleConstants.getFontSize(at(v, "Head")) > StyleConstants.getFontSize(at(v, "Hi")));

			v.setTextColor(Color.RED);
			assertEquals(Color.RED, StyleConstants.getForeground(at(v, "Hi")));
			assertEquals(ColorScheme.LIGHT_GRAY_COLOR, StyleConstants.getForeground(at(v, "quoted")));
			v.setTextFont(new Font(Font.SANS_SERIF, Font.PLAIN, 16));
			assertEquals(16, StyleConstants.getFontSize(at(v, "Hi")));
		});
	}

	@Test
	public void htmlIsShownAsText() throws Throwable
	{
		onEdt(() ->
		{
			MessageView v = new MessageView();
			v.setMarkdown("<b>not bold</b> <a href=\"https://x.com\">x</a>");
			assertEquals("<b>not bold</b> <a href=\"https://x.com\">x</a>", text(v));
			assertFalse(StyleConstants.isBold(at(v, "not bold")));
			assertEquals("text/plain", v.getContentType());
		});
	}

	@Test
	public void plainTextIsNotMarkdown() throws Throwable
	{
		onEdt(() ->
		{
			MessageView v = new MessageView();
			v.setPlainText("**what's** 5*3*2? See https://x.com.\n- not a list");
			assertEquals("**what's** 5*3*2? See https://x.com.\n- not a list", text(v));
			assertEquals("https://x.com", at(v, "https").getAttribute(MessageView.LINK));
			assertFalse(StyleConstants.isBold(at(v, "what")));
			assertEquals("**what's** 5*3*2? See https://x.com.\n- not a list", v.plainText());
			// Switching back to Markdown with the same text renders it as Markdown.
			v.setMarkdown("**what's**");
			assertEquals("what's", text(v));
		});
	}

	@Test
	public void plainTextForCopying() throws Throwable
	{
		onEdt(() ->
		{
			MessageView v = new MessageView();
			v.setMarkdown("Read [the Wiki](" + WIKI + ").\n\n- **one**\n- two");
			assertEquals("Read the Wiki (" + WIKI + ").\n\n- one\n- two", v.plainText());
			assertEquals("Read [the Wiki](" + WIKI + ").\n\n- **one**\n- two", v.getSource());
		});
	}

	@Test
	public void theSameTextIsntRenderedAgain() throws Throwable
	{
		onEdt(() ->
		{
			MessageView v = new MessageView();
			v.setMarkdown("Hello **there**");
			Document first = v.getDocument();
			v.setMarkdown("Hello **there**");
			assertSame(first, v.getDocument());
			v.setTextColor(Color.WHITE);
			assertSame(first, v.getDocument());
			v.setMarkdown("Hello **there**, more");
			assertNotSame(first, v.getDocument());
		});
	}

	@Test
	public void heightFollowsTheWidth() throws Throwable
	{
		onEdt(() ->
		{
			StringBuilder reply = new StringBuilder();
			for (int i = 0; i < 20; i++)
			{
				reply.append("Some words that will have to wrap in a narrow panel. ");
			}
			reply.append("\n\n- a list item that is long enough to wrap onto a second line in the sidebar\n\n```\ncode\n```");
			MessageView v = new MessageView();
			v.setMarkdown(reply.toString());
			int wide = StackLayout.heightFor(v, 600);
			int narrow = StackLayout.heightFor(v, 150);
			assertTrue(wide > 0);
			assertTrue(narrow + " vs " + wide, narrow > wide * 2);
			// Nothing left over from the last measurement.
			assertEquals(wide, StackLayout.heightFor(v, 600));
			// It never asks to be wider than it's given.
			assertEquals(600, v.getPreferredSize().width);
		});
	}

	@Test
	public void longWordsWrapInsteadOfWidening() throws Throwable
	{
		onEdt(() ->
		{
			MessageView one = new MessageView();
			one.setMarkdown("x");
			int line = StackLayout.heightFor(one, 150);
			assertTrue(line > 0);

			MessageView v = new MessageView();
			v.setMarkdown("https://oldschool.runescape.wiki/w/" + Markdown.repeat('A', 300));
			assertTrue(StackLayout.heightFor(v, 150) >= 3 * line);
			assertEquals(150, v.getPreferredSize().width);
		});
	}

	@Test
	public void heightInsideABubble() throws Throwable
	{
		onEdt(() ->
		{
			// As the panel uses it: in a bordered bubble, in the transcript's StackLayout.
			MessageView v = new MessageView();
			v.setMarkdown("**Abyssal whip**: about 1.5m on the GE. It's a good upgrade from a rune scimitar, and the "
				+ "[Wiki](" + WIKI + ") has more.\n\n1. Buy it\n2. Wield it");
			JPanel bubble = new JPanel(new BorderLayout());
			bubble.setBorder(new EmptyBorder(4, 6, 6, 6));
			bubble.add(v, BorderLayout.CENTER);
			int height = StackLayout.heightFor(bubble, 220);
			assertEquals(StackLayout.heightFor(v, 208) + 10, height);

			JPanel transcript = new JPanel(new StackLayout(6));
			transcript.add(bubble);
			transcript.setSize(220, 1000);
			transcript.doLayout();
			bubble.doLayout();
			assertEquals(208, v.getWidth());
			assertEquals(height - 10, v.getHeight());
		});
	}

	@Test
	public void emptyMessagesStillHaveALine() throws Throwable
	{
		onEdt(() ->
		{
			MessageView v = new MessageView();
			assertTrue(StackLayout.heightFor(v, 200) > 0);
			v.setMarkdown("");
			assertTrue(StackLayout.heightFor(v, 200) > 0);
			v.setPlainText(null);
			assertTrue(StackLayout.heightFor(v, 200) > 0);
		});
	}

	@Test
	public void everyPrefixOfAReplyRenders() throws Throwable
	{
		onEdt(() ->
		{
			// The live bubble while a reply streams in.
			String reply = "## Fire cape\n\n1. *High* Ranged, see [Fight Caves](https://oldschool.runescape.wiki/w/TzHaar_Fight_Cave).\n"
				+ "2. Prayer potions\n   - about `12`\n\n> Jad's attacks\n\n```\nflick\n```\n\n---\n\n| Item | Price |\n|---|--:|\n| Pot | 9k |\n\nDone";
			MessageView v = new MessageView();
			BufferedImage image = new BufferedImage(220, 600, BufferedImage.TYPE_INT_ARGB);
			for (int i = 0; i <= reply.length(); i++)
			{
				v.setMarkdown(reply.substring(0, i));
				int h = StackLayout.heightFor(v, 220);
				assertTrue(h > 0);
				v.setSize(220, h);
				Graphics2D g = image.createGraphics();
				v.print(g);
				g.dispose();
			}
		});
	}

	@Test
	public void drawsCodeBoxesQuoteBarsAndRules() throws Throwable
	{
		onEdt(() ->
		{
			MessageView v = new MessageView();
			v.setMarkdown("> quoted\n\n```\ncode\n```\n\n---\n\nend");
			int h = StackLayout.heightFor(v, 220);
			v.setSize(220, h);
			BufferedImage image = new BufferedImage(220, h, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = image.createGraphics();
			v.print(g);
			g.dispose();
			try
			{
				int quoteY = (int) v.modelToView2D(text(v).indexOf("quoted")).getCenterY();
				int codeY = (int) v.modelToView2D(text(v).indexOf("code")).getCenterY();
				// The quote's bar at the far left; the code's box across the whole width; nothing between them.
				assertTrue(alpha(image, 0, quoteY) > 0);
				assertEquals(0, alpha(image, 5, quoteY));
				assertTrue(alpha(image, 1, codeY) > 0);
				assertTrue(alpha(image, 219, codeY) > 0);
				assertEquals(0, alpha(image, 219, quoteY));
				// The rule: some row between the code and "end" is drawn across the width.
				int endY = (int) v.modelToView2D(text(v).indexOf("end")).getY();
				boolean rule = false;
				for (int y = codeY + 12; y < endY; y++)
				{
					rule |= alpha(image, 200, y) > 0 && alpha(image, 200, y - 2) == 0;
				}
				assertTrue(rule);
			}
			catch (BadLocationException e)
			{
				throw new AssertionError(e);
			}
		});
	}

	private static final String TABLE = "Bring:\n\n| Item | Why |\n|---|---|\n| **Extended antifire** | Dragonfire |\n"
		+ "| Anti-venom+ | His venom, which hits hard if you forget it |\n\nThat's all.";

	/** The views drawing tables in {@code v}, in order. */
	private static List<TableView> tables(MessageView v)
	{
		List<TableView> found = new ArrayList<>();
		collect(v.getUI().getRootView(v), found);
		return found;
	}

	private static void collect(View view, List<TableView> found)
	{
		if (view instanceof TableView)
		{
			found.add((TableView) view);
		}
		for (int i = 0; i < view.getViewCount(); i++)
		{
			collect(view.getView(i), found);
		}
	}

	@Test
	public void tablesAreDrawnAsTablesAndCopiedAsText() throws Throwable
	{
		onEdt(() ->
		{
			MessageView v = new MessageView();
			// Its letters see-through, so that what's drawn below is the table's own, whatever the font and its
			// antialiasing: with LCD antialiasing (ClearType's), a letter at the left edge tints the pixel beside it.
			v.setTextColor(new Color(0, 0, 0, 0));
			v.setMarkdown(TABLE);
			int h = StackLayout.heightFor(v, 220);
			v.setSize(220, h);
			List<TableView> tables = tables(v);
			assertEquals(1, tables.size());
			assertFalse("two columns fit the sidebar", tables.get(0).isCards());

			// Drawn: a rounded outline down its left side, across its rows; nothing there beside the text around it.
			BufferedImage image = new BufferedImage(220, h, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = image.createGraphics();
			v.print(g);
			g.dispose();
			try
			{
				// The table is one piece of the text, as tall as all of it.
				Rectangle2D table = v.modelToView2D(text(v).indexOf("Item"));
				int top = (int) table.getY();
				int bottom = (int) table.getMaxY();
				double line = v.modelToView2D(text(v).indexOf("Bring")).getHeight();
				assertTrue("three rows, one of them wrapped", table.getHeight() > 4 * line);
				assertTrue(v.modelToView2D(text(v).indexOf("That's")).getY() >= bottom);
				int inked = 0;
				for (int y = top + 8; y < bottom - 8; y++)
				{
					inked += alpha(image, 0, y) > 0 ? 1 : 0;
				}
				assertTrue("the outline", inked > (bottom - top - 16) * 3 / 4);
				int before = (int) v.modelToView2D(text(v).indexOf("Bring")).getCenterY();
				assertEquals(0, alpha(image, 0, before));
			}
			catch (BadLocationException e)
			{
				throw new AssertionError(e);
			}

			// Selected and copied whole, as lines of text, with the text around it: by the menu, and by Ctrl+C.
			v.selectAll();
			String lines = "Item              | Why\n"
				+ "------------------+--------------------------------------------\n"
				+ "Extended antifire | Dragonfire\n"
				+ "Anti-venom+       | His venom, which hits hard if you forget it";
			assertEquals("Bring:\n" + lines + "\nThat's all.", v.getSelectedText());
			Clipboard clipboard = new Clipboard("test");
			v.getTransferHandler().exportToClipboard(v, clipboard, TransferHandler.COPY);
			try
			{
				assertEquals("Bring:\n" + lines + "\nThat's all.", clipboard.getData(DataFlavor.stringFlavor));
			}
			catch (Exception e)
			{
				throw new AssertionError(e);
			}
			assertEquals("Bring:\n\n" + lines + "\n\nThat's all.", v.plainText());
		});
	}

	@Test
	public void aSelectedTableShowsNoSliverOfSelectionBesideIt() throws Throwable
	{
		onEdt(() ->
		{
			MessageView v = new MessageView();
			v.setMarkdown("Bring these:\n\n| Item | Why |\n|---|---|\n| Extended antifire | Dragonfire |\n"
				+ "| Anti-venom+ | His venom |\n\nThat's it.");
			// Wider than the sidebar, so there is room beside the table to look in whatever the machine's font.
			int h = StackLayout.heightFor(v, WIDE);
			v.setSize(WIDE, h);
			TableView table = tables(v).get(0);
			int right = (int) table.getPreferredSpan(View.X_AXIS);
			assertTrue("narrower than the message", right < WIDE - 40);
			// The line break that ends the table's paragraph is the table's own: not a sliver of text beside it, which
			// would show a selection of its own.
			Element root = v.getDocument().getDefaultRootElement();
			Element paragraph = root.getElement(root.getElementIndex(table.getStartOffset()));
			assertEquals(1, paragraph.getElementCount());
			assertEquals(paragraph.getEndOffset(), table.getEndOffset());

			v.selectAll();
			v.getCaret().setSelectionVisible(true);
			BufferedImage image = new BufferedImage(WIDE, h, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = image.createGraphics();
			v.print(g);
			g.dispose();
			try
			{
				Rectangle2D at = v.modelToView2D(text(v).indexOf("Item"));
				for (int y = (int) at.getY(); y < (int) at.getMaxY(); y++)
				{
					for (int x = right; x < WIDE; x++)
					{
						assertEquals("nothing beside the table at " + x + "," + y, 0, alpha(image, x, y));
					}
				}
				// The table itself shows it's selected, and the text after it too.
				assertTrue(alpha(image, right / 2, (int) at.getCenterY()) > 0);
				assertTrue(alpha(image, 2, (int) v.modelToView2D(text(v).indexOf("That's")).getCenterY()) > 0);
			}
			catch (BadLocationException e)
			{
				throw new AssertionError(e);
			}
			assertEquals("Bring these:\nItem              | Why\n------------------+-----------\n"
				+ "Extended antifire | Dragonfire\nAnti-venom+       | His venom\nThat's it.", v.getSelectedText());
		});
	}

	@Test
	public void tablesTooWideForTheSidebarAreCards() throws Throwable
	{
		onEdt(() ->
		{
			MessageView v = new MessageView();
			v.setMarkdown("| Crossbow | Price | Accuracy | Damage | Notes |\n|---|--:|--:|--:|---|\n"
				+ "| Dragon hunter | 72M | +95 | +122 | Best vs dragons |\n| Rune | 9k | +90 | +90 | |");
			int narrow = StackLayout.heightFor(v, 220);
			assertTrue(tables(v).get(0).isCards());
			// Wide enough for all of it: a table, one line a row.
			int wide = StackLayout.heightFor(v, 2000);
			assertFalse(tables(v).get(0).isCards());
			assertTrue("a card a row, a line a cell, is taller", narrow > 2 * wide);
		});
	}

	@Test
	public void aTableTooBigToDrawIsItsLines() throws Throwable
	{
		onEdt(() ->
		{
			StringBuilder reply = new StringBuilder("| n | square |\n|---|---|\n");
			for (int i = 0; i <= Markdown.MAX_TABLE_ROWS; i++)
			{
				reply.append("| ").append(i).append(" | ").append(i * i).append(" |\n");
			}
			MessageView v = new MessageView();
			v.setMarkdown(reply.toString());
			assertTrue(StackLayout.heightFor(v, 220) > 0);
			assertTrue(tables(v).isEmpty());
			assertTrue(text(v).startsWith("n   | square\n----+-------\n0   | 0"));
		});
	}

	private static int alpha(BufferedImage image, int x, int y)
	{
		return image.getRGB(x, y) >>> 24;
	}

	@Test
	public void linksAreFoundUnderTheMouse() throws Throwable
	{
		onEdt(() ->
		{
			MessageView v = new MessageView();
			v.setMarkdown("Read [the Wiki](" + WIKI + ") first");
			v.setSize(400, StackLayout.heightFor(v, 400));
			try
			{
				Rectangle2D wiki = v.modelToView2D(text(v).indexOf("Wiki"));
				Point onLink = new Point((int) wiki.getX() + 2, (int) wiki.getCenterY());
				assertEquals(WIKI, v.linkAt(onLink));
				assertEquals(WIKI, v.getToolTipText(new MouseEvent(v, MouseEvent.MOUSE_MOVED, 0, 0, onLink.x, onLink.y, 0, false)));

				Rectangle2D read = v.modelToView2D(text(v).indexOf("Read"));
				assertNull(v.linkAt(new Point((int) read.getX() + 2, (int) read.getCenterY())));
				// Past the end of the line, and below the text.
				assertNull(v.linkAt(new Point(395, (int) wiki.getCenterY())));
				assertNull(v.linkAt(new Point((int) wiki.getX() + 2, v.getHeight() + 50)));
			}
			catch (BadLocationException e)
			{
				throw new AssertionError(e);
			}
		});
	}

	@Test
	public void aLinkEndingALineIsOnlyALinkWhereItIs() throws Throwable
	{
		onEdt(() ->
		{
			MessageView v = new MessageView();
			v.setMarkdown("[Wiki](" + WIKI + ")\n\nnext");
			v.setSize(400, StackLayout.heightFor(v, 400));
			try
			{
				Rectangle2D last = v.modelToView2D(text(v).indexOf("i\n"));
				assertEquals(WIKI, v.linkAt(new Point((int) last.getX() + 1, (int) last.getCenterY())));
				assertNull(v.linkAt(new Point((int) last.getX() + 60, (int) last.getCenterY())));
			}
			catch (BadLocationException e)
			{
				throw new AssertionError(e);
			}
		});
	}

	@Test
	public void linesCanSitCentredOrRight() throws Throwable
	{
		onEdt(() ->
		{
			MessageView v = new MessageView();
			v.setPlainText("Stopped.");
			assertEquals(StyleConstants.ALIGN_LEFT, StyleConstants.getAlignment(paragraph(v)));
			v.setAlignment(StyleConstants.ALIGN_CENTER);
			assertEquals(StyleConstants.ALIGN_CENTER, StyleConstants.getAlignment(paragraph(v)));
			v.setAlignment(StyleConstants.ALIGN_RIGHT);
			v.setMarkdown("one\n\ntwo");
			assertEquals(StyleConstants.ALIGN_RIGHT, StyleConstants.getAlignment(paragraph(v)));

			// Drawn at the right edge: nothing in the left half.
			v.setSize(300, StackLayout.heightFor(v, 300));
			try
			{
				assertTrue(v.modelToView2D(0).getX() > 150);
			}
			catch (BadLocationException e)
			{
				throw new AssertionError(e);
			}
		});
	}

	private static AttributeSet paragraph(MessageView v)
	{
		return v.getStyledDocument().getParagraphElement(0).getAttributes();
	}

	@Test
	public void itKnowsHowWideItIsUnwrapped() throws Throwable
	{
		onEdt(() ->
		{
			MessageView v = new MessageView();
			v.setPlainText("hi");
			int small = v.naturalWidth();
			assertTrue(small > 0 && small < 40);
			v.setPlainText("A question long enough to wrap in the narrow panel, and then some more words");
			int big = v.naturalWidth();
			assertTrue(big > 300);
			int line = StackLayout.heightFor(one("x"), 400);
			// Its longest line, whatever width it was last laid out at.
			assertTrue(StackLayout.heightFor(v, 150) > line);
			assertEquals(big, v.naturalWidth());
			v.setPlainText("short\na much longer second line");
			assertEquals("the longest line", one("a much longer second line").naturalWidth(), v.naturalWidth());
			// A box that wide holds it without a wrap.
			v.setPlainText("How do I get a whip?");
			assertEquals(line, StackLayout.heightFor(v, v.naturalWidth()));
		});
	}

	@Test
	public void itsWidthIsTheTextsWhateverTheLookAndFeelAdds() throws Throwable
	{
		onEdt(() ->
		{
			int hugged = one("ok").naturalWidth();
			MessageView v = new MessageView();
			// As FlatLaf, which RuneLite's look and feel is built on, does: text components are at least 64 pixels wide.
			v.setUI(new BasicTextPaneUI()
			{
				@Override
				public Dimension getPreferredSize(JComponent c)
				{
					Dimension d = super.getPreferredSize(c);
					d.width = Math.max(d.width, 64);
					return d;
				}
			});
			v.setPlainText("ok");
			assertTrue("the look and feel's minimum", v.getPreferredSize().width >= 64);
			assertEquals("the bubble still hugs the text", hugged, v.naturalWidth());
			assertTrue(hugged < 30);
		});
	}

	private static MessageView one(String text)
	{
		MessageView v = new MessageView();
		v.setPlainText(text);
		return v;
	}

	@Test
	public void theMessagesOwnTooltipShowsAwayFromLinks() throws Throwable
	{
		onEdt(() ->
		{
			MessageView v = new MessageView();
			v.setMarkdown("[Wiki](" + WIKI + ") and some more words after it");
			v.setToolTipText("Claude \u00b7 14:02");
			v.setSize(400, StackLayout.heightFor(v, 400));
			try
			{
				Rectangle2D wiki = v.modelToView2D(1);
				assertEquals(WIKI, v.getToolTipText(move(v, (int) wiki.getX() + 1, (int) wiki.getCenterY())));
				Rectangle2D after = v.modelToView2D(text(v).indexOf("after"));
				assertEquals("Claude \u00b7 14:02", v.getToolTipText(move(v, (int) after.getX() + 1, (int) after.getCenterY())));
			}
			catch (BadLocationException e)
			{
				throw new AssertionError(e);
			}
		});
	}

	private static MouseEvent move(MessageView v, int x, int y)
	{
		return new MouseEvent(v, MouseEvent.MOUSE_MOVED, 0, 0, x, y, 0, false);
	}
}

