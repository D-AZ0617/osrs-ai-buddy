package com.osrsaibuddy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import okhttp3.HttpUrl;

/**
 * Reads the bit of Markdown replies are written in into blocks of styled text, for {@link MessageView}: paragraphs,
 * headings, lists, quotes, code, rules and tables; bold, italic, inline code and links. Pure, so any thread can use it.
 * HTML is never interpreted ("<b>" is shown as it is), only http and https addresses become links, and anything
 * unfinished (an unclosed "**" while a reply streams in, half a link) is shown as the plain text it is.
 */
class Markdown
{
	enum BlockKind
	{
		PARAGRAPH, HEADING, BULLET, NUMBERED, QUOTE, CODE, RULE, TABLE
	}

	enum SpanKind
	{
		TEXT, BOLD, ITALIC, BOLD_ITALIC, CODE, LINK
	}

	static class Block
	{
		final BlockKind kind;
		/** HEADING: 1 to 6. */
		final int level;
		/**
		 * BULLET and NUMBERED: how deeply the list is nested, 0 for a list that isn't inside another. Every other kind:
		 * how many list items the block is part of (a second paragraph or a code block under an item), usually 0.
		 */
		final int depth;
		/** NUMBERED: the number to show. */
		final int number;
		/** The text of every kind but CODE, TABLE and RULE; it can contain line breaks. Empty for those. */
		final List<Span> spans;
		/**
		 * CODE: the lines to show as they are, in a monospaced font. TABLE: the table as such lines, cells padded to
		 * line up, which is also how it's copied. Empty for the others.
		 */
		final List<String> lines;
		/** TABLE: its cells, to draw it as a table; null when it's too big to draw, and is shown as its lines. */
		final Table table;

		Block(BlockKind kind, int level, int depth, int number, List<Span> spans, List<String> lines)
		{
			this(kind, level, depth, number, spans, lines, null);
		}

		Block(BlockKind kind, int level, int depth, int number, List<Span> spans, List<String> lines, Table table)
		{
			this.kind = kind;
			this.level = level;
			this.depth = depth;
			this.number = number;
			this.spans = spans;
			this.lines = lines;
			this.table = table;
		}
	}

	/** A table's cells, for drawing it as a table. */
	static class Table
	{
		/** One {@code ALIGN_*} per column. */
		final int[] align;
		/**
		 * The rows, the header first, each with a cell per column (an empty one where the row had too few) holding its
		 * text with its formatting.
		 */
		final List<List<List<Span>>> rows;

		Table(int[] align, List<List<List<Span>>> rows)
		{
			this.align = align;
			this.rows = rows;
		}
	}

	static class Span
	{
		final SpanKind kind;
		final String text;
		/** LINK: the http or https address, as written. Null for the other kinds. */
		final String url;

		Span(SpanKind kind, String text, String url)
		{
			this.kind = kind;
			this.text = text;
			this.url = url;
		}
	}

	/** Longest link text looked for: a stray "[" mustn't make the rest of a long reply be searched again and again. */
	private static final int MAX_LABEL = 1000;
	private static final int MAX_URL = 2000;
	private static final String ASCII_PUNCTUATION = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~";
	/** Left off the end of a bare address: "see https://example.com." means the address without the full stop. */
	private static final String URL_TRAILERS = ".,;:!?*_~'\"";
	static final int ALIGN_LEFT = 0;
	static final int ALIGN_CENTER = 1;
	static final int ALIGN_RIGHT = 2;
	/**
	 * Most characters a table may take once its cells are padded to line up. Padding fills every row to the longest
	 * cell of each column, so one long cell over thousands of short rows would turn a short reply into hundreds of
	 * megabytes; a table past this is shown as written instead.
	 */
	static final int MAX_TABLE = 20_000;
	/**
	 * Most rows a table may have to be drawn as a table (it's drawn again with every few words of a reply that streams
	 * in); one with more is shown as its lined-up lines, as code is.
	 */
	static final int MAX_TABLE_ROWS = 200;

	private Markdown()
	{
	}

	/** The blocks of {@code text}, in order. Never fails: whatever isn't recognised is text. */
	static List<Block> parse(String text)
	{
		if (text == null || text.isEmpty())
		{
			return Collections.emptyList();
		}
		return new BlockParser(lines(text)).parse();
	}

	/** Spans for text that isn't Markdown (the player's own messages, errors): only bare http(s) addresses are links. */
	static List<Span> linkify(String text)
	{
		return new InlineParser(text == null ? "" : text, false).parse();
	}

	/**
	 * The text as it reads, for copying: list markers and quote marks kept, formatting marks gone, and each link's
	 * address after its text.
	 */
	static String plainText(List<Block> blocks)
	{
		StringBuilder out = new StringBuilder();
		Block previous = null;
		for (Block b : blocks)
		{
			if (previous != null)
			{
				// List items (and what's inside them) go on the next line; everything else gets a blank line between.
				boolean list = isItem(previous) && (isItem(b) || b.depth > 0);
				out.append(list ? "\n" : "\n\n");
			}
			String indent = repeat(' ', 2 * b.depth);
			switch (b.kind)
			{
				case BULLET:
					appendIndented(out, indent + "- ", indent + "  ", plainText(b.spans, true));
					break;
				case NUMBERED:
					String marker = b.number + ". ";
					appendIndented(out, indent + marker, indent + repeat(' ', marker.length()), plainText(b.spans, true));
					break;
				case QUOTE:
					appendIndented(out, indent + "> ", indent + "> ", plainText(b.spans, true));
					break;
				case CODE:
				case TABLE:
					appendIndented(out, indent, indent, String.join("\n", b.lines));
					break;
				case RULE:
					out.append(indent).append("----");
					break;
				default:
					appendIndented(out, indent, indent, plainText(b.spans, true));
					break;
			}
			previous = b;
		}
		return out.toString();
	}

