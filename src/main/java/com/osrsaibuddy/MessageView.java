package com.osrsaibuddy;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.util.Collections;
import java.util.List;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JTextPane;
import javax.swing.JToolTip;
import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;
import javax.swing.border.EmptyBorder;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.BoxView;
import javax.swing.text.DefaultCaret;
import javax.swing.text.DefaultStyledDocument;
import javax.swing.text.Element;
import javax.swing.text.LabelView;
import javax.swing.text.ParagraphView;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledEditorKit;
import javax.swing.text.TabSet;
import javax.swing.text.TabStop;
import javax.swing.text.View;
import javax.swing.text.ViewFactory;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.util.LinkBrowser;
import okhttp3.HttpUrl;

/**
 * One message in the transcript, or a line about one: {@link Markdown} (or plain text) drawn with text attributes,
 * never as HTML. Read-only but selectable, see-through, and wrapped to whatever width its container gives it, with the
 * height to match (it works in {@link StackLayout} like a wrapped text area); it can also say how wide it would be
 * unwrapped, for a bubble that hugs a short message. Links show their address on hover and open in the browser on
 * click; right-click copies. Swing EDT only.
 *
 * <pre>
 * MessageView body = new MessageView();
 * body.setTextColor(Color.WHITE);
 * body.setMarkdown(reply);  // again with the longer text as it streams in: cheap, and skipped when unchanged
 * </pre>
 */
class MessageView extends JTextPane
{
	/** The character attribute holding a link's address. */
	static final Object LINK = new AttributeKey("link");
	/** The character attribute holding a table to draw, on the table's text: a {@link Markdown.Table}. */
	private static final Object TABLE = new AttributeKey("table");
	/** The paragraph attribute holding what to draw behind it: a {@link Decor}. */
	private static final Object DECOR = new AttributeKey("decor");

	/** The same font as the panel's other text. */
	private static final Font DEFAULT_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
	private static final Color LINK_COLOR = new Color(110, 180, 255);
	private static final Color QUOTE_COLOR = ColorScheme.LIGHT_GRAY_COLOR;
	/** Translucent, so they suit both the player's and the assistant's bubble colours. */
	private static final Color CODE_BACKGROUND = new Color(255, 255, 255, 20);
	private static final Color CODE_SPAN_BACKGROUND = new Color(255, 255, 255, 30);
	private static final Color LINE_COLOR = new Color(255, 255, 255, 70);
	/** Space above a block, in pixels: between paragraphs, list items, and around headings. */
	private static final int BLOCK_GAP = 6;
	private static final int LIST_GAP = 4;
	private static final int ITEM_GAP = 2;
	private static final int HEADING_GAP = 8;
	private static final int AFTER_HEADING_GAP = 3;
	/** Space inside a code block's box, around the text. */
	private static final int CODE_PAD_X = 5;
	private static final int CODE_PAD_Y = 3;
	private static final int QUOTE_BAR_WIDTH = 2;
	private static final int QUOTE_INDENT = 9;
	/** Lists nested deeper than this aren't indented further: the panel is narrow. */
	private static final int MAX_INDENT_DEPTH = 4;
	/** A list marker is followed by a tab to this stop, so an item's text lines up with its wrapped lines. */
	private static final TabSet MARKER_TAB = new TabSet(new TabStop[]{new TabStop(0)});

	private String source = "";
	private boolean markdown = true;
	private List<Markdown.Block> blocks = Collections.emptyList();
	private Color textColor = Color.WHITE;
	private Font textFont = DEFAULT_FONT;
	/** How lines sit across the width: one of StyleConstants' ALIGN_LEFT, ALIGN_CENTER and ALIGN_RIGHT. */
	private int alignment = StyleConstants.ALIGN_LEFT;
	/** {@link #naturalWidth}, worked out once per text; -1 until then. */
	private int naturalWidth = -1;
	private boolean overLink;
	/** The latest mouse press or release opened the menu. */
	private boolean menuOpened;

