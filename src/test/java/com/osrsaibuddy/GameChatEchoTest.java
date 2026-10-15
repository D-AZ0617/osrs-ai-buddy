package com.osrsaibuddy;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Replies as game chat messages: split, marked, shortened, and without Markdown. */
public class GameChatEchoTest
{
	@Test
	public void repliesBecomeOneGameChatMessagePerParagraphOrListItem()
	{
		String h = "<colHIGHLIGHT>";
		String n = "<colNORMAL>";
		assertEquals(List.of(
				h + "Claude: " + n + "Right now, actually. Training Attack gets you two things at once:",
				h + "- " + n + "The abyssal whip at 70 Attack",
				h + "2. " + n + "Warriors' Guild access",
				h + "Claude: " + n + "You can do it on Slayer tasks."),
			GameChatEcho.echoMessages("Claude", null,
				"Right now, actually. Training Attack gets you two things at once:\r\n\n"
					+ "\u2022 The abyssal whip at 70 Attack\n2. Warriors\u2019 Guild access\n\n  You can do it on **Slayer** tasks.  ",
				500));
		// The model's text is escaped and never highlighted; a named chat shows its name once.
		List<String> named = GameChatEcho.echoMessages("Claude", "Bossing", "<col=ef1020>You have been banned.\n- next", 500);
		assertEquals(h + "Claude (Bossing): " + n + "<lt>col=ef1020<gt>You have been banned.", named.get(0));
		assertEquals(h + "- " + n + "next", named.get(1));
		// A list item first still says who it's from.
		assertEquals(h + "Claude: - " + n + "first", GameChatEcho.echoMessages("Claude", null, "* first", 500).get(0));
		for (String m : named)
		{
			assertFalse(m, m.contains("<br>"));
		}
		// Markdown that's only layout is left out; any kind of line break starts a new message.
		assertEquals(List.of(h + "Claude: " + n + "Top", h + "- " + n + "item", h + "Claude: " + n + "end"),
			GameChatEcho.echoMessages("Claude", null, "Top\n---\n```java\n|---|---|\n***\r- item\u2028end\n```", 500));
	}

	@Test
	public void tablesBecomeOneLinePerRow()
	{
		String h = "<colHIGHLIGHT>";
		String n = "<colNORMAL>";
		// Two columns read as "name: value"; the column names and the rule under them are left out.
		assertEquals(List.of(
				h + "Claude: " + n + "Also bring:",
				h + "- " + n + "Extended antifire: Dragonfire",
				h + "- " + n + "Anti-venom+: His venom",
				h + "Claude: " + n + "That's it."),
			GameChatEcho.echoMessages("Claude", null, "Also bring:\n\n| Item | Why |\n|---|:--:|\n"
				+ "| **Extended antifire** | Dragonfire |\n| Anti-venom+ | His venom |\n\nThat's it.", 500));
		// More columns are joined; an escaped pipe is part of its cell, and an empty cell is skipped.
		assertEquals(List.of(h + "Claude: - " + n + "Rune - 9k - a | b"),
			GameChatEcho.echoMessages("Claude", null, "| Bow | Price | Notes | |\n|---|---|---|---|\n| Rune | 9k | a \\| b | |",
				500));
		// Pipes outside a table stay as they are.
		assertEquals(List.of(h + "Claude: " + n + "| this or that |"),
			GameChatEcho.echoMessages("Claude", null, "| this or that |", 500));
	}

	@Test
	public void longRepliesAreCutWithANoteAboutThePanel()
	{
		String note = "<colHIGHLIGHT>OSRS AI Buddy: the full reply is in the side panel.";
		// Cut at a word once the length setting is used up.
		List<String> cut = GameChatEcho.echoMessages("Claude", null, "one two three four five six seven eight nine ten", 20);
		assertEquals(List.of("<colHIGHLIGHT>Claude: <colNORMAL>one two three four ...", note), cut);
		// A new paragraph isn't started as a stub when the setting is nearly used up.
		String first = "x".repeat(40) + " " + "y".repeat(40);
		assertEquals(List.of("<colHIGHLIGHT>Claude: <colNORMAL>" + first, note),
			GameChatEcho.echoMessages("Claude", null, first + "\nI think you should go to Ardougne next.", 90));
		// At most a few messages, however short.
		List<String> many = GameChatEcho.echoMessages("Claude", null, "a\nb\nc\nd\ne\nf\ng\nh\ni\nj", 500);
		assertEquals(GameChatEcho.MAX_MESSAGES + 1, many.size());
		assertEquals(note, many.get(many.size() - 1));
		// Words the game couldn't wrap are shortened; the panel has the whole thing.
		String link = "https://oldschool.runescape.wiki/w/Dragon_defender?some=long&query=string&more=1";
		String shown = GameChatEcho.echoMessages("Claude", null, "See " + link, 500).get(0);
		assertTrue(shown, shown.endsWith(link.substring(0, GameChatEcho.MAX_WORD - 3) + "..."));
		// Nothing to show still says something.
		assertEquals(List.of("<colHIGHLIGHT>OSRS AI Buddy (error): (empty reply)"), GameChatEcho.echoMessages("OSRS AI Buddy (error)", null, " \n ", 500));
	}

