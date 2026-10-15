package com.osrsaibuddy;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Toolkit;
import java.awt.geom.Area;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.Element;
import javax.swing.text.JTextComponent;
import javax.swing.text.Position;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import javax.swing.text.View;

/**
 * A Markdown table in a {@link MessageView}, drawn the way chat apps draw tables: a rounded outline, a bold header row
 * over a line, light lines between the rows, and cells that wrap, the columns sharing the width by what's in them.
 * When the columns don't fit the narrow panel (more than {@link #MAX_COLUMNS}, or one squeezed under
 * {@link #MIN_COLUMN}), each row is a small card instead: its first cell in bold, then a "Header: value" line for each
 * other cell. Bold, italic and code in the cells keep their look; links are plain text. The table's text is in the
 * document as its lined-up lines (see {@link MessageView}), so selecting and copying the message takes the table along:
 * this view only draws it. Swing EDT only.
 */
final class TableView extends View
{
	/**
	 * Between the table's lines in the document: not a line break, which would make each line a paragraph. Unicode's
	 * line separator, written as an escape so that it can't be lost unseen.
	 */
	static final String ROW_BREAK = "\u2028";
	/** A table with more columns than this, that doesn't fit as it is, is drawn as cards. */
	static final int MAX_COLUMNS = 3;
	/** A column squeezed narrower than this is too narrow to read: the table is drawn as cards. */
	static final int MIN_COLUMN = 60;
	/** Space inside a cell, around its text. */
	private static final int PAD_X = 6;
	private static final int PAD_Y = 4;
	/** Space inside a card, around its text, and between cards. */
	private static final int CARD_PAD_X = 8;
	private static final int CARD_PAD_Y = 5;
	private static final int CARD_GAP = 6;
	private static final int ARC = 10;
	private static final Color OUTLINE = new Color(255, 255, 255, 60);
	private static final Color DIVIDER = new Color(255, 255, 255, 28);
	private static final Color HEADER_FILL = new Color(255, 255, 255, 14);
	private static final Color CODE_FILL = new Color(255, 255, 255, 30);
	/** A word, or the space between words. */
	private static final Pattern TOKEN = Pattern.compile("\\s+|\\S+");

	private final Markdown.Table table;
	/** How wide each column is unwrapped, padding included: the same at any width, so worked out once. */
	private int[] natural;
	/** The latest layout, for the width it was made for. */
	private Layout layout;

	TableView(Element elem, Markdown.Table table)
	{
		super(elem);
		this.table = table;
	}

	// ------------------------------------------------------------------
	// How wide the columns are, and when it's cards (pure)
	// ------------------------------------------------------------------

	/**
	 * Each column's width when the table has {@code available} pixels, from how wide each is unwrapped
	 * ({@code natural}, padding included): as wide as that when they all fit; otherwise the columns that fit in an equal
	 * share of the room keep their width, and the others share what's left equally (their text wraps).
	 */
	static int[] columnWidths(int[] natural, int available)
	{
		int n = natural.length;
		long total = 0;
		for (int w : natural)
		{
			total += w;
		}
		if (total <= available)
		{
			return natural.clone();
		}
		int[] widths = new int[n];
		boolean[] kept = new boolean[n];
		int left = Math.max(0, available);
		int open = n;
		boolean changed = true;
		while (changed && open > 0)
		{
			changed = false;
			int share = left / open;
			for (int c = 0; c < n; c++)
			{
				if (!kept[c] && natural[c] <= share)
				{
					kept[c] = true;
					widths[c] = natural[c];
					left -= natural[c];
					open--;
					changed = true;
				}
			}
		}
		// What's left, shared equally; the odd pixels to the first of them.
		int extra = open == 0 ? 0 : left % open;
		for (int c = 0; c < n; c++)
		{
			if (!kept[c])
			{
				widths[c] = left / open + (extra-- > 0 ? 1 : 0);
			}
		}
		return widths;
	}

	/**
	 * Whether a table whose columns want {@code natural} pixels and get {@code widths} is drawn as cards: not when it
	 * fits as it is; when it doesn't, if it has more than {@link #MAX_COLUMNS} columns, or a column gets less than it
	 * wants and less than {@link #MIN_COLUMN} (a narrow column that has all it wants is fine).
	 */
	static boolean cards(int[] natural, int[] widths)
	{
		boolean squeezed = false;
		boolean narrow = false;
		for (int c = 0; c < natural.length; c++)
		{
			squeezed |= widths[c] < natural[c];
			narrow |= widths[c] < natural[c] && widths[c] < MIN_COLUMN;
		}
		return squeezed && (natural.length > MAX_COLUMNS || narrow);
	}

