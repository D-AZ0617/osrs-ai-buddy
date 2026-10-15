package com.osrsaibuddy;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The panel's words around the messages: the line under a reply on its way saying what it's doing, each message's
 * tooltip, how notes read, and the line saying what was looked up or shared for a message. Pure, so it's tested
 * without Swing.
 */
final class PanelText
{
	/** "Thinking" counts its dots for this long; then the seconds count instead, for a model that's slow to start. */
	static final int THINKING_DOTS_SECONDS = 5;
	/** A summary's note, as {@link ConversationBuilder#applySummary} writes it: how many messages, then the summary. */
	private static final Pattern SUMMARY_NOTE = Pattern.compile(
		"Summary of the (?:(\\d+) earlier messages, sent instead of them|earlier message, sent instead of it):\\n\\n(.*)",
		Pattern.DOTALL);

	private PanelText()
	{
	}

	/**
	 * The muted line under the reply on its way, at {@code now} (milliseconds): what it's doing while there are no new
	 * words to read. Null while it's writing.
	 */
	static String live(Chat chat, long now)
	{
		if (chat.retryWhy != null && chat.retryAt > now)
		{
			return chat.retryWhy + "; trying again in " + ChatApi.seconds(chat.retryAt - now) + "s";
		}
		long ms = now - chat.runStartedAt;
		if (chat.isSummarizing())
		{
			return "Summarising earlier messages\u2026 " + elapsed(ms);
		}
		if (chat.lookingUp)
		{
			// What it looked up is listed just above, as each look-up comes back: this line only says it's still going.
			return "Looking things up\u2026 " + elapsed(ms);
		}
		return chat.liveText == null ? thinking(ms) : null;
	}

	/**
	 * Before the first words: "Thinking" with one, two, then three dots, a step a second (the panel's ticker redraws it
	 * each second), then the seconds it's been, since a model on the player's own computer can take minutes to start.
	 */
	static String thinking(long ms)
	{
		long secs = Math.max(0, ms / 1000);
		if (secs < THINKING_DOTS_SECONDS)
		{
			return "Thinking" + "...".substring(0, (int) (secs % 3) + 1);
		}
		return "Thinking\u2026 " + elapsed(ms);
	}

	/** "12s", "3m 5s". */
	static String elapsed(long ms)
	{
		long secs = Math.max(0, ms / 1000);
		return secs < 60 ? secs + "s" : (secs / 60) + "m " + (secs % 60) + "s";
	}

	/**
	 * A message's tooltip: when it was sent, and for a reply who wrote it, with what the transcript no longer spells out
	 * under each message: a question that went unanswered, a reply that didn't finish, a message the summary now
	 * stands in for.
	 */
	static String tooltip(Chat.Message m)
	{
		String time = new SimpleDateFormat("HH:mm").format(new Date(m.time));
		StringBuilder tip = new StringBuilder();
		if (m.role == Chat.Role.ASSISTANT)
		{
			tip.append(m.who != null ? m.who : "Assistant").append(" \u00b7 ");
		}
		tip.append(time);
		if (m.role == Chat.Role.USER && m.unanswered)
		{
			tip.append(" \u00b7 not answered");
		}
		if (m.unfinished)
		{
			tip.append(" \u00b7 didn't finish");
		}
		if (m.summarized)
		{
			tip.append(" \u00b7 summarised: the summary further down is sent instead");
		}
		return tip.toString();
	}

	/** How the transcript shows a note: one line, and what's a click away from it. */
	static final class Note
	{
		final String line;
		/** Null for nothing more. */
		final String details;

		private Note(String line, String details)
		{
			this.line = line;
			this.details = details;
		}
	}

	/**
	 * A note as the transcript shows it: a summary's note is a short line, "Summary of 24 earlier messages", with the
	 * summary itself a click away (it can be long); any other note is shown as it is.
	 */
	static Note note(String text)
	{
		Matcher m = SUMMARY_NOTE.matcher(text == null ? "" : text);
		if (!m.matches())
		{
			return new Note(text == null ? "" : text, null);
		}
		String count = m.group(1);
		String line = count == null ? "Summary of 1 earlier message" : "Summary of " + count + " earlier messages";
		return new Note(line, m.group(2).trim());
	}

	/**
	 * What was looked up or shared for a message, as the panel shows it: one muted line over the message, with
	 * everything a click away. What the player shared of their own is named first, and always in full; then the Wiki
	 * pages and GE prices, by name when they fit, or counted ("Looked up 3 things", "3 look-ups") when they don't.
	 */
	static final class Summary
	{
		/** Between the parts of the line. */
		static final String SEPARATOR = " \u00b7 ";
		static final String ELLIPSIS = "\u2026";
		/** What a line cut short doesn't end on, before its ellipsis: spaces, and the marks that join words. */
		private static final Pattern TRAILING = Pattern.compile("[\\s:;,\u00b7]+$");