	@Test
	public void markdownIsLeftOutOfGameChat()
	{
		// Links keep their text: the game can't open them, and the panel has them.
		assertEquals("Get a Dragon defender first.",
			GameChatEcho.chatText("Get a [Dragon defender](https://oldschool.runescape.wiki/w/Dragon_defender) first."));
		assertEquals("Coins are stackable.", GameChatEcho.chatText("[Coins](https://oldschool.runescape.wiki/w/Coins_(item)) are stackable."));
		assertEquals("A picture of a whip", GameChatEcho.chatText("![A picture of a whip](https://example.com/whip.png)"));
		assertEquals("https://oldschool.runescape.wiki/w/Vorkath", GameChatEcho.chatText("[](https://oldschool.runescape.wiki/w/Vorkath)"));
		assertEquals("See https://oldschool.runescape.wiki/w/Vorkath for drops.",
			GameChatEcho.chatText("See <https://oldschool.runescape.wiki/w/Vorkath> for drops."));
		// Code, emphasis, quotes and headings lose their markers.
		assertEquals("Type ::buddy in the chatbox.", GameChatEcho.chatText("Type `::buddy` in the chatbox."));
		assertEquals("Bring an antifire potion, not a regular one.",
			GameChatEcho.chatText("Bring an *antifire* potion, _not_ a __regular__ one."));
		assertEquals("Really important.", GameChatEcho.chatText("***Really important.***"));
		assertEquals("Note: bank first.", GameChatEcho.chatText("**Note:** bank first."));
		assertEquals("Quoted advice.", GameChatEcho.chatText("> Quoted advice."));
		assertEquals("Nested.", GameChatEcho.chatText(">> Nested."));
		assertEquals("Also nested.", GameChatEcho.chatText("> > Also nested."));
		assertEquals("Heading", GameChatEcho.chatText("> ## Heading"));
		assertEquals("", GameChatEcho.chatText("```java"));
		assertEquals("", GameChatEcho.chatText("  ~~~"));
	}

	@Test
	public void whatOnlyLooksLikeMarkdownIsLeftAlone()
	{
		// Underscores inside names and Wiki addresses, and stars in sums.
		assertEquals("Use snake_case_names and 2 * 3 * 4.", GameChatEcho.chatText("Use snake_case_names and 2 * 3 * 4."));
		assertEquals("https://oldschool.runescape.wiki/w/Abyssal_whip", GameChatEcho.chatText("https://oldschool.runescape.wiki/w/Abyssal_whip"));
		assertEquals("5*3*2 and 1_000_000", GameChatEcho.chatText("5*3*2 and 1_000_000"));
		assertEquals("[not a link] (really)", GameChatEcho.chatText("[not a link] (really)"));
		assertEquals("50 > 40", GameChatEcho.chatText("50 > 40"));
		// Half a marker, as a reply cut short might leave: shown as it is.
		assertEquals("an *unfinished thought", GameChatEcho.chatText("an *unfinished thought"));
		// Escaped punctuation is the character itself, as in the panel; other backslashes stay.
		assertEquals("Escaped *not italic* or _either_, 1. no list", GameChatEcho.chatText("Escaped \\*not italic\\* or \\_either\\_, 1\\. no list"));
		assertEquals("# Not a heading, [not](a link), `tick`", GameChatEcho.chatText("\\# Not a heading, \\[not\\](a link), \\`tick\\`"));
		assertEquals("C:\\Users\\me and \\", GameChatEcho.chatText("C:\\Users\\me and \\\\"));
	}

	@Test
	public void quotedListsAndCodeBlocksStillReadWell()
	{
		String h = "<colHIGHLIGHT>";
		String n = "<colNORMAL>";
		assertEquals(List.of(h + "Claude: " + n + "Steps:", h + "- " + n + "Buy a rune pouch", h + "2. " + n + "Fill it",
				h + "Claude: " + n + "::buddy hello"),
			GameChatEcho.echoMessages("Claude", null, "Steps:\n> - Buy a *rune* pouch\n> 2. Fill it\n~~~\n::buddy hello\n~~~", 500));
		// A link as a list item: the marker stays, the address goes.
		assertEquals(List.of(h + "Claude: - " + n + "Vorkath"),
			GameChatEcho.echoMessages("Claude", null, "* [Vorkath](https://oldschool.runescape.wiki/w/Vorkath)", 500));
	}

	@Test
	public void cutsAtASpaceWhenThereIsOne()
	{
		assertEquals("one two", GameChatEcho.cutAtSpace("one two three", 9));
		assertEquals("abcde", GameChatEcho.cutAtSpace("abcdefghij", 5));
		// A space too early would leave a stub: cut mid-word instead.
		assertEquals("a bcdefg", GameChatEcho.cutAtSpace("a bcdefghij", 8));
	}
}