	// ------------------------------------------------------------------
	// The view
	// ------------------------------------------------------------------

	/** Whether it's drawn as cards at the width it has now; for tests. */
	boolean isCards()
	{
		return layout(availableWidth()).cards;
	}

	@Override
	public float getPreferredSpan(int axis)
	{
		Layout l = layout(availableWidth());
		return axis == X_AXIS ? l.width : l.height;
	}

	@Override
	public float getMinimumSpan(int axis)
	{
		// It never makes its paragraph wider than the message: it wraps or turns into cards instead.
		return axis == X_AXIS ? 0 : getPreferredSpan(axis);
	}

	@Override
	public float getMaximumSpan(int axis)
	{
		return getPreferredSpan(axis);
	}

	@Override
	public Shape modelToView(int pos, Shape a, Position.Bias b) throws BadLocationException
	{
		if (pos < getStartOffset() || pos > getEndOffset())
		{
			throw new BadLocationException("Not in the table", pos);
		}
		// One piece: anywhere in its text is before it, and its end after it.
		Rectangle r = a.getBounds();
		return new Rectangle(pos < textEnd() ? r.x : r.x + r.width, r.y, 0, r.height);
	}

	/**
	 * Where the table's text ends: before the line break that ends its paragraph, when there's one. That's in this
	 * view too (see {@link MessageView}), so that nothing beside the table shows a selection.
	 */
	private int textEnd()
	{
		int end = getEndOffset();
		try
		{
			return end > getStartOffset() && getDocument().getText(end - 1, 1).equals("\n") ? end - 1 : end;
		}
		catch (BadLocationException e)
		{
			return end;
		}
	}

	@Override
	public int viewToModel(float x, float y, Shape a, Position.Bias[] biasReturn)
	{
		Rectangle r = a.getBounds();
		biasReturn[0] = Position.Bias.Forward;
		return y < r.y + r.height / 2f ? getStartOffset() : textEnd();
	}

	@Override
	public void paint(Graphics g, Shape a)
	{
		Rectangle r = a.getBounds();
		Layout l = layout(availableWidth());
		Graphics2D g2 = PanelStyle.smooth(g);
		try
		{
			Object hints = Toolkit.getDefaultToolkit().getDesktopProperty("awt.font.desktophints");
			if (hints instanceof Map)
			{
				g2.addRenderingHints((Map<?, ?>) hints);
			}
			else
			{
				g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			}
			Rectangle clip = g.getClipBounds();
			if (l.cards)
			{
				paintCards(g2, l, r.x, r.y, clip);
			}
			else
			{
				paintTable(g2, l, r.x, r.y, clip);
			}
			paintSelection(g2, l, r.x, r.y);
		}
		finally
		{
			g2.dispose();
		}
	}

	private void paintTable(Graphics2D g, Layout l, int x, int y, Rectangle clip)
	{
		if (l.rows.isEmpty())
		{
			return;
		}
		RoundRectangle2D outline = new RoundRectangle2D.Float(x + 0.5f, y + 0.5f, l.width - 1, l.height - 1, ARC, ARC);
		int headerHeight = l.rows.get(0).height;
		Area header = new Area(outline);
		header.intersect(new Area(new Rectangle(x, y, l.width, 1 + headerHeight)));
		g.setColor(HEADER_FILL);
		g.fill(header);
		int top = y + 1;
		for (int r = 0; r < l.rows.size(); r++)
		{
			Row row = l.rows.get(r);
			if (r > 0)
			{
				// The header's line is stronger than the lines between rows.
				g.setColor(r == 1 ? OUTLINE : DIVIDER);
				g.fillRect(x + 1, top - 1, l.width - 2, 1);
			}
			if (clip == null || top < clip.y + clip.height && top + row.height > clip.y)
			{
				int left = x + 1;
				for (int c = 0; c < row.cells.size(); c++)
				{
					int inner = l.columns[c] - 2 * PAD_X;
					drawLines(g, row.cells.get(c), left + PAD_X, top + PAD_Y, inner, table.align[c], l.lineHeight,
						l.ascent);
					left += l.columns[c];
				}
			}
			top += row.height + 1;
		}
		g.setColor(OUTLINE);
		g.draw(outline);
	}