		/** "Shared your equipment and inventory", or null when nothing of the player's was shared. */
		final String sharing;
		/** "Looked up Vorkath, Dragon bones (GE price)", or null when nothing was. */
		final String lookups;
		/**
		 * "Looked up 3 things", or null when nothing was. Also null for one thing when nothing was shared: its name cut
		 * short says more than "Looked up 1 thing".
		 */
		final String count;
		/** "3 look-ups": the count, shorter. Null when {@link #count} is. */
		final String tally;
		/** When nothing was shared or looked up: the first line there is (a skipped look-up, say), or null. */
		final String other;
		/**
		 * Everything, for when the line is clicked: each line as it was recorded (what was shared, skipped or went
		 * wrong), then what was looked up, one kind to a line ("Wiki pages: ...", "Wiki searches: ...", "GE prices:
		 * ..."). Empty when there's nothing.
		 */
		final String details;

		private Summary(String sharing, String lookups, int things, String other, String details)
		{
			this.sharing = sharing;
			this.lookups = lookups;
			boolean counted = things > 1 || things == 1 && sharing != null;
			this.count = counted ? "Looked up " + things + (things == 1 ? " thing" : " things") : null;
			this.tally = counted ? things + (things == 1 ? " look-up" : " look-ups") : null;
			this.other = other;
			this.details = details;
		}

		/** Whether there's nothing to show: no line at all. */
		boolean isEmpty()
		{
			return details.isEmpty();
		}

		/**
		 * The line, as long as {@code fits} says fits on one row: the look-ups by name, else counted ("Looked up 3
		 * things", then "3 look-ups"), else cut short at the end. Never cut into what was shared: after it, the look-ups
		 * are cut down to an ellipsis, or left off (the line's chevron says there's more); only if what was shared
		 * doesn't fit by itself is the line longer than a row: it wraps, with the count on a row of its own.
		 */
		String line(Predicate<String> fits)
		{
			String named = join(sharing, lookups != null ? lookups : other);
			if (fits.test(named))
			{
				return named;
			}
			if (count != null && fits.test(join(sharing, count)))
			{
				return join(sharing, count);
			}
			if (tally != null && fits.test(join(sharing, tally)))
			{
				return join(sharing, tally);
			}
			if (sharing != null)
			{
				if (tally == null)
				{
					// Only what was shared, which is never cut.
					return sharing;
				}
				// A count cut short says nothing: all of it goes, after what was shared.
				for (String shorter : new String[]{sharing + SEPARATOR + ELLIPSIS, sharing + ELLIPSIS, sharing})
				{
					if (fits.test(shorter))
					{
						return shorter;
					}
				}
				// Longer than a row by itself: the count on a row of its own, rather than a separator left at the end
				// of one row or the start of the next.
				return sharing + "\n" + tally;
			}
			if (tally != null)
			{
				return tally;
			}
			// One thing looked up, or a line of its own: the longest start of it that fits with the ellipsis after it;
			// widths only grow with the length.
			int lo = 0;
			int hi = named.length() - 1;
			int best = -1;
			while (lo <= hi)
			{
				int mid = (lo + hi) >>> 1;
				if (fits.test(cut(named, mid)))
				{
					best = mid;
					lo = mid + 1;
				}
				else
				{
					hi = mid - 1;
				}
			}
			if (best < 0)
			{
				return named;
			}
			if (best < named.length() && named.charAt(best) != ' ')
			{
				// Not in the middle of a word: back to the space before it, if there's one.
				int space = named.lastIndexOf(' ', best - 1);
				best = space > 0 ? space : best;
			}
			return cut(named, best);
		}

		/** The first {@code length} characters of {@code text}, and an ellipsis: no stray space or colon before it. */
		static String cut(String text, int length)
		{
			return TRAILING.matcher(text.substring(0, length)).replaceFirst("") + ELLIPSIS;
		}

		private static String join(String first, String second)
		{
			if (first == null || second == null)
			{
				return first != null ? first : second == null ? "" : second;
			}
			return first + SEPARATOR + second;
		}
	}

	/**
	 * Sums up {@code lines} (a message's {@link Chat.Message#activity}; null for none) for the line over the message.
	 * Lines from {@link GameDataTools} for what was shared are put together ("Shared your equipment and bank"), and the
	 * Wiki and GE price look-ups from {@link LookupTools} too, each thing named once: the Wiki pages read (or, when none
	 * was, what was searched for), then the GE prices. A line for each was too much to read over every reply. Done only
	 * when shown: the saved lines keep every look-up, and chats saved by earlier versions show the same way.
	 */
	static Summary summary(List<String> lines)
	{
		List<String> pages = new ArrayList<>();
		List<String> searches = new ArrayList<>();
		List<String> prices = new ArrayList<>();
		List<String> rest = new ArrayList<>();
		for (String line : lines == null ? Collections.<String>emptyList() : lines)
		{
			if (line != null && !fold(line, pages, searches, prices))
			{
				rest.add(line);
			}
		}
		List<String> details = new ArrayList<>(rest);
		if (!pages.isEmpty())
		{
			details.add("Wiki pages: " + String.join(", ", pages));
		}
		if (!searches.isEmpty())
		{
			details.add("Wiki searches: " + quoted(searches));
		}
		if (!prices.isEmpty())
		{
			details.add("GE prices: " + String.join(", ", prices));
		}

		String sharing = sharing(rest);
		// The pages say what the searches found: the searches count only when no page was read.
		List<String> wiki = pages.isEmpty() ? quotedEach(searches) : pages;
		int things = wiki.size() + prices.size();
		String lookups = null;
		if (things > 0)
		{
			List<String> names = new ArrayList<>(wiki);
			if (!prices.isEmpty())
			{
				names.add(String.join(", ", prices) + (prices.size() == 1 ? " (GE price)" : " (GE prices)"));
			}
			lookups = "Looked up " + String.join(", ", names);
		}
		String other = sharing == null && lookups == null && !rest.isEmpty() ? brief(rest.get(0)) : null;
		return new Summary(sharing, lookups, things, other, String.join("\n", details));
	}