	MessageView()
	{
		setEditorKit(new Kit());
		setEditable(false);
		setOpaque(false);
		setBorder(new EmptyBorder(0, 0, 0, 0));
		setCaret(new StillCaret());
		// Read-only, so the caret is never drawn: no need to keep a pixel free for it at the right edge.
		putClientProperty("caretWidth", 0);
		// Never HTML, whatever the content type might become; tooltips get the same in createToolTip.
		putClientProperty("html.disable", Boolean.TRUE);
		ToolTipManager.sharedInstance().registerComponent(this);

		MouseAdapter mouse = new MouseAdapter()
		{
			@Override
			public void mouseMoved(MouseEvent e)
			{
				showHand(linkAt(e.getPoint()) != null);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				showHand(false);
			}

			@Override
			public void mouseClicked(MouseEvent e)
			{
				// Not when the click opened the menu (a Ctrl-click on a Mac is both).
				String url = !menuOpened && SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 1 ? linkAt(e.getPoint()) : null;
				if (url != null)
				{
					open(url);
				}
			}

			@Override
			public void mousePressed(MouseEvent e)
			{
				// The menu opens on press on some systems and on release on others.
				menuOpened = e.isPopupTrigger();
				if (menuOpened)
				{
					showMenu(e);
				}
			}

			@Override
			public void mouseReleased(MouseEvent e)
			{
				if (e.isPopupTrigger())
				{
					menuOpened = true;
					showMenu(e);
				}
			}
		};
		addMouseListener(mouse);
		addMouseMotionListener(mouse);
		render();
	}

	/** Shows {@code text} as Markdown. Cheap to call again with the same text (nothing happens). */
	void setMarkdown(String text)
	{
		String t = text == null ? "" : text;
		if (markdown && t.equals(source))
		{
			return;
		}
		source = t;
		markdown = true;
		blocks = Markdown.parse(t);
		render();
	}

	/** Shows {@code text} as it is (the player's own words, errors), with only bare web addresses made links. */
	void setPlainText(String text)
	{
		String t = text == null ? "" : text;
		if (!markdown && t.equals(source))
		{
			return;
		}
		source = t;
		markdown = false;
		blocks = t.isEmpty() ? Collections.emptyList() : Collections.singletonList(
			new Markdown.Block(Markdown.BlockKind.PARAGRAPH, 0, 0, 0, Markdown.linkify(t), Collections.emptyList()));
		render();
	}

	/** The colour of ordinary text (links, quotes and code keep their own). */
	void setTextColor(Color color)
	{
		if (color != null && !color.equals(textColor))
		{
			textColor = color;
			render();
		}
	}

	/** The font of ordinary text; headings and code are sized from it. */
	void setTextFont(Font f)
	{
		if (f != null && !f.equals(textFont))
		{
			textFont = f;
			render();
		}
	}

	/** How lines sit across the width: StyleConstants.ALIGN_LEFT (the default), ALIGN_CENTER or ALIGN_RIGHT. */
	void setAlignment(int alignment)
	{
		if (alignment != this.alignment)
		{
			this.alignment = alignment;
			render();
		}
	}

	/** The text last given to {@link #setMarkdown} or {@link #setPlainText}. */
	String getSource()
	{
		return source;
	}

	/** The whole message as plain text, as the menu's "Copy message" puts it on the clipboard. */
	String plainText()
	{
		return markdown ? Markdown.plainText(blocks) : source;
	}

	/**
	 * The selection as plain text, with a table in it as its lines: in the document, a table's lines are kept apart by
	 * {@link TableView#ROW_BREAK}, as a line break would split it into paragraphs. Ctrl+C copies this too.
	 */
	@Override
	public String getSelectedText()
	{
		String selected = super.getSelectedText();
		return selected == null ? null : selected.replace(TableView.ROW_BREAK, "\n");
	}

	/** Copies the selection, or the whole message if nothing is selected. */
	void copyToClipboard()
	{
		String selected = getSelectedText();
		toClipboard(selected != null && !selected.isEmpty() ? selected : plainText());
	}

	/** Copies the whole message as plain text, whatever is selected. */
	void copyAll()
	{
		toClipboard(plainText());
	}

	/**
	 * How wide the text is without wrapping, its longest line, in pixels: what a bubble needs to hug a short message.
	 * Measured by the same views that lay the text out, so a box this wide holds it without a wrap.
	 */
	int naturalWidth()
	{
		if (naturalWidth < 0)
		{
			// The views' unwrapped width, which their wrapping doesn't change. Asked of the views themselves, not the
			// look and feel: its preferred size can be wider than the text (FlatLaf, which RuneLite's is built on, makes
			// every text component at least 64 pixels wide), which would put "ok" in a bubble far too big for it. One
			// more pixel for what rounding loses.
			Insets in = getInsets();
			float text = getUI().getRootView(this).getPreferredSpan(View.X_AXIS);
			naturalWidth = (int) Math.ceil(text) + in.left + in.right + 1;
		}
		return naturalWidth;
	}