	private void paintCards(Graphics2D g, Layout l, int x, int y, Rectangle clip)
	{
		int top = y;
		for (Row card : l.rows)
		{
			if (clip == null || top < clip.y + clip.height && top + card.height > clip.y)
			{
				g.setColor(OUTLINE);
				g.draw(new RoundRectangle2D.Float(x + 0.5f, top + 0.5f, l.width - 1, card.height - 1, ARC, ARC));
				int lineTop = top + 1 + CARD_PAD_Y;
				for (List<Line> field : card.cells)
				{
					drawLines(g, field, x + 1 + CARD_PAD_X, lineTop, l.width - 2 - 2 * CARD_PAD_X, Markdown.ALIGN_LEFT,
						l.lineHeight, l.ascent);
					lineTop += field.size() * l.lineHeight;
				}
			}
			top += card.height + CARD_GAP;
		}
	}

	private static void drawLines(Graphics2D g, List<Line> lines, int x, int y, int width, int align, int lineHeight,
		int ascent)
	{
		int baseline = y + ascent;
		for (Line line : lines)
		{
			int left = x + (align == Markdown.ALIGN_RIGHT ? width - line.width
				: align == Markdown.ALIGN_CENTER ? (width - line.width) / 2 : 0);
			for (Piece p : line.pieces)
			{
				if (p.run.code)
				{
					g.setColor(CODE_FILL);
					g.fillRoundRect(left + p.x - 1, baseline - ascent, p.width + 2, lineHeight, 4, 4);
				}
				g.setColor(p.run.color);
				g.setFont(p.run.font);
				g.drawString(p.text, left + p.x, baseline);
			}
			baseline += lineHeight;
		}
	}

