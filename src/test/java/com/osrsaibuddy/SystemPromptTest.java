package com.osrsaibuddy;

import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** What the assistant is told about OSRS AI Buddy's settings: their names as the settings panel shows them. */
public class SystemPromptTest
{
	@Test
	public void theSettingsItSuggestsAreNamedAsThePlayerSeesThem()
	{
		String items = SettingName.of("shareItems");
		String character = SettingName.of("sendCharacter");
		assertTrue(OsrsAiBuddyPlugin.SYSTEM_PROMPT.contains("(\"" + items + "\" or \"" + character + "\")"));
		assertFalse("the old name", OsrsAiBuddyPlugin.SYSTEM_PROMPT.contains("Send character info"));
	}

	@Test
	public void itKnowsThePanelShowsTables()
	{
		// The panel draws tables (and turns wide ones into cards): the assistant mustn't refuse to make one.
		assertFalse(OsrsAiBuddyPlugin.SYSTEM_PROMPT.contains("don't use tables"));
		assertTrue(OsrsAiBuddyPlugin.SYSTEM_PROMPT.contains("small tables are fine"));
	}
}