	@Override
	public Dimension getPreferredSize()
	{
		Dimension d = super.getPreferredSize();
		// Wrapped text has no width of its own: a long line mustn't ask for a wider panel.
		if (getWidth() > 0)
		{
			d.width = getWidth();
		}
		return d;
	}

	@Override
	public boolean getScrollableTracksViewportWidth()
	{
		return true;
	}

	/** A link's address over a link; anywhere else, the tooltip set on the view (the message's time, say), if any. */
	@Override
	public String getToolTipText(MouseEvent e)
	{
		String url = linkAt(e.getPoint());
		return url != null ? url : getToolTipText();
	}

	@Override
	public JToolTip createToolTip()
	{
		JToolTip tip = super.createToolTip();
		tip.putClientProperty("html.disable", Boolean.TRUE);
		return tip;
	}

	/**
	 * Builds the text into a new document and swaps it in: one layout instead of one per insert, and the old document
	 * keeps showing until the new one is complete. Each document has its own style context: a shared one would collect
	 * a listener from every document ever made.
	 */
	private void render()
	{
		DefaultStyledDocument doc = new DefaultStyledDocument();
		try
		{
			write(doc);
		}
		catch (BadLocationException e)
		{
			// Text only ever goes at the end of the document, which is always a valid place.
			throw new IllegalStateException(e);
		}
		int dot = getCaret().getDot();
		int mark = getCaret().getMark();
		naturalWidth = -1;
		setDocument(doc);
		// Keep a selection made while the reply streams in, as far as the new text allows.
		if (dot != mark && Math.max(dot, mark) <= doc.getLength())
		{
			getCaret().setDot(mark);
			getCaret().moveDot(dot);
		}
	}

	private void write(DefaultStyledDocument doc) throws BadLocationException
	{
		// Where the text of the latest list item at each depth starts: what's nested in it lines up there.
		int[] textStart = new int[MAX_INDENT_DEPTH + 1];
		for (int d = 0; d < textStart.length; d++)
		{
			textStart[d] = (d + 1) * 16;
		}
		Markdown.Block previous = null;
		AttributeSet tail = null;
		for (Markdown.Block b : blocks)
		{
			if (previous != null)
			{
				// Ends the previous block's last line, in its font, so that line keeps its own height.
				doc.insertString(doc.getLength(), "\n", tail);
			}
			tail = writeBlock(doc, b, gapBefore(previous, b), textStart);
			previous = b;
		}
	}

