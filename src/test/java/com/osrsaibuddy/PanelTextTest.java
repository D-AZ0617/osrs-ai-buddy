package com.osrsaibuddy;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.function.Predicate;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The panel's words for a reply on its way, a message's tooltip, notes, and the line about look-ups and sharing. */
public class PanelTextTest
{
	private static final long NOW = 1_000_000;

	private static Chat running()
	{
		Chat chat = new Chat("x");
		chat.pending = new ChatApi.Pending();
		chat.runStartedAt = NOW - 12_400;
		return chat;
	}

	@Test
	public void theLineUnderTheReplySaysWhatItsDoingUntilItWrites()
	{
		Chat chat = running();
		assertEquals("Thinking… 12s", PanelText.live(chat, NOW));

		chat.liveText = "Vorkath is";
		assertNull("the words say it all", PanelText.live(chat, NOW));

		// What it looked up is listed above the reply, as each look-up comes back: this line doesn't say it again.
		chat.lookingUp = true;
		chat.liveActivity.add("Read the Wiki page \"Vorkath\"");
		assertEquals("Looking things up… 12s", PanelText.live(chat, NOW));

		chat.retryWhy = "ChatGPT is busy";
		chat.retryAt = NOW + 5_200;
		assertEquals("ChatGPT is busy; trying again in 6s", PanelText.live(chat, NOW));
		// Once the wait is over, the request is on its way again.
		assertEquals("Looking things up… 18s", PanelText.live(chat, NOW + 6_000));
	}

	@Test
	public void thinkingCountsDotsThenSeconds()
	{
		assertEquals("Thinking.", PanelText.thinking(0));
		assertEquals("Thinking..", PanelText.thinking(1_200));
		assertEquals("Thinking...", PanelText.thinking(2_999));
		assertEquals("Thinking.", PanelText.thinking(3_000));
		// A model that's slow to start: the seconds show it's still waiting.
		assertEquals("Thinking… 5s", PanelText.thinking(5_000));
		assertEquals("Thinking… 1m 2s", PanelText.thinking(62_000));
	}

	@Test
	public void summarisingHasItsOwnLine()
	{
		Chat chat = running();
		chat.skipSummary = () ->
		{
		};
		assertEquals("Summarising earlier messages… 12s", PanelText.live(chat, NOW));
		chat.runStartedAt = NOW - 75_000;
		assertEquals("Summarising earlier messages… 1m 15s", PanelText.live(chat, NOW));
	}

	@Test
	public void aMessagesTooltipSaysWhenAndWho()
	{
		long time = 1_700_000_000_000L;
		String hhmm = new SimpleDateFormat("HH:mm").format(new Date(time));
		Chat.Message question = new Chat.Message(Chat.Role.USER, "q", time);
		assertEquals(hhmm, PanelText.tooltip(question));
		question.unanswered = true;
		assertEquals(hhmm + " · not answered", PanelText.tooltip(question));

		Chat.Message reply = new Chat.Message(Chat.Role.ASSISTANT, "a", time);
		assertEquals("Assistant · " + hhmm, PanelText.tooltip(reply));
		reply.who = "Claude";
		reply.unfinished = true;
		reply.summarized = true;
		assertEquals("Claude · " + hhmm + " · didn't finish · summarised: the summary further down is sent "
			+ "instead", PanelText.tooltip(reply));
		assertEquals(hhmm, PanelText.tooltip(new Chat.Message(Chat.Role.NOTE, "Stopped.", time)));
	}