	/**
	 * What the player shared of their own, from {@link GameDataTools}' lines: "Shared your equipment, inventory and
	 * bank", "Searched your bank", "Shared your equipment and searched your bank"; null for nothing. Lines for what
	 * wasn't shared after all (the request had stopped, a setting is off) don't count.
	 */
	private static String sharing(List<String> lines)
	{
		List<String> shared = new ArrayList<>();
		boolean searched = false;
		for (String line : lines)
		{
			if (line.startsWith(GameDataTools.SHARED))
			{
				addOnce(shared, line.substring(GameDataTools.SHARED.length()));
			}
			else if (line.startsWith(GameDataTools.SEARCHED) && !line.endsWith(GameDataTools.UNSHARED))
			{
				searched = true;
			}
		}
		// Sharing the whole bank says it all for a search of it too.
		searched &= !shared.contains("bank");
		if (shared.isEmpty())
		{
			return searched ? "Searched your bank" : null;
		}
		String what = "Shared your " + and(shared);
		if (!searched)
		{
			return what;
		}
		return what + (shared.size() > 1 ? ", and" : " and") + " searched your bank";
	}

	/** "a", "a and b", "a, b and c". */
	private static String and(List<String> items)
	{
		if (items.size() == 1)
		{
			return items.get(0);
		}
		return String.join(", ", items.subList(0, items.size() - 1)) + " and " + items.get(items.size() - 1);
	}

	/** A line on its own, as the summary shows it when it's all there is: the long ones said shorter. */
	private static String brief(String line)
	{
		if (RequestRunner.NO_LOOKUPS.equals(line))
		{
			return "No look-ups: this model can't use tools";
		}
		if (line.endsWith(GameDataTools.UNSHARED))
		{
			// What was read but never sent (see GameDataTools.unshared): the details have the line in full.
			String read = line.substring(0, line.length() - GameDataTools.UNSHARED.length());
			if (read.startsWith(GameDataTools.READ))
			{
				return "Didn't share your " + read.substring(GameDataTools.READ.length()) + " (stopped)";
			}
			if (read.startsWith(GameDataTools.SEARCHED))
			{
				return "Didn't share your bank search (stopped)";
			}
		}
		return line;
	}

	/**
	 * For a line {@link LookupTools} wrote for a look-up that went through: adds what was looked up to its list, once.
	 * False for any other line, which is shown as it is.
	 */
	private static boolean fold(String line, List<String> pages, List<String> searches, List<String> prices)
	{
		if (line.startsWith(LookupTools.SEARCHED_WIKI))
		{
			return addOnce(searches, unquote(line.substring(LookupTools.SEARCHED_WIKI.length())));
		}
		if (line.startsWith(LookupTools.READ_WIKI_PAGE))
		{
			return addOnce(pages, pageTitle(line.substring(LookupTools.READ_WIKI_PAGE.length())));
		}
		if (line.startsWith(LookupTools.CHECKED_GE_PRICE))
		{
			String item = line.substring(LookupTools.CHECKED_GE_PRICE.length()).trim();
			return addOnce(prices, item.isEmpty() ? null : item);
		}
		return false;
	}

	/** False when there's no {@code name}: the line wasn't one the look-ups make after all. */
	private static boolean addOnce(List<String> names, String name)
	{
		if (name == null)
		{
			return false;
		}
		if (!names.contains(name))
		{
			names.add(name);
		}
		return true;
	}

	/** {@code Abyssal whip} from {@code "Abyssal whip"}; null for anything else. */
	private static String unquote(String s)
	{
		return s.length() > 2 && s.startsWith("\"") && s.endsWith("\"") ? s.substring(1, s.length() - 1) : null;
	}

	/** The title from {@code "Abyssal whip"}, or from {@code "Abyssal whip" (Drops)} when one section was read. */
	private static String pageTitle(String s)
	{
		int end = s.indexOf("\" (");
		return unquote(end > 0 && s.endsWith(")") ? s.substring(0, end + 1) : s);
	}

	private static List<String> quotedEach(List<String> names)
	{
		List<String> out = new ArrayList<>();
		for (String name : names)
		{
			out.add('"' + name + '"');
		}
		return out;
	}

	private static String quoted(List<String> names)
	{
		return String.join(", ", quotedEach(names));
	}
}
