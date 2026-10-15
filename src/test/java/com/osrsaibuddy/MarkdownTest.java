package com.osrsaibuddy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** What the Markdown reader makes of replies: blocks, spans, links, and text that's unfinished or odd. */
public class MarkdownTest
{
	/** Blocks in short form, one per line: kind, depth (and number or level), then the spans or lines. */
	private static String blocks(String markdown)
	{
		StringBuilder out = new StringBuilder();
		for (Markdown.Block b : Markdown.parse(markdown))
		{
			if (out.length() > 0)
			{
				out.append('\n');
			}
			switch (b.kind)
			{
				case PARAGRAPH:
					out.append("P").append(b.depth).append(spans(b.spans));
					break;
				case HEADING:
					out.append("H").append(b.level).append(spans(b.spans));
					break;
				case BULLET:
					out.append("B").append(b.depth).append(spans(b.spans));
					break;
				case NUMBERED:
					out.append("N").append(b.depth).append('#').append(b.number).append(spans(b.spans));
					break;
				case QUOTE:
					out.append("Q").append(b.depth).append(spans(b.spans));
					break;
				case CODE:
					out.append("C").append(b.depth).append('{').append(String.join("|", b.lines)).append('}');
					break;
				case TABLE:
					out.append("T").append(b.depth).append('{').append(String.join("|", b.lines)).append('}');
					break;
				default:
					out.append("R").append(b.depth);
					break;
			}
			assertTrue("only CODE and TABLE have lines", b.lines.isEmpty() || b.kind == Markdown.BlockKind.CODE || b.kind == Markdown.BlockKind.TABLE);
		}
		return out.toString();
	}

	/** Spans in short form: "[t:plain |b:bold|i:italic|bi:both|c:code|l:text->url]". */
	private static String spans(List<Markdown.Span> spans)
	{
		StringBuilder out = new StringBuilder("[");
		for (Markdown.Span s : spans)
		{
			if (out.length() > 1)
			{
				out.append('|');
			}
			switch (s.kind)
			{
				case BOLD:
					out.append("b:");
					break;
				case ITALIC:
					out.append("i:");
					break;
				case BOLD_ITALIC:
					out.append("bi:");
					break;
				case CODE:
					out.append("c:");
					break;
				case LINK:
					out.append("l:");
					break;
				default:
					out.append("t:");
					break;
			}
			out.append(s.text);
			if (s.kind == Markdown.SpanKind.LINK)
			{
				out.append("->").append(s.url);
			}
			else
			{
				assertNull("only links have an address", s.url);
			}
		}
		return out.append(']').toString();
	}

	private static String inline(String text)
	{
		return spans(Markdown.inline(text));
	}

	// Blocks

	@Test
	public void paragraphsAreSeparatedByBlankLines()
	{
		assertEquals("P0[t:One]\nP0[t:Two]", blocks("One\n\nTwo"));
		assertEquals("P0[t:One]\nP0[t:Two]", blocks("\n\n  One  \n \t \n\n\nTwo\n\n"));
		assertEquals("", blocks(""));
		assertEquals("", blocks(null));
		assertEquals("", blocks("\n \n\t\n"));
	}

	@Test
	public void singleLineBreaksStayLineBreaks()
	{
		// Replies are often written line by line; joining them into one line would garble them.
		assertEquals("P0[t:Requirements:\n70 Attack\n60 Defence]", blocks("Requirements:\n70 Attack  \n   60 Defence"));
		// Bold can run over a line break, as in CommonMark.
		assertEquals("P0[t:a |b:b\nc|t: d]", blocks("a **b\nc** d"));
		// A backslash at the end of a line is Markdown's line break; the line break is all that's left.
		assertEquals("P0[t:a\nb]", blocks("a\\\nb"));
	}

	@Test
	public void crlfAndCrAreLineBreaksToo()
	{
		String lf = "# Title\n\nSome **text**\nmore\n\n- one\n- two\n\n```\ncode\n```";
		assertEquals(blocks(lf), blocks(lf.replace("\n", "\r\n")));
		assertEquals(blocks(lf), blocks(lf.replace("\n", "\r")));
		assertEquals("C0{a|b}", blocks("```\r\na\r\nb\r\n```\r\n"));
	}