	static String plainText(String markdown)
	{
		return plainText(parse(markdown));
	}

	/** The spans' text run together; with {@code urls}, each link's address follows its text unless they're the same. */
	static String plainText(List<Span> spans, boolean urls)
	{
		StringBuilder out = new StringBuilder();
		for (Span s : spans)
		{
			out.append(s.text);
			if (urls && s.kind == SpanKind.LINK && !s.text.equals(s.url))
			{
				out.append(" (").append(s.url).append(')');
			}
		}
		return out.toString();
	}

	/**
	 * A table as monospaced lines: cells padded to line up, " | " between columns and a rule under the header row.
	 * {@code rows} starts with the header; {@code align} has one {@code ALIGN_*} per column. A table that would take
	 * more than {@link #MAX_TABLE} characters lined up is shown as written: its cells unpadded, missing ones left out.
	 */
	static List<String> tableLines(List<List<String>> rows, int[] align)
	{
		int columns = align.length;
		int[] width = columnChars(rows, columns);
		boolean lineUp = linesUp(rows, width);
		List<String> out = new ArrayList<>();
		for (int r = 0; r < rows.size(); r++)
		{
			List<String> row = rows.get(r);
			StringBuilder line = new StringBuilder();
			for (int c = 0; c < (lineUp ? columns : Math.min(columns, row.size())); c++)
			{
				if (c > 0)
				{
					line.append(" | ");
				}
				line.append(lineUp ? pad(cell(row, c), width[c], align[c]) : row.get(c));
			}
			out.add(line.toString().stripTrailing());
			if (r == 0)
			{
				out.add(lineUp ? rule(width) : repeat('-', Math.max(1, out.get(0).length())));
			}
		}
		return out;
	}

	/** How many characters each column's widest cell has (at least one). */
	private static int[] columnChars(List<List<String>> rows, int columns)
	{
		int[] width = new int[columns];
		Arrays.fill(width, 1);
		for (List<String> row : rows)
		{
			// Only the cells a row has: one that's missing is empty, and looking at it would take as long as padding it.
			for (int c = 0; c < Math.min(columns, row.size()); c++)
			{
				width[c] = Math.max(width[c], row.get(c).length());
			}
		}
		return width;
	}

	/** Whether the table, its columns {@code width} characters wide, takes at most {@link #MAX_TABLE} lined up. */
	private static boolean linesUp(List<List<String>> rows, int[] width)
	{
		long lineWidth = 3L * (width.length - 1);
		for (int w : width)
		{
			lineWidth += w;
		}
		return lineWidth * (rows.size() + 1) <= MAX_TABLE;
	}

	/** The rule under a lined-up header: dashes as wide as each column, "+" where the " | " go. */
	private static String rule(int[] width)
	{
		StringBuilder rule = new StringBuilder();
		for (int c = 0; c < width.length; c++)
		{
			rule.append(c > 0 ? "-+-" : "").append(repeat('-', width[c]));
		}
		return rule.toString();
	}

	private static String cell(List<String> row, int c)
	{
		return c < row.size() ? row.get(c) : "";
	}

	private static String pad(String s, int width, int align)
	{
		int space = width - s.length();
		if (align == ALIGN_RIGHT)
		{
			return repeat(' ', space) + s;
		}
		if (align == ALIGN_CENTER)
		{
			return repeat(' ', space / 2) + s + repeat(' ', space - space / 2);
		}
		return s + repeat(' ', space);
	}

	private static boolean isItem(Block b)
	{
		return b.kind == BlockKind.BULLET || b.kind == BlockKind.NUMBERED;
	}

	private static void appendIndented(StringBuilder out, String first, String rest, String text)
	{
		String[] lines = text.split("\n", -1);
		for (int i = 0; i < lines.length; i++)
		{
			out.append(i == 0 ? "" : "\n").append(i == 0 ? first : rest).append(lines[i]);
		}
	}

	static String repeat(char c, int n)
	{
		if (n <= 0)
		{
			return "";
		}
		char[] chars = new char[n];
		Arrays.fill(chars, c);
		return new String(chars);
	}

	/** Splits on \n, \r\n and \r alike. */
	private static List<String> lines(String text)
	{
		List<String> out = new ArrayList<>();
		int start = 0;
		for (int i = 0; i < text.length(); i++)
		{
			char c = text.charAt(i);
			if (c == '\n' || c == '\r')
			{
				out.add(text.substring(start, i));
				if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n')
				{
					i++;
				}
				start = i + 1;
			}
		}
		out.add(text.substring(start));
		return out;
	}

	/** Columns of leading whitespace, a tab reaching the next multiple of 4. */
	private static int indentOf(String line)
	{
		int col = 0;
		for (int i = 0; i < line.length(); i++)
		{
			char c = line.charAt(i);
			if (c == ' ')
			{
				col++;
			}
			else if (c == '\t')
			{
				col += 4 - col % 4;
			}
			else
			{
				break;
			}
		}
		return col;
	}