	@Test
	public void aSummarysNoteIsOneLineWithTheSummaryAClickAway()
	{
		Chat chat = new Chat("x");
		List<Chat.Message> old = new ArrayList<>();
		for (int i = 0; i < 24; i++)
		{
			Chat.Message m = new Chat.Message(i % 2 == 0 ? Chat.Role.USER : Chat.Role.ASSISTANT, "m" + i);
			chat.messages.add(m);
			old.add(m);
		}
		Chat.Message note = ConversationBuilder.applySummary(chat, old, "The player is training Agility.", null);
		PanelText.Note shown = PanelText.note(note.text);
		assertEquals("Summary of 24 earlier messages", shown.line);
		assertEquals("The player is training Agility.", shown.details);

		Chat one = new Chat("y");
		Chat.Message only = new Chat.Message(Chat.Role.USER, "q");
		one.messages.add(only);
		PanelText.Note single = PanelText.note(ConversationBuilder.applySummary(one, Collections.singletonList(only),
			"One question.\n\nOn two lines.", null).text);
		assertEquals("Summary of 1 earlier message", single.line);
		assertEquals("One question.\n\nOn two lines.", single.details);

		// Any other note is shown as it is.
		PanelText.Note stopped = PanelText.note("Stopped.");
		assertEquals("Stopped.", stopped.line);
		assertNull(stopped.details);
		assertEquals("Summary of the plan: none", PanelText.note("Summary of the plan: none").line);
	}

	@Test
	public void timesAreShort()
	{
		assertEquals("0s", PanelText.elapsed(-5));
		assertEquals("59s", PanelText.elapsed(59_999));
		assertEquals("1m 0s", PanelText.elapsed(60_000));
	}

	private static PanelText.Summary sum(String... lines)
	{
		return PanelText.summary(Arrays.asList(lines));
	}

	/** Any line fits. */
	private static final Predicate<String> ROOMY = s -> true;

	/** A line fits when it has at most {@code chars} characters (the panel measures pixels instead). */
	private static Predicate<String> chars(int chars)
	{
		return s -> s.length() <= chars;
	}

	@Test
	public void whatWasSharedComesFirstThenWhatWasLookedUp()
	{
		PanelText.Summary s = sum(
			"Searched the Wiki for \"vorkath gear\"",
			"Read the Wiki page \"Vorkath\" (Equipment)",
			"Shared your equipment",
			"Checked the GE price of Dragon bones",
			"Shared your inventory");
		assertEquals("Shared your equipment and inventory · Looked up Vorkath, Dragon bones (GE price)", s.line(ROOMY));
		// Too long for the row: the look-ups are counted instead, and what was shared is still named in full.
		assertEquals("Shared your equipment and inventory · Looked up 2 things", s.line(chars(60)));
		// Shorter still.
		assertEquals("Shared your equipment and inventory · 2 look-ups", s.line(chars(50)));
		// Still too long: the count goes, never what was shared.
		assertEquals("Shared your equipment and inventory · …", s.line(chars(47)));
		// Not even that fits: what was shared is shown whole all the same, over two rows, the count on its own.
		assertEquals("Shared your equipment and inventory\n2 look-ups", s.line(chars(30)));
		assertEquals("Shared your equipment and inventory", sum("Shared your equipment", "Shared your inventory")
			.line(chars(10)));
	}

	@Test
	public void oneLookUpAfterWhatWasSharedIsCountedRatherThanCut()
	{
		PanelText.Summary s = sum("Shared your equipment", "Shared your inventory",
			"Read the Wiki page \"Vorkath/Strategies\" (Equipment)");
		assertEquals("Shared your equipment and inventory · Looked up Vorkath/Strategies", s.line(ROOMY));
		assertEquals("Shared your equipment and inventory · Looked up 1 thing", s.line(chars(60)));
		assertEquals("Shared your equipment and inventory · 1 look-up", s.line(chars(50)));
		// Then as with more look-ups: the count goes, after what was shared.
		assertEquals("Shared your equipment and inventory · …", s.line(chars(45)));
		// The name itself is never cut down to "Looked up…" after what was shared.
		PanelText.Summary bank = sum("Searched your bank for \"clue\"",
			"Read the Wiki page \"Treasure Trails/Full guide/Elite\"");
		assertEquals("Searched your bank · Looked up 1 thing", bank.line(chars(45)));
		assertEquals("Searched your bank · 1 look-up", bank.line(chars(32)));
	}