	@Test
	public void headings()
	{
		assertEquals("H1[t:One]\nH2[t:Two]\nH3[t:Three]\nH4[t:Four]\nH5[t:Five]\nH6[t:Six]",
			blocks("# One\n## Two\n### Three\n#### Four\n##### Five\n###### Six"));
		assertEquals("H2[t:Title]", blocks("## Title ##"));
		assertEquals("H2[t:Learning C#]", blocks("## Learning C#"));
		assertEquals("H1[]", blocks("#"));
		assertEquals("H3[t:Gear |b:upgrades]", blocks("   ### Gear **upgrades**"));
		assertEquals("P0[t:#hashtag]", blocks("#hashtag"));
		assertEquals("P0[t:####### Seven]", blocks("####### Seven"));
		// A heading interrupts a paragraph.
		assertEquals("P0[t:Intro]\nH2[t:Next]\nP0[t:Body]", blocks("Intro\n## Next\nBody"));
	}

	@Test
	public void bulletListsWithEveryMarker()
	{
		assertEquals("B0[t:one]\nB0[t:two]\nB0[t:three]", blocks("- one\n* two\n+ three"));
		assertEquals("B0[t:Abyssal whip: |b:1.5m]", blocks("-   Abyssal whip: **1.5m**"));
		// Not list items: no space after the marker.
		assertEquals("P0[t:-5 coins\n+5 attack]", blocks("-5 coins\n+5 attack"));
		// Bold at the start of a line isn't a bullet.
		assertEquals("P0[b:Note:|t: careful]", blocks("**Note:** careful"));
	}

	@Test
	public void nestedListsByIndentation()
	{
		assertEquals("B0[t:a]\nB1[t:b]\nB2[t:c]\nB1[t:d]\nB0[t:e]", blocks("- a\n  - b\n    - c\n  - d\n- e"));
		assertEquals("B0[t:a]\nB1[t:b]\nB0[t:c]", blocks("- a\n    - b\n- c"));
		assertEquals("N0#1[t:a]\nB1[t:b]\nN0#2[t:c]", blocks("1. a\n   - b\n2. c"));
		// Lenient: two spaces under "1. " is meant as nested, though CommonMark would start a new list.
		assertEquals("N0#1[t:a]\nB1[t:b]\nN0#2[t:c]", blocks("1. a\n  - b\n2. c"));
		// One space isn't enough to nest.
		assertEquals("B0[t:a]\nB0[t:b]", blocks("- a\n - b"));
		// Tabs count as four columns.
		assertEquals("B0[t:a]\nB1[t:b]", blocks("- a\n\t- b"));
	}

	@Test
	public void numberedListsCountUpFromTheirFirstNumber()
	{
		assertEquals("N0#1[t:a]\nN0#2[t:b]\nN0#3[t:c]", blocks("1. a\n2. b\n3. c"));
		assertEquals("N0#1[t:a]\nN0#2[t:b]\nN0#3[t:c]", blocks("1. a\n1. b\n1. c"));
		assertEquals("N0#3[t:a]\nN0#4[t:b]", blocks("3) a\n7) b"));
		assertEquals("N0#1[t:a]\nN1#1[t:x]\nN1#2[t:y]\nN0#2[t:b]", blocks("1. a\n   1. x\n   2. y\n2. b"));
		// A paragraph in between starts a new list, which keeps its own number.
		assertEquals("N0#1[t:a]\nP0[t:Then:]\nN0#2[t:b]", blocks("1. a\n\nThen:\n\n2. b"));
		// A bullet list between numbers is a new list.
		assertEquals("N0#1[t:a]\nB0[t:x]\nN0#5[t:b]", blocks("1. a\n- x\n5. b"));
		assertEquals("N0#123456789[t:big]", blocks("123456789. big"));
		assertEquals("P0[t:1234567890. too big]", blocks("1234567890. too big"));
		assertEquals("P0[t:1.5m coins]", blocks("1.5m coins"));
	}

	@Test
	public void listsInterruptParagraphsOnlyWhenClearlyLists()
	{
		assertEquals("P0[t:Steps:]\nN0#1[t:Bank]\nN0#2[t:Run]", blocks("Steps:\n1. Bank\n2. Run"));
		assertEquals("P0[t:You need:]\nB0[t:a rope]", blocks("You need:\n- a rope"));
		// "2023." mid-paragraph isn't a list, and neither is a bare "-".
		assertEquals("P0[t:Released in\n2023. It was big]", blocks("Released in\n2023. It was big"));
		assertEquals("P0[t:Total\n-]", blocks("Total\n-"));
		// After a blank line any number starts a list.
		assertEquals("P0[t:Released in]\nN0#2023[t:It was big]", blocks("Released in\n\n2023. It was big"));
	}