	/** Over the table while the selection takes it in: it's selected and copied whole, with the text around it. */
	private void paintSelection(Graphics2D g, Layout l, int x, int y)
	{
		Container c = getContainer();
		if (!(c instanceof JTextComponent))
		{
			return;
		}
		JTextComponent text = (JTextComponent) c;
		int start = text.getSelectionStart();
		int end = text.getSelectionEnd();
		Color color = text.getSelectionColor();
		// As the text around it: only while the selection shows (the view has the focus).
		boolean shown = text.getCaret() != null && text.getCaret().isSelectionVisible();
		if (!shown || start == end || start >= textEnd() || end <= getStartOffset() || color == null)
		{
			return;
		}
		g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 90));
		g.fill(new RoundRectangle2D.Float(x, y, l.width, l.height, ARC, ARC));
	}

	// ------------------------------------------------------------------
	// Layout
	// ------------------------------------------------------------------

	/** The room the paragraph gives the table, or {@link Integer#MAX_VALUE} before it's been laid out. */
	private int availableWidth()
	{
		for (View v = getParent(); v != null; v = v.getParent())
		{
			if (v instanceof MessageView.BlockView)
			{
				return ((MessageView.BlockView) v).flowWidth();
			}
		}
		return Integer.MAX_VALUE;
	}

	/** The table laid out in {@code available} pixels; worked out once for each width. */
	private Layout layout(int available)
	{
		if (layout != null && layout.available == available)
		{
			return layout;
		}
		Component c = getContainer();
		Layout before = layout;
		layout = c == null ? new Layout(available) : layOut(c, available);
		if (before != null && before.height != layout.height)
		{
			// Taller or shorter at the new width. The paragraph keeps its rows' heights from before (text lines are
			// all as tall as ever): it's told, so it asks again.
			preferenceChanged(null, true, true);
		}
		return layout;
	}

	private Layout layOut(Component c, int available)
	{
		AttributeSet attrs = getAttributes();
		Font base = ((StyledDocument) getDocument()).getFont(attrs);
		Color color = StyleConstants.getForeground(attrs);
		Fonts fonts = new Fonts(base, color);
		FontMetrics fm = c.getFontMetrics(base);
		Layout l = new Layout(available);
		l.lineHeight = fm.getHeight();
		l.ascent = fm.getAscent();

		int columns = table.align.length;
		if (natural == null)
		{
			natural = new int[columns];
			for (int r = 0; r < table.rows.size(); r++)
			{
				for (int col = 0; col < columns; col++)
				{
					List<Run> runs = runs(table.rows.get(r).get(col), fonts, r == 0);
					natural[col] = Math.max(natural[col], unwrapped(c, runs) + 2 * PAD_X);
				}
			}
		}
		// Inside the outline.
		int room = available == Integer.MAX_VALUE ? Integer.MAX_VALUE : available - 2;
		int[] widths = columnWidths(natural, room);
		l.cards = cards(natural, widths);
		if (l.cards)
		{
			layOutCards(c, l, fonts, available);
		}
		else
		{
			l.columns = widths;
			l.width = 2;
			for (int w : widths)
			{
				l.width += w;
			}
			l.height = 1;
			for (int r = 0; r < table.rows.size(); r++)
			{
				Row row = new Row();
				int lines = 1;
				for (int col = 0; col < columns; col++)
				{
					List<Line> cell = wrap(c, runs(table.rows.get(r).get(col), fonts, r == 0), widths[col] - 2 * PAD_X);
					row.cells.add(cell);
					lines = Math.max(lines, cell.size());
				}
				row.height = lines * l.lineHeight + 2 * PAD_Y;
				l.rows.add(row);
				// The row and the line under it (the outline, under the last).
				l.height += row.height + 1;
			}
		}
		return l;
	}

	/** One card per row (the header's own when there's no other yet): the first cell in bold, then the others. */
	private void layOutCards(Component c, Layout l, Fonts fonts, int available)
	{
		l.width = available == Integer.MAX_VALUE ? 0 : available;
		int inner = l.width - 2 - 2 * CARD_PAD_X;
		List<List<Markdown.Span>> header = table.rows.get(0);
		boolean headerOnly = table.rows.size() == 1;
		for (int r = headerOnly ? 0 : 1; r < table.rows.size(); r++)
		{
			List<List<Markdown.Span>> cells = table.rows.get(r);
			Row card = new Row();
			if (!cells.get(0).isEmpty())
			{
				card.cells.add(wrap(c, runs(cells.get(0), fonts, true), inner));
			}
			for (int col = 1; col < cells.size(); col++)
			{
				if (cells.get(col).isEmpty())
				{
					continue;
				}
				List<Run> field = new ArrayList<>();
				if (!headerOnly && !header.get(col).isEmpty())
				{
					String label = Markdown.plainText(header.get(col), false) + ": ";
					field.add(new Run(label, fonts.base, false, fonts.label));
				}
				field.addAll(runs(cells.get(col), fonts, false));
				card.cells.add(wrap(c, field, inner));
			}
			int lines = 0;
			for (List<Line> field : card.cells)
			{
				lines += field.size();
			}
			card.height = Math.max(1, lines) * l.lineHeight + 2 * CARD_PAD_Y + 2;
			l.rows.add(card);
			l.height += card.height + (l.rows.size() > 1 ? CARD_GAP : 0);
		}
	}

	/** The fonts and colours a table is drawn in, from its text's. */
	private static final class Fonts
	{
		final Font base;
		final Font bold;
		final Font italic;
		final Font boldItalic;
		final Font code;
		final Color color;
		/** For a card's "Header:", quieter than the value after it. */
		final Color label;

		Fonts(Font base, Color color)
		{
			this.base = base;
			bold = base.deriveFont(Font.BOLD);
			italic = base.deriveFont(Font.ITALIC);
			boldItalic = base.deriveFont(Font.BOLD | Font.ITALIC);
			code = new Font(Font.MONOSPACED, Font.PLAIN, Math.max(1, base.getSize() - 1));
			this.color = color;
			label = new Color(color.getRed(), color.getGreen(), color.getBlue(), 150);
		}
	}

	/** A cell's spans as runs of text, each in its font; a header's in bold. Links are plain text here. */
	private static List<Run> runs(List<Markdown.Span> spans, Fonts fonts, boolean header)
	{
		List<Run> runs = new ArrayList<>();
		for (Markdown.Span s : spans)
		{
			Font font;
			switch (s.kind)
			{
				case BOLD:
					font = fonts.bold;
					break;
				case ITALIC:
					font = header ? fonts.boldItalic : fonts.italic;
					break;
				case BOLD_ITALIC:
					font = fonts.boldItalic;
					break;
				case CODE:
					font = fonts.code;
					break;
				default:
					font = header ? fonts.bold : fonts.base;
					break;
			}
			runs.add(new Run(s.text, font, s.kind == Markdown.SpanKind.CODE, fonts.color));
		}
		return runs;
	}

	/** How wide {@code runs} are on one line. */
	private static int unwrapped(Component c, List<Run> runs)
	{
		List<Line> lines = wrap(c, runs, Integer.MAX_VALUE);
		return lines.isEmpty() ? 0 : lines.get(0).width;
	}

	/**
	 * {@code runs} wrapped to {@code width}: lines break between words, and inside a word too long for a line of its
	 * own. Words of one run on a line are one piece of text, measured and drawn whole, so their spaces are the font's.
	 */
	static List<Line> wrap(Component c, List<Run> runs, int width)
	{
		List<Line> lines = new ArrayList<>();
		Line line = new Line();
		boolean space = false;
		for (Run run : runs)
		{
			FontMetrics fm = c.getFontMetrics(run.font);
			Matcher m = TOKEN.matcher(run.text);
			while (m.find())
			{
				String word = m.group();
				if (Character.isWhitespace(word.charAt(0)))
				{
					// None at the start of a line.
					space = !line.pieces.isEmpty();
					continue;
				}
				if (!line.pieces.isEmpty() && line.widthWith(word, run, space, fm) > width)
				{
					lines.add(line);
					line = new Line();
					space = false;
				}
				// Too long for a line of its own: as much as fits on each.
				while (word.length() > 1 && fm.stringWidth(word) > width)
				{
					int n = fitting(fm, word, width);
					line.add(word.substring(0, n), run, false, fm);
					lines.add(line);
					line = new Line();
					word = word.substring(n);
				}
				line.add(word, run, space, fm);
				space = false;
			}
		}
		if (!line.pieces.isEmpty() || lines.isEmpty())
		{
			lines.add(line);
		}
		return lines;
	}

	/** How many of {@code word}'s first characters fit in {@code width}: at least one. */
	private static int fitting(FontMetrics fm, String word, int width)
	{
		int n = 1;
		while (n < word.length() && fm.stringWidth(word.substring(0, n + 1)) <= width)
		{
			n++;
		}
		return n;
	}

	/** Text in one font and colour, maybe code (drawn on a faint patch). */
	static final class Run
	{
		final String text;
		final Font font;
		final boolean code;
		final Color color;

		Run(String text, Font font, boolean code, Color color)
		{
			this.text = text;
			this.font = font;
			this.code = code;
			this.color = color;
		}
	}

	/** Words of one run on a line, and where they start on it. */
	private static final class Piece
	{
		final Run run;
		final int x;
		String text;
		int width;

		Piece(Run run, int x, String text, int width)
		{
			this.run = run;
			this.x = x;
			this.text = text;
			this.width = width;
		}
	}

	/** A wrapped line: its pieces, and how wide they are together. */
	static final class Line
	{
		final List<Piece> pieces = new ArrayList<>();
		int width;

		/** How wide the line would be with {@code word} of {@code run} added, after a space if {@code space}. */
		int widthWith(String word, Run run, boolean space, FontMetrics fm)
		{
			Piece last = pieces.isEmpty() ? null : pieces.get(pieces.size() - 1);
			if (last != null && last.run == run)
			{
				return last.x + fm.stringWidth(last.text + (space ? " " : "") + word);
			}
			return width + (space ? fm.charWidth(' ') : 0) + fm.stringWidth(word);
		}

		void add(String word, Run run, boolean space, FontMetrics fm)
		{
			Piece last = pieces.isEmpty() ? null : pieces.get(pieces.size() - 1);
			if (last != null && last.run == run)
			{
				last.text += (space ? " " : "") + word;
				last.width = fm.stringWidth(last.text);
				width = last.x + last.width;
				return;
			}
			int x = width + (space ? fm.charWidth(' ') : 0);
			int w = fm.stringWidth(word);
			pieces.add(new Piece(run, x, word, w));
			width = x + w;
		}
	}

	/** A row of the table (its cells' lines), or a card (its fields' lines). */
	private static final class Row
	{
		final List<List<Line>> cells = new ArrayList<>();
		int height;
	}

	/** The table laid out for one width. */
	private static final class Layout
	{
		final int available;
		boolean cards;
		/** The table's columns' widths, padding included; unused for cards. */
		int[] columns = new int[0];
		final List<Row> rows = new ArrayList<>();
		int width;
		int height;
		int lineHeight;
		int ascent;

		Layout(int available)
		{
			this.available = available;
		}
	}
}