	/**
	 * Adds one block at the end of the document. Returns the attributes of its text, for the line break that ends it:
	 * a drawn table's own, so that the line break is part of the table's view, and not a sliver of text beside it (it
	 * would show a selection of its own there).
	 */
	private AttributeSet writeBlock(DefaultStyledDocument doc, Markdown.Block b, int gap, int[] textStart) throws BadLocationException
	{
		int start = doc.getLength();
		int depth = Math.min(b.depth, MAX_INDENT_DEPTH);
		int base = depth == 0 ? 0 : textStart[depth - 1];
		SimpleAttributeSet text = textAttributes();
		AttributeSet tail = text;
		// Paragraph attributes: for every line of the block, then extra ones for its first and last lines.
		SimpleAttributeSet lines = new SimpleAttributeSet();
		SimpleAttributeSet first = new SimpleAttributeSet();
		SimpleAttributeSet last = new SimpleAttributeSet();
		StyleConstants.setLeftIndent(lines, base);
		StyleConstants.setAlignment(lines, alignment);
		StyleConstants.setSpaceAbove(first, gap);
		switch (b.kind)
		{
			case HEADING:
				StyleConstants.setBold(text, true);
				StyleConstants.setFontSize(text, textFont.getSize() + Math.max(0, 4 - b.level));
				writeSpans(doc, b.spans, text);
				break;
			case BULLET:
			case NUMBERED:
				// The marker hangs out to the left, so wrapped lines line up with the item's text.
				String marker = b.kind == Markdown.BlockKind.BULLET ? "\u2022" : b.number + ".";
				int hang = markerWidth(marker, b.kind == Markdown.BlockKind.BULLET);
				StyleConstants.setLeftIndent(lines, base + hang);
				StyleConstants.setTabSet(lines, MARKER_TAB);
				StyleConstants.setFirstLineIndent(first, -hang);
				textStart[depth] = base + hang;
				doc.insertString(doc.getLength(), marker + "\t", text);
				writeSpans(doc, b.spans, text);
				break;
			case QUOTE:
				StyleConstants.setForeground(text, QUOTE_COLOR);
				StyleConstants.setLeftIndent(lines, base + QUOTE_INDENT);
				lines.addAttribute(DECOR, new Decor(Decor.BAR, LINE_COLOR, base, 0));
				first.addAttribute(DECOR, new Decor(Decor.BAR, LINE_COLOR, base, gap));
				writeSpans(doc, b.spans, text);
				break;
			case TABLE:
				if (b.table != null)
				{
					// Drawn by a TableView. Its lines are its text, so selecting and copying the message takes it.
					SimpleAttributeSet table = new SimpleAttributeSet(text);
					table.addAttribute(TABLE, b.table);
					doc.insertString(doc.getLength(), String.join(TableView.ROW_BREAK, b.lines), table);
					tail = table;
					break;
				}
				// Too big to draw: its lines, as code.
				writeLines(doc, b, base, gap, text, lines, first, last);
				break;
			case CODE:
				writeLines(doc, b, base, gap, text, lines, first, last);
				break;
			case RULE:
				// An empty line in a small font, with a line drawn through it.
				StyleConstants.setFontSize(text, Math.max(1, textFont.getSize() / 2));
				lines.addAttribute(DECOR, new Decor(Decor.RULE, LINE_COLOR, base, 0));
				first.addAttribute(DECOR, new Decor(Decor.RULE, LINE_COLOR, base, gap));
				break;
			default:
				writeSpans(doc, b.spans, text);
				break;
		}
		int end = doc.getLength();
		// The block's font goes on its paragraphs too: the document's own last line break takes it from there.
		lines.addAttributes(text);
		// Replaced, not merged: a new line starts with a copy of the previous block's paragraph attributes.
		doc.setParagraphAttributes(start, end - start + 1, lines, true);
		doc.setParagraphAttributes(start, 0, first, false);
		if (last.getAttributeCount() > 0)
		{
			doc.setParagraphAttributes(end, 0, last, false);
		}
		return tail;
	}

	/** A block's lines as they are, monospaced, in a box: code, or a table too big to draw. */
	private void writeLines(DefaultStyledDocument doc, Markdown.Block b, int base, int gap, SimpleAttributeSet text,
		SimpleAttributeSet lines, SimpleAttributeSet first, SimpleAttributeSet last) throws BadLocationException
	{
		StyleConstants.setFontFamily(text, Font.MONOSPACED);
		StyleConstants.setFontSize(text, Math.max(1, textFont.getSize() - 1));
		StyleConstants.setLeftIndent(lines, base + CODE_PAD_X);
		StyleConstants.setRightIndent(lines, CODE_PAD_X);
		lines.addAttribute(DECOR, new Decor(Decor.BOX, CODE_BACKGROUND, base, 0));
		first.addAttribute(DECOR, new Decor(Decor.BOX, CODE_BACKGROUND, base, gap));
		StyleConstants.setSpaceAbove(first, gap + CODE_PAD_Y);
		StyleConstants.setSpaceBelow(last, CODE_PAD_Y);
		doc.insertString(doc.getLength(), String.join("\n", b.lines), text);
	}

	/** How far a list item's text is from its marker's left edge: the same for every item up to 99 in a list. */
	private int markerWidth(String marker, boolean bullet)
	{
		FontMetrics fm = getFontMetrics(textFont);
		int space = fm.charWidth(' ');
		int width = bullet ? fm.stringWidth(marker) + space : Math.max(fm.stringWidth("00."), fm.stringWidth(marker));
		return width + space + 2;
	}