	@Test
	public void itemsContinueOnTheirNextLines()
	{
		assertEquals("B0[t:a long item\nthat goes on]\nB0[t:b]", blocks("- a long item\nthat goes on\n- b"));
		assertEquals("B0[t:a\ncontinued]", blocks("- a\n     continued"));
		// An empty item takes its text from the next line, without an empty first line.
		assertEquals("B0[t:text]", blocks("-\n  text"));
	}

	@Test
	public void paragraphsAndCodeInsideListItems()
	{
		assertEquals("N0#1[t:First]\nP1[t:More about first.]\nN0#2[t:Second]",
			blocks("1. First\n\n   More about first.\n\n2. Second"));
		assertEquals("B0[t:a]\nB1[t:b]\nP2[t:inner]\nP1[t:outer]",
			blocks("- a\n  - b\n\n    inner\n\n  outer"));
		// Not indented after a blank line: the list is over.
		assertEquals("B0[t:a]\nP0[t:After]\nB0[t:b]", blocks("- a\n\nAfter\n\n- b"));
		assertEquals("N0#1[t:Type:]\nC1{::buddy hello|  indented}\nN0#2[t:Wait]",
			blocks("1. Type:\n   ```\n   ::buddy hello\n     indented\n   ```\n2. Wait"));
		assertEquals("B0[t:Quote:]\nQ1[t:inside]", blocks("- Quote:\n  > inside"));
	}

	@Test
	public void quotes()
	{
		assertEquals("Q0[t:one\ntwo]", blocks("> one\n> two"));
		assertEquals("Q0[t:one]\nQ0[t:two]", blocks("> one\n>\n> two"));
		assertEquals("Q0[t:nested]", blocks("> > nested"));
		assertEquals("Q0[t:no space]", blocks(">no space"));
		assertEquals("Q0[t:Mod Ash: |i:soon|t:\u2122]", blocks("> Mod Ash: *soon*\u2122"));
		// A line without ">" ends the quote.
		assertEquals("Q0[t:quoted]\nP0[t:not quoted]", blocks("> quoted\nnot quoted"));
		assertEquals("P0[t:Before]\nQ0[t:quoted]", blocks("Before\n> quoted"));
		assertEquals("", blocks(">"));
		// List markers inside a quote are text.
		assertEquals("Q0[t:- a\n- b]", blocks("> - a\n> - b"));
	}

	@Test
	public void fencedCode()
	{
		assertEquals("P0[t:Run:]\nC0{**not bold**|  [not](https://a.link)}\nP0[t:Done]",
			blocks("Run:\n```java\n**not bold**\n  [not](https://a.link)\n```\nDone"));
		assertEquals("C0{a}", blocks("~~~\na\n~~~"));
		// Closed only by a fence at least as long, of the same kind.
		assertEquals("C0{```|~~~~|b}", blocks("````\n```\n~~~~\nb\n`````"));
		// Blank lines and indentation are kept; tabs become spaces.
		assertEquals("C0{a||    b|c     d}", blocks("```\na\n\n\tb\nc\t  d\n```"));
		assertEquals("C0{}", blocks("```\n```"));
		// "```js```" on one line is inline code.
		assertEquals("P0[c:js]", blocks("```js```"));
		assertEquals("P0[t:``]", blocks("``"));
	}

	@Test
	public void unclosedCodeRunsToTheEnd()
	{
		// While a reply streams in, its code block isn't closed yet.
		assertEquals("P0[t:Try:]\nC0{line one|line t}", blocks("Try:\n```\nline one\nline t"));
		assertEquals("C0{}", blocks("```"));
		assertEquals("C0{# not a heading|- not a list}", blocks("```\n# not a heading\n- not a list"));
	}

	@Test
	public void rules()
	{
		assertEquals("R0\nR0\nR0\nR0\nR0", blocks("---\n***\n___\n* * *\n - - -"));
		assertEquals("P0[t:Above]\nR0\nP0[t:Below]", blocks("Above\n---\nBelow"));
		assertEquals("P0[t:--]", blocks("--"));
		assertEquals("P0[t:--- x]", blocks("--- x"));
	}