	@Test
	public void whatWasSharedStaysOnOneRowWhenItFitsThere()
	{
		PanelText.Summary s = sum("Shared your equipment", "Shared your inventory", "Shared your bank",
			"Read the Wiki page \"Vorkath\"", "Read the Wiki page \"Zulrah\"", "Checked the GE price of Dragon bones");
		assertEquals("Shared your equipment, inventory and bank · 3 look-ups", s.line(chars(55)));
		assertEquals("Shared your equipment, inventory and bank · …", s.line(chars(45)));
		// Not even " · …" fits after it: the ellipsis alone, then nothing (the chevron says there's more).
		assertEquals("Shared your equipment, inventory and bank…", s.line(chars(44)));
		assertEquals("Shared your equipment, inventory and bank", s.line(chars(41)));
		// Only when what was shared is longer than a row does the line take two: the count on its own, with no
		// separator left at the end of a row.
		assertEquals("Shared your equipment, inventory and bank\n3 look-ups", s.line(chars(40)));
	}

	@Test
	public void lookUpsAloneAreNamedOrCounted()
	{
		assertEquals("Looked up Dragon bones (GE price)", sum("Checked the GE price of Dragon bones").line(ROOMY));
		PanelText.Summary s = sum(
			"Searched the Wiki for \"whip\"",
			"Read the Wiki page \"Abyssal whip\"",
			"Read the Wiki page \"Abyssal demon\" (Drops)",
			"Checked the GE price of Abyssal whip",
			"Checked the GE price of Big bones");
		assertEquals("Looked up Abyssal whip, Abyssal demon, Abyssal whip, Big bones (GE prices)", s.line(ROOMY));
		assertEquals("Looked up 4 things", s.line(chars(30)));
		assertEquals("4 look-ups", s.line(chars(15)));
		// The searches only found the pages, so they're left off the line; the details still have them.
		assertEquals("Wiki pages: Abyssal whip, Abyssal demon\nWiki searches: \"whip\"\n"
			+ "GE prices: Abyssal whip, Big bones", s.details);
		// With no page read, what was searched for is what was looked up.
		PanelText.Summary searched = sum("Searched the Wiki for \"abyssal whip drop rate\"",
			"Searched the Wiki for \"whip\"");
		assertEquals("Looked up \"abyssal whip drop rate\", \"whip\"", searched.line(ROOMY));
		assertEquals("Looked up 2 things", searched.line(chars(30)));
		// One long name: cut at the end, between words.
		assertEquals("Looked up Abyssal whip (or)…", sum("Checked the GE price of Abyssal whip (or) ornament kit")
			.line(chars(30)));
		// A title with quotes or brackets of its own, with and without a section.
		assertEquals("Looked up \"Ernest\" the Chicken, Dragon (disambiguation)", sum(
			"Read the Wiki page \"\"Ernest\" the Chicken\" (introduction)",
			"Read the Wiki page \"Dragon (disambiguation)\"").line(ROOMY));
	}

	@Test
	public void eachThingIsNamedOnceAndTheDetailsHaveEveryLine()
	{
		PanelText.Summary s = sum(
			"Shared your equipment",
			"Read the Wiki page \"Vorkath\"",
			"Read the Wiki page \"Vorkath\" (Drops)",
			"Searched your bank for \"rune\"",
			"Couldn't search the Wiki for \"vorkath\"",
			"Searched the Wiki for \"vorkath\"",
			"Searched the Wiki for \"vorkath\"",
			"Checked the GE price of Rune platebody",
			"Checked the GE price of Rune platebody",
			"Didn't share your inventory: \"Share items and gear\" is off",
			"Read your bank, but didn't share it: the request had stopped");
		assertEquals("Shared your equipment and searched your bank · Looked up Vorkath, Rune platebody (GE price)",
			s.line(ROOMY));
		// Each line as it was recorded, in order; then what was looked up, one kind to a line.
		assertEquals("Shared your equipment\n"
			+ "Searched your bank for \"rune\"\n"
			+ "Couldn't search the Wiki for \"vorkath\"\n"
			+ "Didn't share your inventory: \"Share items and gear\" is off\n"
			+ "Read your bank, but didn't share it: the request had stopped\n"
			+ "Wiki pages: Vorkath\n"
			+ "Wiki searches: \"vorkath\"\n"
			+ "GE prices: Rune platebody", s.details);
	}