	private static void writeSpans(DefaultStyledDocument doc, List<Markdown.Span> spans, AttributeSet base) throws BadLocationException
	{
		for (Markdown.Span s : spans)
		{
			SimpleAttributeSet a = new SimpleAttributeSet(base);
			switch (s.kind)
			{
				case BOLD:
					StyleConstants.setBold(a, true);
					break;
				case ITALIC:
					StyleConstants.setItalic(a, true);
					break;
				case BOLD_ITALIC:
					StyleConstants.setBold(a, true);
					StyleConstants.setItalic(a, true);
					break;
				case CODE:
					StyleConstants.setFontFamily(a, Font.MONOSPACED);
					StyleConstants.setFontSize(a, Math.max(1, StyleConstants.getFontSize(base) - 1));
					StyleConstants.setBackground(a, CODE_SPAN_BACKGROUND);
					break;
				case LINK:
					StyleConstants.setForeground(a, LINK_COLOR);
					StyleConstants.setUnderline(a, true);
					a.addAttribute(LINK, s.url);
					break;
				default:
					break;
			}
			doc.insertString(doc.getLength(), s.text, a);
		}
	}

	/** Ordinary text. Every run gets all of these, so nothing depends on the document's defaults. */
	private SimpleAttributeSet textAttributes()
	{
		SimpleAttributeSet a = new SimpleAttributeSet();
		StyleConstants.setFontFamily(a, textFont.getFamily());
		StyleConstants.setFontSize(a, textFont.getSize());
		StyleConstants.setBold(a, textFont.isBold());
		StyleConstants.setItalic(a, textFont.isItalic());
		StyleConstants.setForeground(a, textColor);
		return a;
	}

	private static int gapBefore(Markdown.Block previous, Markdown.Block b)
	{
		if (previous == null)
		{
			return 0;
		}
		if (b.kind == Markdown.BlockKind.HEADING)
		{
			return HEADING_GAP;
		}
		if (previous.kind == Markdown.BlockKind.HEADING)
		{
			return AFTER_HEADING_GAP;
		}
		if (inList(previous) && inList(b))
		{
			return isItem(previous) && isItem(b) ? ITEM_GAP : LIST_GAP;
		}
		return BLOCK_GAP;
	}

	private static boolean isItem(Markdown.Block b)
	{
		return b.kind == Markdown.BlockKind.BULLET || b.kind == Markdown.BlockKind.NUMBERED;
	}

	private static boolean inList(Markdown.Block b)
	{
		return isItem(b) || b.depth > 0;
	}

	/** The address of the link under {@code p}, or null. */
	String linkAt(Point p)
	{
		int pos = viewToModel2D(p);
		int length = getDocument().getLength();
		// viewToModel gives the nearest gap between characters: look at the characters on both sides of it.
		for (int i = Math.max(0, pos - 1); i <= pos && i < length; i++)
		{
			Object url = getStyledDocument().getCharacterElement(i).getAttributes().getAttribute(LINK);
			if (url instanceof String && characterBounds(i).contains(p))
			{
				return (String) url;
			}
		}
		return null;
	}

	private Rectangle2D characterBounds(int i)
	{
		try
		{
			Rectangle2D from = modelToView2D(i);
			Rectangle2D to = modelToView2D(i + 1);
			if (from == null || to == null)
			{
				return new Rectangle2D.Double();
			}
			if (Math.abs(from.getY() - to.getY()) < 1 && to.getX() > from.getX())
			{
				return new Rectangle2D.Double(from.getX(), from.getY(), to.getX() - from.getX(), from.getHeight());
			}
			// The last character on a line: the next one is on the line below.
			Font f = getStyledDocument().getFont(getStyledDocument().getCharacterElement(i).getAttributes());
			int width = getFontMetrics(f).charWidth(getDocument().getText(i, 1).charAt(0));
			return new Rectangle2D.Double(from.getX(), from.getY(), width, from.getHeight());
		}
		catch (BadLocationException e)
		{
			return new Rectangle2D.Double();
		}
	}

	private void showHand(boolean hand)
	{
		if (hand != overLink)
		{
			overLink = hand;
			// Null goes back to the panel's own cursor.
			setCursor(hand ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : null);
		}
	}

	private void showMenu(MouseEvent e)
	{
		JPopupMenu menu = new JPopupMenu();
		String selected = getSelectedText();
		JMenuItem copy = new JMenuItem(selected != null && !selected.isEmpty() ? "Copy" : "Copy message");
		copy.addActionListener(a -> copyToClipboard());
		menu.add(copy);
		String url = linkAt(e.getPoint());
		if (url != null)
		{
			JMenuItem copyLink = new JMenuItem("Copy link");
			copyLink.addActionListener(a -> toClipboard(url));
			menu.add(copyLink);
		}
		menu.show(this, e.getX(), e.getY());
	}