	@Test
	public void tables()
	{
		assertEquals("T0{Item          | Price|--------------+------|Abyssal whip  |  1.5m|Dragon dagger |   17k}",
			blocks("| Item | Price |\n|---|---:|\n| Abyssal whip | 1.5m |\n| Dragon dagger | 17k |"));
		// Formatting is dropped, "\|" is a pipe, missing cells are empty, extra ones are left out.
		List<Markdown.Block> table = Markdown.parse("a | b\n:-: | --\n**x\\|y** |\n1 | 2 | 3");
		assertEquals(1, table.size());
		assertEquals(Arrays.asList(" a  | b", "----+--", "x|y |", " 1  | 2"), table.get(0).lines);
		// The table ends at a blank line or a line without a pipe.
		assertEquals("T0{a | b|--+--|1 | 2}\nP0[t:after]", blocks("|a|b|\n|-|-|\n|1|2|\nafter"));
		// Without a delimiter row (or while it's still streaming in) it's a paragraph.
		assertEquals("P0[t:| a | b |]", blocks("| a | b |"));
		assertEquals("P0[t:| a | b |\n|--]", blocks("| a | b |\n|--"));
		assertEquals("P0[t:a | b]\nR0", blocks("a | b\n---"));
	}

	@Test
	public void tablesKeepTheirCellsToBeDrawn()
	{
		Markdown.Block b = Markdown.parse("| Item | **Price** |\n|:--|--:|\n| `whip` | 1.5m | extra |\n| Pot |").get(0);
		assertNotNull(b.table);
		assertArrayEquals(new int[]{Markdown.ALIGN_LEFT, Markdown.ALIGN_RIGHT}, b.table.align);
		assertEquals(3, b.table.rows.size());
		// Formatting kept, for the drawing; a cell per column in every row, the extra one left out.
		assertEquals("[b:Price]", spans(b.table.rows.get(0).get(1)));
		assertEquals("[c:whip]", spans(b.table.rows.get(1).get(0)));
		assertEquals(2, b.table.rows.get(1).size());
		assertEquals("[t:Pot]", spans(b.table.rows.get(2).get(0)));
		assertTrue(b.table.rows.get(2).get(1).isEmpty());
		// The lines, as copied: formatting marks dropped.
		assertEquals(Arrays.asList("Item | Price", "-----+------", "whip |  1.5m", "Pot  |"), b.lines);
	}

	@Test
	public void aTableTooBigToDrawIsShownAsItsLines()
	{
		StringBuilder rows = new StringBuilder("| n | sq |\n|---|---|\n");
		for (int i = 0; i < Markdown.MAX_TABLE_ROWS - 1; i++)
		{
			rows.append("| ").append(i).append(" | ").append(i * i).append(" |\n");
		}
		assertNotNull("as many rows as can be drawn", Markdown.parse(rows.toString()).get(0).table);
		rows.append("| one | more |\n");
		Markdown.Block tooMany = Markdown.parse(rows.toString()).get(0);
		assertNull(tooMany.table);
		assertEquals(Markdown.MAX_TABLE_ROWS + 2, tooMany.lines.size());
		// Too many characters to line up: not drawn either.
		Markdown.Block wide = Markdown.parse("x|y\n-|-\n|" + "w".repeat(2000) + "\n|\n|\n|\n|\n|\n|\n|\n|\n|").get(0);
		assertNull(wide.table);
	}

	@Test
	public void tableLinesLineUp()
	{
		List<List<String>> rows = Arrays.asList(
			Arrays.asList("Skill", "Level", "Mid"),
			Arrays.asList("Attack", "99", "x"),
			Collections.singletonList("Hitpoints"));
		assertEquals(Arrays.asList(
			"Skill     | Level | Mid",
			"----------+-------+----",
			"Attack    |    99 |  x",
			"Hitpoints |       |"),
			Markdown.tableLines(rows, new int[]{Markdown.ALIGN_LEFT, Markdown.ALIGN_RIGHT, Markdown.ALIGN_CENTER}));
	}

	@Test
	public void aTableTooBigToLineUpIsShownAsWritten()
	{
		// One long cell over many rows: lined up, every row would be padded to it, about 4 million characters.
		StringBuilder reply = new StringBuilder("x|y\n-|-:\n|" + "w".repeat(2000));
		for (int i = 0; i < 2000; i++)
		{
			reply.append("\n|");
		}
		List<Markdown.Block> blocks = Markdown.parse(reply.toString());
		assertEquals(1, blocks.size());
		List<String> lines = blocks.get(0).lines;
		assertEquals(2003, lines.size());
		assertEquals(Arrays.asList("x | y", "-----", "w".repeat(2000), ""), lines.subList(0, 4));
		int size = 0;
		for (String line : lines)
		{
			size += line.length();
		}
		assertTrue(size + " characters from " + reply.length(), size <= reply.length());
		// Four lines (with the rule) of 5000 characters still line up; one more character doesn't.
		int[] align = {Markdown.ALIGN_LEFT, Markdown.ALIGN_LEFT};
		int wide = Markdown.MAX_TABLE / 4 - 4;
		List<List<String>> rows = Arrays.asList(Arrays.asList("a", "b"), Arrays.asList("c".repeat(wide), ""),
			Collections.singletonList("d"));
		assertEquals("d" + " ".repeat(wide - 1) + " |", Markdown.tableLines(rows, align).get(3));
		rows = Arrays.asList(Arrays.asList("a", "b"), Arrays.asList("c".repeat(wide + 1), ""), Collections.singletonList("d"));
		assertEquals(Arrays.asList("a | b", "-----", "c".repeat(wide + 1) + " |", "d"), Markdown.tableLines(rows, align));
	}