	private static String stripIndent(String line)
	{
		int i = 0;
		while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t'))
		{
			i++;
		}
		return line.substring(i);
	}

	/** Takes up to {@code cols} columns of indentation off the line (code inside a list item), tabs made spaces. */
	private static String removeIndent(String line, int cols)
	{
		String expanded = expandTabs(line);
		int i = 0;
		while (i < cols && i < expanded.length() && expanded.charAt(i) == ' ')
		{
			i++;
		}
		return expanded.substring(i);
	}

	/** Tabs as spaces up to the next multiple of 4: Swing's tab stops would be far too wide in code. */
	private static String expandTabs(String line)
	{
		if (line.indexOf('\t') < 0)
		{
			return line;
		}
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < line.length(); i++)
		{
			char c = line.charAt(i);
			if (c == '\t')
			{
				out.append(repeat(' ', 4 - out.length() % 4));
			}
			else
			{
				out.append(c);
			}
		}
		return out.toString();
	}

	/** {@code dest} if it's an http or https address that can be opened, else null. */
	static String linkUrl(String dest)
	{
		if (!startsWithIgnoreCase(dest, 0, "http://") && !startsWithIgnoreCase(dest, 0, "https://"))
		{
			return null;
		}
		return HttpUrl.parse(dest) != null ? dest : null;
	}

	private static boolean startsWithIgnoreCase(String s, int at, String prefix)
	{
		return s.regionMatches(true, at, prefix, 0, prefix.length());
	}

	private static boolean isWhitespace(char c)
	{
		return Character.isWhitespace(c) || Character.isSpaceChar(c);
	}

	private static boolean isPunctuation(char c)
	{
		if (c < 128)
		{
			return ASCII_PUNCTUATION.indexOf(c) >= 0;
		}
		switch (Character.getType(c))
		{
			case Character.CONNECTOR_PUNCTUATION:
			case Character.DASH_PUNCTUATION:
			case Character.START_PUNCTUATION:
			case Character.END_PUNCTUATION:
			case Character.INITIAL_QUOTE_PUNCTUATION:
			case Character.FINAL_QUOTE_PUNCTUATION:
			case Character.OTHER_PUNCTUATION:
			case Character.MATH_SYMBOL:
			case Character.CURRENCY_SYMBOL:
			case Character.MODIFIER_SYMBOL:
			case Character.OTHER_SYMBOL:
				return true;
			default:
				return false;
		}
	}

	/** An open list item: where its marker is and how far in a line must be to be part of it. */
	private static class Item
	{
		final int childIndent;
		final boolean numbered;
		final int number;

		Item(int markerIndent, int contentIndent, boolean numbered, int number)
		{
			// Lenient on purpose: "1. a" then "  - b" is meant as a nested list even though "b" isn't under "a".
			this.childIndent = Math.min(markerIndent + 2, contentIndent);
			this.numbered = numbered;
			this.number = number;
		}
	}

	/** "- ", "* ", "+ ", "1. " or "1) " starting a line. */
	private static class Marker
	{
		final boolean numbered;
		final int number;
		/** From the marker to its text, which is where the item's own content starts. */
		final int width;
		final String content;

		Marker(boolean numbered, int number, int width, String content)
		{
			this.numbered = numbered;
			this.number = number;
			this.width = width;
			this.content = content;
		}

		static Marker read(String rest)
		{
			if (rest.isEmpty())
			{
				return null;
			}
			char c = rest.charAt(0);
			int end;
			int number = 0;
			boolean numbered = false;
			if (c == '-' || c == '*' || c == '+')
			{
				end = 1;
			}
			else
			{
				int i = 0;
				while (i < rest.length() && i < 9 && rest.charAt(i) >= '0' && rest.charAt(i) <= '9')
				{
					i++;
				}
				if (i == 0 || i >= rest.length() || (rest.charAt(i) != '.' && rest.charAt(i) != ')'))
				{
					return null;
				}
				number = Integer.parseInt(rest.substring(0, i));
				numbered = true;
				end = i + 1;
			}
			// "-5 coins" and "1.5m" aren't list items.
			if (end < rest.length() && rest.charAt(end) != ' ' && rest.charAt(end) != '\t')
			{
				return null;
			}
			int spaces = 0;
			while (end + spaces < rest.length() && (rest.charAt(end + spaces) == ' ' || rest.charAt(end + spaces) == '\t'))
			{
				spaces++;
			}
			String content = rest.substring(end + spaces).trim();
			return new Marker(numbered, number, end + (spaces >= 1 && spaces <= 4 ? spaces : 1), content);
		}
	}

	/**
	 * Reads lines into blocks. Close to CommonMark, but a single line break stays a line break (replies are often
	 * written line by line), nesting is lenient, and there are no setext headings, indented code or HTML blocks.
	 */
	private static class BlockParser
	{
		private final List<String> lines;
		private final List<Block> blocks = new ArrayList<>();
		/** The list items the next line may still be part of, outermost first. */
		private final List<Item> items = new ArrayList<>();
		private int pos;
		/** The paragraph, list item or quote being read, or null. */
		private StringBuilder text;
		private BlockKind textKind;
		private int textDepth;
		private int textNumber;

		BlockParser(List<String> lines)
		{
			this.lines = lines;
		}

		List<Block> parse()
		{
			while (pos < lines.size())
			{
				String line = lines.get(pos++);
				if (line.isBlank())
				{
					endText();
				}
				else
				{
					readLine(line);
				}
			}
			endText();
			return blocks;
		}

		private void readLine(String line)
		{
			int indent = indentOf(line);
			String rest = stripIndent(line).stripTrailing();

			int[] fence = fence(rest);
			if (fence != null)
			{
				int depth = enter(indent);
				endText();
				readCode((char) fence[0], fence[1], indent, depth);
				return;
			}
			if (isRule(rest))
			{
				int depth = enter(indent);
				endText();
				blocks.add(new Block(BlockKind.RULE, 0, depth, 0, Collections.emptyList(), Collections.emptyList()));
				return;
			}
			int level = headingLevel(rest);
			if (level > 0)
			{
				int depth = enter(indent);
				endText();
				blocks.add(new Block(BlockKind.HEADING, level, depth, 0, inline(headingText(rest, level)), Collections.emptyList()));
				return;
			}
			if (rest.startsWith(">"))
			{
				readQuote(indent, quoteContent(rest));
				return;
			}
			if (tableStartsAt(rest))
			{
				int depth = enter(indent);
				endText();
				readTable(rest, depth);
				return;
			}
			Marker marker = Marker.read(rest);
			if (marker != null && canStartItem(marker))
			{
				startItem(indent, marker);
				return;
			}
			if (text != null && textKind != BlockKind.QUOTE)
			{
				// The next line of the same paragraph or item, however it's indented.
				if (text.length() > 0)
				{
					text.append('\n');
				}
				text.append(rest);
				return;
			}
			int depth = enter(indent);
			endText();
			startText(BlockKind.PARAGRAPH, depth, 0, rest);
		}

		/** Leaves the list items that a line indented this much isn't part of; returns how many it's still in. */
		private int enter(int indent)
		{
			while (!items.isEmpty() && indent < items.get(items.size() - 1).childIndent)
			{
				items.remove(items.size() - 1);
			}
			return items.size();
		}

		/**
		 * Like CommonMark, a list only interrupts a paragraph when it clearly starts one, so "Released in\n2023. It
		 * was..." stays a paragraph.
		 */
		private boolean canStartItem(Marker m)
		{
			if (text == null || textKind != BlockKind.PARAGRAPH)
			{
				return true;
			}
			return !m.content.isEmpty() && (!m.numbered || m.number == 1);
		}

		private void startItem(int indent, Marker m)
		{
			endText();
			Item sibling = null;
			while (!items.isEmpty() && indent < items.get(items.size() - 1).childIndent)
			{
				sibling = items.remove(items.size() - 1);
			}
			int depth = items.size();
			// Numbered like CommonMark: the first item's number, then counting up, whatever the later ones say.
			int number = m.numbered && sibling != null && sibling.numbered ? sibling.number + 1 : m.number;
			items.add(new Item(indent, indent + m.width, m.numbered, number));
			startText(m.numbered ? BlockKind.NUMBERED : BlockKind.BULLET, depth, number, m.content);
		}

		private void readQuote(int indent, String content)
		{
			if (text != null && textKind == BlockKind.QUOTE)
			{
				if (content.isEmpty())
				{
					// A ">" line on its own separates paragraphs inside the quote.
					endText();
				}
				else
				{
					text.append('\n').append(content);
				}
				return;
			}
			int depth = enter(indent);
			endText();
			if (!content.isEmpty())
			{
				startText(BlockKind.QUOTE, depth, 0, content);
			}
		}

		private void readCode(char fenceChar, int fenceLength, int indent, int depth)
		{
			List<String> code = new ArrayList<>();
			while (pos < lines.size())
			{
				String line = lines.get(pos++);
				if (closesFence(stripIndent(line), fenceChar, fenceLength))
				{
					break;
				}
				code.add(removeIndent(line, indent));
			}
			// An unclosed fence (a reply still streaming) runs to the end, like CommonMark.
			blocks.add(new Block(BlockKind.CODE, 0, depth, 0, Collections.emptyList(), code));
		}

		/** This line has cells, and the next one is a delimiter row ("|---|:--:|") with as many. */
		private boolean tableStartsAt(String rest)
		{
			if (pos >= lines.size() || rest.indexOf('|') < 0)
			{
				return false;
			}
			int[] align = delimiterRow(lines.get(pos));
			return align != null && align.length == splitCells(rest).size();
		}

		private void readTable(String header, int depth)
		{
			int[] align = delimiterRow(lines.get(pos++));
			List<List<List<Span>>> cells = new ArrayList<>();
			cells.add(tableCells(header, align.length));
			while (pos < lines.size() && !lines.get(pos).isBlank() && lines.get(pos).indexOf('|') >= 0)
			{
				cells.add(tableCells(lines.get(pos++), align.length));
			}
			// As text, formatting marks dropped: how it's copied, and shown when it's too big to draw.
			List<List<String>> rows = new ArrayList<>();
			for (List<List<Span>> row : cells)
			{
				List<String> plain = new ArrayList<>();
				for (List<Span> cell : row)
				{
					plain.add(plainText(cell, false));
				}
				rows.add(plain);
			}
			boolean drawn = rows.size() <= MAX_TABLE_ROWS && linesUp(rows, columnChars(rows, align.length));
			if (drawn)
			{
				// Every row as many cells as there are columns, for the drawing.
				for (List<List<Span>> row : cells)
				{
					while (row.size() < align.length)
					{
						row.add(Collections.emptyList());
					}
				}
			}
			blocks.add(new Block(BlockKind.TABLE, 0, depth, 0, Collections.emptyList(), tableLines(rows, align),
				drawn ? new Table(align, cells) : null));
		}

		private void startText(BlockKind kind, int depth, int number, String first)
		{
			text = new StringBuilder(first);
			textKind = kind;
			textDepth = depth;
			textNumber = number;
		}

		private void endText()
		{
			if (text != null)
			{
				blocks.add(new Block(textKind, 0, textDepth, textNumber, inline(text.toString()), Collections.emptyList()));
				text = null;
			}
		}
	}

	/** "```" or "~~~" (or longer) opening a code block: {fence character, length}, or null. */
	private static int[] fence(String rest)
	{
		if (rest.length() < 3 || (rest.charAt(0) != '`' && rest.charAt(0) != '~'))
		{
			return null;
		}
		char c = rest.charAt(0);
		int n = 0;
		while (n < rest.length() && rest.charAt(n) == c)
		{
			n++;
		}
		// "```js```" on one line is inline code, not a fence.
		if (n < 3 || (c == '`' && rest.indexOf('`', n) >= 0))
		{
			return null;
		}
		return new int[]{c, n};
	}

	private static boolean closesFence(String rest, char c, int length)
	{
		int n = 0;
		while (n < rest.length() && rest.charAt(n) == c)
		{
			n++;
		}
		return n >= length && rest.substring(n).isBlank();
	}

	/** "---", "***", "___" or spaced out like "* * *". */
	private static boolean isRule(String rest)
	{
		char c = rest.charAt(0);
		if (c != '-' && c != '*' && c != '_')
		{
			return false;
		}
		int n = 0;
		for (int i = 0; i < rest.length(); i++)
		{
			char ch = rest.charAt(i);
			if (ch == c)
			{
				n++;
			}
			else if (ch != ' ' && ch != '\t')
			{
				return false;
			}
		}
		return n >= 3;
	}

	/** 1 to 6 for "# Title" to "###### Title", else 0 ("#hashtag" isn't a heading). */
	private static int headingLevel(String rest)
	{
		int n = 0;
		while (n < rest.length() && rest.charAt(n) == '#')
		{
			n++;
		}
		if (n == 0 || n > 6 || (n < rest.length() && rest.charAt(n) != ' ' && rest.charAt(n) != '\t'))
		{
			return 0;
		}
		return n;
	}

	private static String headingText(String rest, int level)
	{
		String s = rest.substring(level).trim();
		int end = s.length();
		while (end > 0 && s.charAt(end - 1) == '#')
		{
			end--;
		}
		// Closing hashes ("## Title ##") aren't part of the title, but the one in "C#" is.
		if (end == 0)
		{
			return "";
		}
		if (end < s.length() && (s.charAt(end - 1) == ' ' || s.charAt(end - 1) == '\t'))
		{
			return s.substring(0, end).trim();
		}
		return s;
	}

	/** The text after the ">" marks (all of them: a quote inside a quote is shown as one). */
	private static String quoteContent(String rest)
	{
		int i = 0;
		while (i < rest.length() && rest.charAt(i) == '>')
		{
			i++;
			while (i < rest.length() && (rest.charAt(i) == ' ' || rest.charAt(i) == '\t'))
			{
				i++;
			}
		}
		return rest.substring(i).trim();
	}

	/** The alignment of each column if the line is a table's delimiter row ("| --- | :-: | --: |"), else null. */
	private static int[] delimiterRow(String line)
	{
		if (line.indexOf('|') < 0)
		{
			// "---" alone is a rule.
			return null;
		}
		List<String> cells = splitCells(line.trim());
		int[] align = new int[cells.size()];
		for (int c = 0; c < cells.size(); c++)
		{
			String cell = cells.get(c);
			boolean left = cell.startsWith(":");
			boolean right = cell.endsWith(":");
			String dashes = cell.substring(left ? 1 : 0, Math.max(left ? 1 : 0, cell.length() - (right ? 1 : 0)));
			if (dashes.isEmpty() || !dashes.chars().allMatch(ch -> ch == '-'))
			{
				return null;
			}
			align[c] = right && left ? ALIGN_CENTER : right ? ALIGN_RIGHT : ALIGN_LEFT;
		}
		return align;
	}

	/** A table row's cells, the first {@code columns} of them: those past the header's are left out. */
	private static List<List<Span>> tableCells(String line, int columns)
	{
		List<List<Span>> cells = new ArrayList<>();
		for (String cell : splitCells(line.trim()))
		{
			if (cells.size() == columns)
			{
				break;
			}
			cells.add(inline(cell));
		}
		return cells;
	}

	/** Splits a row at its "|"s (not "\|"), without the optional ones at either end. Cells come back trimmed. */
	private static List<String> splitCells(String row)
	{
		int start = row.startsWith("|") ? 1 : 0;
		int end = row.length();
		if (end > start && row.charAt(end - 1) == '|' && (end < 2 || row.charAt(end - 2) != '\\'))
		{
			end--;
		}
		List<String> cells = new ArrayList<>();
		StringBuilder cell = new StringBuilder();
		for (int i = start; i < end; i++)
		{
			char c = row.charAt(i);
			if (c == '\\' && i + 1 < end && row.charAt(i + 1) == '|')
			{
				cell.append('|');
				i++;
			}
			else if (c == '|')
			{
				cells.add(cell.toString().trim());
				cell.setLength(0);
			}
			else
			{
				cell.append(c);
			}
		}
		cells.add(cell.toString().trim());
		return cells;
	}

	static List<Span> inline(String text)
	{
		return new InlineParser(text, true).parse();
	}

	/** A piece of a block's text on its way to becoming spans. */
	private static class Node
	{
		static final int TEXT = 0;
		static final int CODE = 1;
		static final int LINK = 2;
		/** A run of "*" or "_" that may open or close bold or italic. */
		static final int DELIMITER = 3;

		final int type;
		final String text;
		final String url;
		final int index;
		final char ch;
		/** The run's length as written, and how much of it is still unused (shown as text if it stays unused). */
		final int length;
		int count;
		final boolean canOpen;
		final boolean canClose;

		Node(int type, String text, String url, int index, char ch, int length, boolean canOpen, boolean canClose)
		{
			this.type = type;
			this.text = text;
			this.url = url;
			this.index = index;
			this.ch = ch;
			this.length = length;
			this.count = length;
			this.canOpen = canOpen;
			this.canClose = canClose;
		}
	}

	/**
	 * Inline Markdown, after CommonMark: code spans first, then links, then "*" and "_" paired up by the spec's
	 * delimiter rules (so "snake_case_name" isn't italic, and "**bold *both* bold**" nests). Linear time, even for
	 * odd input like thousands of "[" or "*": replies are re-read many times a second while they stream.
	 */
	private static class InlineParser
	{
		private final String s;
		private final int n;
		/** False for text that isn't Markdown: only bare addresses are picked out. */
		private final boolean markdown;
		private final List<Node> nodes = new ArrayList<>();
		private final List<Node> delimiters = new ArrayList<>();
		private final StringBuilder text = new StringBuilder();
		/** Lengths of backtick runs known to have no closing run further on. */
		private final BitSet noCloser = new BitSet();
		/** How far bare addresses have been read: nothing before here ends one. */
		private int addressScanned;

		InlineParser(String s, boolean markdown)
		{
			this.s = s;
			this.n = s.length();
			this.markdown = markdown;
		}

		List<Span> parse()
		{
			int i = 0;
			while (i < n)
			{
				char c = s.charAt(i);
				int next = -1;
				if ((c == 'h' || c == 'H') && (i == 0 || !Character.isLetterOrDigit(s.charAt(i - 1))))
				{
					next = bareUrl(i);
				}
				else if (!markdown)
				{
					next = -1;
				}
				else if (c == '\\' && i + 1 < n && ASCII_PUNCTUATION.indexOf(s.charAt(i + 1)) >= 0)
				{
					text.append(s.charAt(i + 1));
					next = i + 2;
				}
				else if (c == '\\' && i + 1 < n && s.charAt(i + 1) == '\n')
				{
					// A backslash at the end of a line is Markdown for a line break, which lines already are here.
					next = i + 1;
				}
				else if (c == '`')
				{
					next = codeSpan(i);
				}
				else if (c == '[')
				{
					next = link(i);
				}
				else if (c == '!' && i + 1 < n && s.charAt(i + 1) == '[')
				{
					// An image: never loaded, but its address is still a link.
					next = link(i + 1);
				}
				else if (c == '<')
				{
					next = autolink(i);
				}
				else if (c == '*' || c == '_')
				{
					next = delimiterRun(i);
				}
				if (next < 0)
				{
					text.append(c);
					next = i + 1;
				}
				i = next;
			}
			flushText();
			return spans(emphasis());
		}

		private void flushText()
		{
			if (text.length() > 0)
			{
				nodes.add(new Node(Node.TEXT, text.toString(), null, nodes.size(), '\0', 0, false, false));
				text.setLength(0);
			}
		}

		private void add(int type, String content, String url)
		{
			flushText();
			nodes.add(new Node(type, content, url, nodes.size(), '\0', 0, false, false));
		}

		private int runLength(int i, char c)
		{
			int j = i;
			while (j < n && s.charAt(j) == c)
			{
				j++;
			}
			return j - i;
		}

		/** "`code`" or "``code with ` in it``": the index after it, or -1 if the run of backticks isn't closed. */
		private int codeSpan(int i)
		{
			int run = runLength(i, '`');
			int close = noCloser.get(run) ? -1 : findCloser(i + run, run);
			if (close < 0)
			{
				noCloser.set(run);
				text.append(repeat('`', run));
				return i + run;
			}
			String code = s.substring(i + run, close).replace('\n', ' ');
			if (code.length() >= 2 && code.startsWith(" ") && code.endsWith(" ") && !code.isBlank())
			{
				code = code.substring(1, code.length() - 1);
			}
			add(Node.CODE, code, null);
			return close + run;
		}

		private int findCloser(int from, int run)
		{
			int j = from;
			while (j < n)
			{
				if (s.charAt(j) == '`')
				{
					int length = runLength(j, '`');
					if (length == run)
					{
						return j;
					}
					j += length;
				}
				else
				{
					j++;
				}
			}
			return -1;
		}

		/** "[text](address)" at {@code i}: the index after it, or -1. Link text can't contain "[" or a line break. */
		private int link(int i)
		{
			int j = i + 1;
			int limit = Math.min(n, i + 1 + MAX_LABEL);
			while (j < limit)
			{
				char c = s.charAt(j);
				if (c == '\\' && j + 1 < n && s.charAt(j + 1) != '\n')
				{
					j += 2;
					continue;
				}
				if (c == ']' || c == '[' || c == '\n')
				{
					break;
				}
				j++;
			}
			if (j + 1 >= n || s.charAt(j) != ']' || s.charAt(j + 1) != '(')
			{
				return -1;
			}
			String label = s.substring(i + 1, j);
			int k = skipSpaces(j + 2);
			String dest;
			if (k < n && s.charAt(k) == '<')
			{
				int close = k + 1;
				while (close < n && close - k <= MAX_URL && s.charAt(close) != '>' && s.charAt(close) != '<' && s.charAt(close) != '\n')
				{
					close++;
				}
				if (close >= n || s.charAt(close) != '>')
				{
					return -1;
				}
				dest = s.substring(k + 1, close);
				k = close + 1;
			}
			else
			{
				StringBuilder d = new StringBuilder();
				int depth = 0;
				int start = k;
				while (k < n && k - start <= MAX_URL)
				{
					char c = s.charAt(k);
					if (c == '\\' && k + 1 < n && ASCII_PUNCTUATION.indexOf(s.charAt(k + 1)) >= 0)
					{
						d.append(s.charAt(k + 1));
						k += 2;
						continue;
					}
					if (c <= ' ')
					{
						break;
					}
					// Balanced brackets belong to the address: wiki pages like "Rune_platebody_(g)". No real address nests
					// them deeply (CommonMark's reference parser stops at 32 too).
					if (c == '(' && ++depth > 32)
					{
						return -1;
					}
					else if (c == ')')
					{
						if (depth == 0)
						{
							break;
						}
						depth--;
					}
					d.append(c);
					k++;
				}
				dest = d.toString();
			}
			k = skipSpaces(k);
			if (k < n && (s.charAt(k) == '"' || s.charAt(k) == '\'' || (s.charAt(k) == '(' && k > 0 && s.charAt(k - 1) <= ' ')))
			{
				// A title ("[text](address "title")"), which isn't shown.
				char close = s.charAt(k) == '(' ? ')' : s.charAt(k);
				int end = s.indexOf(close, k + 1);
				if (end < 0 || end - k > MAX_URL)
				{
					return -1;
				}
				k = skipSpaces(end + 1);
			}
			if (k >= n || s.charAt(k) != ')')
			{
				return -1;
			}
			String shown = plainText(new InlineParser(label, true).parse(), false);
			String url = linkUrl(dest);
			if (url == null)
			{
				// Not a web address (javascript:, file:, a relative path): the text alone, as text.
				text.append(shown);
			}
			else
			{
				add(Node.LINK, shown.isBlank() ? url : shown, url);
			}
			return k + 1;
		}

		private int skipSpaces(int k)
		{
			while (k < n && (s.charAt(k) == ' ' || s.charAt(k) == '\t'))
			{
				k++;
			}
			return k;
		}

		/** "<https://...>": the index after it, or -1. Anything else in angle brackets (HTML) stays text. */
		private int autolink(int i)
		{
			for (int j = i + 1; j < n && j - i <= MAX_URL; j++)
			{
				char c = s.charAt(j);
				if (c == '>')
				{
					String url = linkUrl(s.substring(i + 1, j));
					if (url == null)
					{
						return -1;
					}
					add(Node.LINK, url, url);
					return j + 1;
				}
				if (c == '<' || c <= ' ')
				{
					return -1;
				}
			}
			return -1;
		}

		/** A bare "https://..." address: the index after it (trailing punctuation left out), or -1. */
		private int bareUrl(int i)
		{
			int host = startsWithIgnoreCase(s, i, "https://") ? i + 8 : startsWithIgnoreCase(s, i, "http://") ? i + 7 : -1;
			if (host < 0 || host >= n || !Character.isLetterOrDigit(s.charAt(host)))
			{
				return -1;
			}
			// Where the address stops: a space, a quote, angle brackets. What's before addressScanned has been looked
			// at already, so odd text like "http://a(http://a(..." isn't read again for every "http".
			int end = Math.max(host, addressScanned);
			while (end < n && end - i <= MAX_URL && !endsAddress(s.charAt(end)))
			{
				end++;
			}
			addressScanned = end;
			if (end - i > MAX_URL)
			{
				return -1;
			}
			int opens = 0;
			int closes = 0;
			int squareOpens = 0;
			int squareCloses = 0;
			for (int k = i; k < end; k++)
			{
				char c = s.charAt(k);
				opens += c == '(' ? 1 : 0;
				closes += c == ')' ? 1 : 0;
				squareOpens += c == '[' ? 1 : 0;
				squareCloses += c == ']' ? 1 : 0;
			}
			while (end > i)
			{
				char c = s.charAt(end - 1);
				if (URL_TRAILERS.indexOf(c) >= 0)
				{
					end--;
				}
				else if (c == ')' && closes > opens)
				{
					// "(see https://example.com)": the bracket closes the sentence's, not the address's.
					closes--;
					end--;
				}
				else if (c == ']' && squareCloses > squareOpens)
				{
					squareCloses--;
					end--;
				}
				else
				{
					break;
				}
			}
			String url = linkUrl(s.substring(i, end));
			if (url == null)
			{
				return -1;
			}
			add(Node.LINK, url, url);
			return end;
		}

		private static boolean endsAddress(char c)
		{
			return isWhitespace(c) || c == '<' || c == '>' || c == '"' || c == '`';
		}

		private int delimiterRun(int i)
		{
			char c = s.charAt(i);
			int length = runLength(i, c);
			char before = i == 0 ? ' ' : s.charAt(i - 1);
			char after = i + length >= n ? ' ' : s.charAt(i + length);
			boolean leftFlanking = !isWhitespace(after) && (!isPunctuation(after) || isWhitespace(before) || isPunctuation(before));
			boolean rightFlanking = !isWhitespace(before) && (!isPunctuation(before) || isWhitespace(after) || isPunctuation(after));
			boolean canOpen;
			boolean canClose;
			if (c == '*')
			{
				canOpen = leftFlanking;
				canClose = rightFlanking;
			}
			else
			{
				// "_" inside a word (snake_case, wiki_page_names) is just a character.
				canOpen = leftFlanking && (!rightFlanking || isPunctuation(before));
				canClose = rightFlanking && (!leftFlanking || isPunctuation(after));
			}
			flushText();
			Node run = new Node(Node.DELIMITER, null, null, nodes.size(), c, length, canOpen, canClose);
			nodes.add(run);
			delimiters.add(run);
			return i + length;
		}

		/**
		 * Pairs up the delimiter runs (CommonMark's "process emphasis", with its stack bottoms so it stays linear).
		 * Returns how bold and italic change at each node: +1 where a pair's content starts, -1 at its closer.
		 */
		private int[][] emphasis()
		{
			int[][] change = new int[2][nodes.size() + 1];
			int m = delimiters.size();
			int[] prev = new int[m];
			int[] next = new int[m];
			for (int k = 0; k < m; k++)
			{
				prev[k] = k - 1;
				next[k] = k + 1;
			}
			int[] bottom = new int[12];
			Arrays.fill(bottom, -1);
			int ci = 0;
			while (ci < m)
			{
				Node closer = delimiters.get(ci);
				if (!closer.canClose || closer.count == 0)
				{
					ci = next[ci];
					continue;
				}
				int key = (closer.ch == '*' ? 0 : 6) + (closer.canOpen ? 3 : 0) + closer.length % 3;
				int oi = prev[ci];
				while (oi > bottom[key] && !pairs(delimiters.get(oi), closer))
				{
					oi = prev[oi];
				}
				if (oi > bottom[key])
				{
					Node opener = delimiters.get(oi);
					int use = opener.count >= 2 && closer.count >= 2 ? 2 : 1;
					int[] style = change[use == 2 ? 0 : 1];
					style[opener.index + 1]++;
					style[closer.index]--;
					opener.count -= use;
					closer.count -= use;
					// Runs between the two can't pair with anything any more.
					next[oi] = ci;
					prev[ci] = oi;
					if (opener.count == 0)
					{
						unlink(oi, prev, next);
					}
					if (closer.count == 0)
					{
						int after = next[ci];
						unlink(ci, prev, next);
						ci = after;
					}
				}
				else
				{
					// Nothing below here can open for a closer like this one: later ones needn't look again.
					bottom[key] = prev[ci];
					int after = next[ci];
					if (!closer.canOpen)
					{
						unlink(ci, prev, next);
					}
					ci = after;
				}
			}
			return change;
		}

		private static boolean pairs(Node opener, Node closer)
		{
			if (opener.ch != closer.ch || !opener.canOpen || opener.count == 0)
			{
				return false;
			}
			// CommonMark's "rule of 3", which keeps "*a**b*" from pairing the wrong way.
			boolean both = opener.canClose || closer.canOpen;
			return !(both && (opener.length + closer.length) % 3 == 0 && !(opener.length % 3 == 0 && closer.length % 3 == 0));
		}

		private static void unlink(int k, int[] prev, int[] next)
		{
			if (prev[k] >= 0)
			{
				next[prev[k]] = next[k];
			}
			if (next[k] < next.length)
			{
				prev[next[k]] = prev[k];
			}
		}

		private List<Span> spans(int[][] change)
		{
			List<Span> out = new ArrayList<>();
			StringBuilder run = new StringBuilder();
			SpanKind runKind = SpanKind.TEXT;
			int bold = 0;
			int italic = 0;
			for (Node node : nodes)
			{
				bold += change[0][node.index];
				italic += change[1][node.index];
				if (node.type == Node.CODE || node.type == Node.LINK)
				{
					addSpan(out, runKind, run);
					out.add(new Span(node.type == Node.CODE ? SpanKind.CODE : SpanKind.LINK, node.text, node.url));
					continue;
				}
				// Unpaired "*" and "_" are shown as they are.
				String t = node.type == Node.DELIMITER ? repeat(node.ch, node.count) : node.text;
				if (t.isEmpty())
				{
					continue;
				}
				SpanKind kind = bold > 0 ? (italic > 0 ? SpanKind.BOLD_ITALIC : SpanKind.BOLD) : (italic > 0 ? SpanKind.ITALIC : SpanKind.TEXT);
				if (kind != runKind)
				{
					addSpan(out, runKind, run);
					runKind = kind;
				}
				run.append(t);
			}
			addSpan(out, runKind, run);
			return out;
		}

		private static void addSpan(List<Span> out, SpanKind kind, StringBuilder run)
		{
			if (run.length() > 0)
			{
				out.add(new Span(kind, run.toString(), null));
				run.setLength(0);
			}
		}
	}
}