	private static void toClipboard(String text)
	{
		try
		{
			Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
		}
		catch (IllegalStateException e)
		{
			// Another program has the clipboard open right now; copying again will work.
		}
	}

	private static void open(String url)
	{
		// Written the way browsers expect (spaces and accents encoded), or LinkBrowser refuses it.
		HttpUrl parsed = HttpUrl.parse(url);
		if (parsed == null)
		{
			return;
		}
		try
		{
			LinkBrowser.browse(parsed.toString());
		}
		catch (IllegalArgumentException e)
		{
			// An address Java can't read even so: there's nothing to open.
		}
	}

	private static class AttributeKey
	{
		private final String name;

		AttributeKey(String name)
		{
			this.name = name;
		}

		@Override
		public String toString()
		{
			return name;
		}
	}

	/** What's drawn behind a paragraph: a code block's box, a quote's bar or a rule. */
	private static class Decor
	{
		static final int BOX = 0;
		static final int BAR = 1;
		static final int RULE = 2;

		final int kind;
		final Color color;
		/** From the paragraph's left edge. */
		final int x;
		/** The space above the paragraph that's a gap between blocks, left clear. */
		final int top;

		Decor(int kind, Color color, int x, int top)
		{
			this.kind = kind;
			this.color = color;
			this.x = x;
			this.top = top;
		}

		void paint(Graphics g, Rectangle r)
		{
			int y = r.y + top;
			int height = r.height - top;
			g.setColor(color);
			switch (kind)
			{
				case BOX:
					g.fillRect(r.x + x, y, r.width - x, height);
					break;
				case BAR:
					g.fillRect(r.x + x, y, QUOTE_BAR_WIDTH, height);
					break;
				default:
					g.fillRect(r.x + x, y + height / 2, r.width - x, 1);
					break;
			}
		}
	}

	/** Paragraphs that draw their {@link Decor} first. */
	static class BlockView extends ParagraphView
	{
		private boolean firstRowPainted;

		BlockView(Element elem)
		{
			super(elem);
		}

		/** The width its lines are laid out in, or {@link Integer#MAX_VALUE} before they have been. */
		int flowWidth()
		{
			return layoutSpan;
		}

		@Override
		public void paint(Graphics g, Shape a)
		{
			Object decor = getAttributes().getAttribute(DECOR);
			if (decor instanceof Decor)
			{
				((Decor) decor).paint(g, a.getBounds());
			}
			firstRowPainted = false;
			super.paint(g, a);
		}

		@Override
		protected void paintChild(Graphics g, Rectangle alloc, int index)
		{
			// ParagraphView paints a first line that hangs out to the left (a list item's) twice, which makes
			// anti-aliased text look bold.
			if (index == 0)
			{
				if (firstRowPainted)
				{
					return;
				}
				firstRowPainted = true;
			}
			super.paintChild(g, alloc, index);
		}
	}

	/** Text that can break inside a word: a long address wraps instead of making the message wider than the panel. */
	private static class WrapLabelView extends LabelView
	{
		WrapLabelView(Element elem)
		{
			super(elem);
		}

		@Override
		public float getMinimumSpan(int axis)
		{
			return axis == View.X_AXIS ? 0 : super.getMinimumSpan(axis);
		}
	}

	private static class Kit extends StyledEditorKit
	{
		private static final ViewFactory VIEWS = elem ->
		{
			String name = elem.getName();
			if (AbstractDocument.ParagraphElementName.equals(name))
			{
				return new BlockView(elem);
			}
			if (AbstractDocument.SectionElementName.equals(name))
			{
				return new BoxView(elem, View.Y_AXIS);
			}
			// A table's text, which it draws as a table. Its own attribute only: a paragraph's don't count.
			if (elem.getAttributes().isDefined(TABLE))
			{
				return new TableView(elem, (Markdown.Table) elem.getAttributes().getAttribute(TABLE));
			}
			// Only text is ever inserted: no components or icons.
			return new WrapLabelView(elem);
		};

		@Override
		public ViewFactory getViewFactory()
		{
			return VIEWS;
		}
	}

	/** A caret that never scrolls the transcript (the text is replaced while a reply streams in, moving it). */
	private static class StillCaret extends DefaultCaret
	{
		@Override
		protected void adjustVisibility(Rectangle nloc)
		{
		}
	}
}