	// Spans

	@Test
	public void boldItalicAndBoth()
	{
		assertEquals("[t:a |b:bold|t: b]", inline("a **bold** b"));
		assertEquals("[t:a |b:bold|t: b]", inline("a __bold__ b"));
		assertEquals("[t:a |i:it|t: b]", inline("a *it* b"));
		assertEquals("[t:a |i:it|t: b]", inline("a _it_ b"));
		assertEquals("[bi:both]", inline("***both***"));
		assertEquals("[bi:both]", inline("___both___"));
		assertEquals("[b:x]", inline("**x**"));
	}

	@Test
	public void emphasisNests()
	{
		assertEquals("[b:bold |bi:both|b: bold]", inline("**bold *both* bold**"));
		assertEquals("[i:it |bi:both|i: it]", inline("*it **both** it*"));
		assertEquals("[bi:a|i: b]", inline("***a** b*"));
		assertEquals("[bi:a|b: b]", inline("***a* b**"));
		assertEquals("[b:bold |bi:both]", inline("**bold _both_**"));
	}

	@Test
	public void underscoresInsideWordsAreText()
	{
		assertEquals("[t:snake_case_name]", inline("snake_case_name"));
		assertEquals("[t:a_b_ and c_d]", inline("a_b_ and c_d"));
		assertEquals("[t:un|i:frigging|t:believable]", inline("un*frigging*believable"));
		assertEquals("[t:2 * 3 * 4]", inline("2 * 3 * 4"));
		assertEquals("[t:5*3=15]", inline("5*3=15"));
		assertEquals("[t:a ** b]", inline("a ** b"));
	}

	@Test
	public void backslashEscapes()
	{
		assertEquals("[t:*not italic*]", inline("\\*not italic\\*"));
		assertEquals("[t:a\\b]", inline("a\\\\b"));
		assertEquals("[t:[not a link]]", inline("\\[not a link\\]"));
		// The address in "\[a\](address)" is still an address.
		assertEquals("[t:[a](|l:https://a.b->https://a.b|t:)]", inline("\\[a\\](https://a.b)"));
		// Before anything else, a backslash is just a backslash.
		assertEquals("[t:C:\\Users]", inline("C:\\Users"));
		assertEquals("[t:`]", inline("\\`"));
	}

	@Test
	public void codeSpans()
	{
		assertEquals("[t:Type |c:::buddy hi|t: then]", inline("Type `::buddy hi` then"));
		assertEquals("[c:a ` b]", inline("``a ` b``"));
		assertEquals("[c:`]", inline("`` ` ``"));
		assertEquals("[c:**x** [y](https://z.com) <b>]", inline("`**x** [y](https://z.com) <b>`"));
		assertEquals("[b:bold |c:code|b: bold]", inline("**bold `code` bold**"));
		assertEquals("[c:over two]", inline("`over\ntwo`"));
		assertEquals("[c:  ]", inline("`  `"));
	}

	@Test
	public void unclosedMarkersStayText()
	{
		// What a reply looks like halfway through streaming.
		assertEquals("[t:**bold so far]", inline("**bold so far"));
		assertEquals("[t:*it]", inline("*it"));
		assertEquals("[t:__x]", inline("__x"));
		assertEquals("[t:run `::buddy]", inline("run `::buddy"));
		assertEquals("[t:**a |i:b]", inline("**a *b*"));
		assertEquals("[t:*|b:a]", inline("***a**"));
		assertEquals("[t:[Abyssal whip]", inline("[Abyssal whip"));
		assertEquals("[t:[Abyssal whip]]", inline("[Abyssal whip]"));
		assertEquals("[t:[Abyssal whip](]", inline("[Abyssal whip]("));
		assertEquals("[t:[Abyssal whip](|l:https://oldschool.runescape->https://oldschool.runescape]",
			inline("[Abyssal whip](https://oldschool.runescape"));
		assertEquals("[t:<|l:https://a.b->https://a.b]", inline("<https://a.b"));
		assertEquals("[t:`` x `]", inline("`` x `"));
	}