	@Test
	public void onlyWhatWasSharedIsNamedAsShared()
	{
		assertEquals("Shared your equipment, inventory and bank", sum("Shared your equipment", "Shared your inventory",
			"Shared your bank", "Searched your bank for \"rune\"").line(ROOMY));
		assertEquals("Shared your Slayer task and achievement diaries, and searched your bank", sum(
			"Shared your Slayer task", "Searched your bank for \"rune\"", "Shared your achievement diaries",
			"Shared your Slayer task").line(ROOMY));
		assertEquals("Searched your bank", sum("Searched your bank for \"rune\"").line(ROOMY));
		// Read, but never sent, as the request had stopped; or a setting is off: nothing was shared.
		PanelText.Summary stopped = sum(GameDataTools.unshared("Shared your bank"),
			GameDataTools.unshared("Searched your bank for \"rune\""),
			"Didn't share your inventory: \"Share items and gear\" is off");
		assertNull(stopped.sharing);
		// Said shorter on the line; the details have it in full.
		assertEquals("Didn't share your bank (stopped)", stopped.line(ROOMY));
		assertTrue(stopped.details.startsWith("Read your bank, but didn't share it: the request had stopped\n"));
		assertEquals("Didn't share your bank search (stopped)",
			sum(GameDataTools.unshared("Searched your bank for \"rune\"")).line(ROOMY));
		assertEquals("Didn't share your equipment (stopped)",
			sum(GameDataTools.unshared("Shared your equipment")).line(ROOMY));
	}

	@Test
	public void withNothingSharedOrLookedUpTheFirstLineSaysIt()
	{
		assertEquals("No look-ups: this model can't use tools", sum(RequestRunner.NO_LOOKUPS).line(ROOMY));
		assertEquals(RequestRunner.NO_LOOKUPS, sum(RequestRunner.NO_LOOKUPS).details);
		PanelText.Summary s = sum("Skipped a Wiki search: Wiki look-ups are off", "Couldn't read the game (get_bank)");
		assertEquals("Skipped a Wiki search: Wiki look-ups are off", s.line(ROOMY));
		// Cut short between words, with no colon left before the ellipsis.
		assertEquals("Skipped a Wiki search…", s.line(chars(25)));
		assertEquals("Skipped a Wiki…", s.line(chars(20)));
		assertEquals("Skipped a Wiki search: Wiki look-ups are off\nCouldn't read the game (get_bank)", s.details);
	}

	@Test
	public void otherLinesAreKeptAsTheyAre()
	{
		List<String> lines = Arrays.asList(
			"Something a later version writes",
			"Shared your Slayer task",
			"Read the Wiki page Vorkath",
			"Read the Wiki page \"\"",
			"Searched the Wiki for vorkath",
			"Checked the GE price of ",
			"Couldn't finish a look-up (wiki_page)");
		PanelText.Summary shown = PanelText.summary(lines);
		assertNull(shown.lookups);
		assertEquals("Shared your Slayer task", shown.line(ROOMY));
		assertEquals(String.join("\n", lines), shown.details);
	}

	@Test
	public void nothingListedShowsNothing()
	{
		for (List<String> none : Arrays.asList(null, Collections.<String>emptyList(), Collections.<String>singletonList(null)))
		{
			PanelText.Summary shown = PanelText.summary(none);
			assertTrue(shown.isEmpty());
			assertEquals("", shown.details);
		}
		assertFalse(sum("Shared your bank").isEmpty());
	}
}