	@Test
	public void everyPrefixOfAReplyCanBeRead()
	{
		String reply = "## Getting a **fire cape**\n\nYou'll want:\n\n1. *High* Ranged \u2014 see [Fight Caves](https://oldschool"
			+ ".runescape.wiki/w/TzHaar_Fight_Cave).\n2. Prayer potions\n   - about `12`\n\n> Jad's attacks: magic and range.\n\n"
			+ "```\nprayer flick\n```\n\n| Item | Price |\n|---|--:|\n| Prayer potion(4) | 9k |\n\nGood luck: https://x.com/a_(b)!";
		for (int i = 0; i <= reply.length(); i++)
		{
			String prefix = reply.substring(0, i);
			List<Markdown.Block> parsed = Markdown.parse(prefix);
			assertNotNull(Markdown.plainText(parsed));
			// Nothing is lost: every letter written so far is in what's shown.
			String shown = Markdown.plainText(parsed);
			for (String word : prefix.split("[^A-Za-z]+"))
			{
				assertTrue("\"" + word + "\" missing at " + i, shown.contains(word));
			}
		}
		assertEquals("H2[t:Getting a |b:fire cape]\n"
			+ "P0[t:You'll want:]\n"
			+ "N0#1[i:High|t: Ranged \u2014 see |l:Fight Caves->https://oldschool.runescape.wiki/w/TzHaar_Fight_Cave|t:.]\n"
			+ "N0#2[t:Prayer potions]\n"
			+ "B1[t:about |c:12]\n"
			+ "Q0[t:Jad's attacks: magic and range.]\n"
			+ "C0{prayer flick}\n"
			+ "T0{Item             | Price|-----------------+------|Prayer potion(4) |    9k}\n"
			+ "P0[t:Good luck: |l:https://x.com/a_(b)->https://x.com/a_(b)|t:!]", blocks(reply));
	}

	@Test
	public void randomTextNeverBreaksTheReader()
	{
		// Characters Markdown cares about, mixed at random: whatever comes out, nothing may throw or hang.
		String alphabet = "*_`[]()<>!#>-+1.|\\:/ \n\thttps:/ab";
		Random random = new Random(42);
		for (int round = 0; round < 3000; round++)
		{
			StringBuilder s = new StringBuilder();
			int length = random.nextInt(120);
			for (int i = 0; i < length; i++)
			{
				s.append(alphabet.charAt(random.nextInt(alphabet.length())));
			}
			assertNotNull(Markdown.plainText(Markdown.parse(s.toString())));
			assertNotNull(Markdown.linkify(s.toString()));
		}
	}

	// Links

	@Test
	public void links()
	{
		assertEquals("[t:See |l:the Wiki->https://oldschool.runescape.wiki/w/Abyssal_whip|t:.]",
			inline("See [the Wiki](https://oldschool.runescape.wiki/w/Abyssal_whip)."));
		// Brackets in the address are part of it when they're balanced: wiki pages use them.
		assertEquals("[l:Rune platebody (g)->https://oldschool.runescape.wiki/w/Rune_platebody_(g)]",
			inline("[Rune platebody (g)](https://oldschool.runescape.wiki/w/Rune_platebody_(g))"));
		assertEquals("[l:a->https://x.com/a)b]", inline("[a](https://x.com/a\\)b)"));
		assertEquals("[l:a->https://x.com/a b]", inline("[a](<https://x.com/a b>)"));
		assertEquals("[l:a->https://x.com]", inline("[a](https://x.com \"Title\")"));
		assertEquals("[l:a->https://x.com]", inline("[a]( https://x.com 'Title' )"));
		assertEquals("[l:a->https://x.com]", inline("[a](https://x.com (Title))"));
		// No link text: the address is shown instead. Formatting in link text is dropped.
		assertEquals("[l:https://x.com->https://x.com]", inline("[](https://x.com)"));
		assertEquals("[l:bold link->https://x.com]", inline("[**bold** link](https://x.com)"));
		assertEquals("[b:see |l:here->https://x.com]", inline("**see [here](https://x.com)**"));
		assertEquals("[t:Look: |l:map->https://x.com/map.png]", inline("Look: ![map](https://x.com/map.png)"));
		assertEquals("[l:a->HTTPS://X.COM]", inline("[a](HTTPS://X.COM)"));
		// Text in square brackets that isn't a link stays as it is.
		assertEquals("[t:[sic] and [1] (note)]", inline("[sic] and [1] (note)"));
	}

	@Test
	public void bareAddressesBecomeLinks()
	{
		String wiki = "https://oldschool.runescape.wiki/w/Abyssal_whip";
		assertEquals("[t:See |l:" + wiki + "->" + wiki + "|t:.]", inline("See " + wiki + "."));
		for (String end : new String[]{".", ",", ";", ":", "!", "?", "...", "!?", "'", "\"", ").", "*", "_"})
		{
			assertEquals(end, "[l:" + wiki + "->" + wiki + "|t:" + end + "]", inline(wiki + end));
		}
		// A closing bracket is left out only when it doesn't belong to the address.
		assertEquals("[t:(see |l:" + wiki + "->" + wiki + "|t:)]", inline("(see " + wiki + ")"));
		assertEquals("[t:(see |l:https://x.com/a_(b)->https://x.com/a_(b)|t:).]", inline("(see https://x.com/a_(b))."));
		assertEquals("[t:[|l:https://x.com->https://x.com|t:]]", inline("[https://x.com]"));
		assertEquals("[l:https://x.com->https://x.com]", inline("**https://x.com**"));
		assertEquals("[l:http://x.com/?a=1&b=2#top->http://x.com/?a=1&b=2#top]", inline("http://x.com/?a=1&b=2#top"));
		assertEquals("[l:HTTPS://X.COM->HTTPS://X.COM]", inline("HTTPS://X.COM"));
		assertEquals("[l:https://x.com->https://x.com|t:<b>]", inline("https://x.com<b>"));
		assertEquals("[t:one |l:https://a.com->https://a.com|t:\n|l:https://b.com->https://b.com]", inline("one https://a.com\nhttps://b.com"));
		// Not addresses: nothing after "://", or part of a longer word.
		assertEquals("[t:https://]", inline("https://"));
		assertEquals("[t:https:// is how they start]", inline("https:// is how they start"));
		assertEquals("[t:xhttps://x.com]", inline("xhttps://x.com"));
	}

	@Test
	public void autolinks()
	{
		assertEquals("[t:Go to |l:https://x.com/a->https://x.com/a]", inline("Go to <https://x.com/a>"));
		assertEquals("[t:<b>bold</b>]", inline("<b>bold</b>"));
		assertEquals("[t:<ftp://x.com>]", inline("<ftp://x.com>"));
		// Not an autolink, but the address in it is still an address.
		assertEquals("[t:<|l:https://x->https://x|t: .com>]", inline("<https://x .com>"));
	}

	@Test
	public void onlyWebAddressesAreClickable()
	{
		assertEquals("[t:click]", inline("[click](javascript:alert(1))"));
		assertEquals("[t:open]", inline("[open](file:///C:/Users/me/.runelite)"));
		assertEquals("[t:page]", inline("[page](/w/Abyssal_whip)"));
		assertEquals("[t:mail]", inline("[mail](mailto:a@b.com)"));
		assertEquals("[t:<javascript:alert(1)>]", inline("<javascript:alert(1)>"));
		assertEquals("[t:ftp://x.com and mailto:a@b.com and www.x.com]", inline("ftp://x.com and mailto:a@b.com and www.x.com"));
		assertEquals("[t:a]", inline("[a](https://)"));
		for (Markdown.Span s : Markdown.inline("[a](https://x.com) https://y.com <https://z.com> [b](http://w.com)"))
		{
			if (s.kind == Markdown.SpanKind.LINK)
			{
				assertTrue(s.url, s.url.startsWith("https://") || s.url.startsWith("http://"));
			}
		}
	}

	@Test
	public void htmlIsShownAsItIs()
	{
		assertEquals("P0[t:<b>bold</b> and <i>it</i><br>]", blocks("<b>bold</b> and <i>it</i><br>"));
		assertEquals("P0[t:<script>alert(1)</script>]", blocks("<script>alert(1)</script>"));
		assertEquals("P0[t:<img src=x onerror=alert(1)>]", blocks("<img src=x onerror=alert(1)>"));
		assertEquals("P0[t:&amp; &lt;b&gt; &#169;]", blocks("&amp; &lt;b&gt; &#169;"));
		assertEquals("P0[t:<html><body>hi</body></html>]", blocks("<html><body>hi</body></html>"));
		assertEquals("P0[t:<details>\n<summary>x</summary>]", blocks("<details>\n<summary>x</summary>"));
	}

	@Test
	public void plainTextLinksOnly()
	{
		assertEquals("[t:**not bold** see |l:https://x.com->https://x.com|t:. `x` [y](z)]",
			spans(Markdown.linkify("**not bold** see https://x.com. `x` [y](z)")));
		assertEquals("[]", spans(Markdown.linkify("")));
		assertEquals("[]", spans(Markdown.linkify(null)));
	}

	@Test
	public void plainTextForCopying()
	{
		assertEquals("Title\n\n"
				+ "Some bold, and the Wiki (https://oldschool.runescape.wiki), https://x.com\n\n"
				+ "- one\n"
				+ "  - nested\n"
				+ "    more\n"
				+ "1. first\n"
				+ "2. second\n\n"
				+ "> quoted\n> lines\n\n"
				+ "code\n  indented\n\n"
				+ "----\n\n"
				+ "a | b\n--+--\n1 | 2",
			Markdown.plainText("# Title\n\nSome **bold**, and [the Wiki](https://oldschool.runescape.wiki), https://x.com\n\n"
				+ "- one\n  - nested\n    more\n1. first\n1. second\n\n> quoted\n> lines\n\n```\ncode\n  indented\n```\n\n---\n\n"
				+ "|a|b|\n|-|-|\n|1|2|"));
		assertEquals("", Markdown.plainText(""));
	}

	@Test
	public void longInputIsReadQuickly()
	{
		StringBuilder reply = new StringBuilder();
		while (reply.length() < 300_000)
		{
			reply.append("## Section\n\nSome **bold** and *italic* text with `code`, a [link](https://oldschool.runescape.wiki/w/Abyssal_whip) "
				+ "and https://x.com/a_(b).\n\n- item one\n  - nested _two_\n1. numbered\n\n> quoted line\n\n```\ncode line\n```\n\n"
				+ "| a | b |\n|---|---|\n| 1 | 2 |\n\n");
		}
		List<String> inputs = new ArrayList<>();
		inputs.add(reply.toString());
		// Odd text that would be slow to read with a careless parser (each case is about 200,000 characters).
		inputs.add(Markdown.repeat('[', 200_000));
		inputs.add(Markdown.repeat('*', 200_000));
		inputs.add(Markdown.repeat('`', 200_000));
		inputs.add(Markdown.repeat('<', 200_000));
		inputs.add(Markdown.repeat('>', 200_000));
		inputs.add(Markdown.repeat('#', 200_000));
		inputs.add(Markdown.repeat('|', 200_000));
		inputs.add(repeat("*a ", 70_000));
		inputs.add(repeat("a* ", 70_000));
		inputs.add(repeat("_a*", 70_000));
		inputs.add(repeat("*a _b ", 35_000));
		inputs.add(repeat("**a ", 50_000) + repeat(" a**", 50_000));
		inputs.add(repeat("``a`", 50_000));
		inputs.add(repeat("`a``", 50_000));
		inputs.add(repeat("[a](", 50_000));
		inputs.add(repeat("[a]", 70_000));
		inputs.add(repeat("](", 100_000));
		inputs.add(repeat("http://:", 25_000));
		inputs.add(repeat("https://x.com/(", 15_000));
		inputs.add(repeat("<https://x", 20_000));
		inputs.add(repeat("a\n", 100_000));
		inputs.add(repeat("> a\n", 50_000));
		inputs.add(repeat("|a|\n", 50_000));
		StringBuilder nested = new StringBuilder();
		for (int i = 0; i < 1000; i++)
		{
			nested.append(Markdown.repeat(' ', 2 * i)).append("- item\n");
		}
		inputs.add(nested.toString());

		long started = System.nanoTime();
		for (String input : inputs)
		{
			long one = System.nanoTime();
			List<Markdown.Block> parsed = Markdown.parse(input);
			assertNotNull(Markdown.plainText(parsed));
			long ms = (System.nanoTime() - one) / 1_000_000;
			assertTrue("took " + ms + "ms: " + input.substring(0, 20), ms < 3000);
		}
		long total = (System.nanoTime() - started) / 1_000_000;
		assertTrue("took " + total + "ms in all", total < 10_000);
	}

	private static String repeat(String s, int times)
	{
		StringBuilder out = new StringBuilder(s.length() * times);
		for (int i = 0; i < times; i++)
		{
			out.append(s);
		}
		return out.toString();
	}
}

