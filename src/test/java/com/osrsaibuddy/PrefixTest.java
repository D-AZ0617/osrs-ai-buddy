package com.osrsaibuddy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import org.junit.Test;

public class PrefixTest
{
	@Test
	public void recognisesOurCommand()
	{
		assertEquals("fix the build", OsrsAiBuddyPlugin.stripPrefix("::buddy fix the build"));
		assertEquals("fix the build", OsrsAiBuddyPlugin.stripPrefix("  ::BUDDY   fix the build "));
		assertEquals("", OsrsAiBuddyPlugin.stripPrefix("::buddy"));
	}

	@Test
	public void leavesOtherChatAlone()
	{
		// Including other plugins' commands, like Claude Chat's ::claude.
		assertNull(OsrsAiBuddyPlugin.stripPrefix("::claude hi"));
		assertNull(OsrsAiBuddyPlugin.stripPrefix("::buddyx"));
		assertNull(OsrsAiBuddyPlugin.stripPrefix("ai fix it"));
		assertNull(OsrsAiBuddyPlugin.stripPrefix("hello ::buddy"));
		assertNull(OsrsAiBuddyPlugin.stripPrefix(""));
		assertNull(OsrsAiBuddyPlugin.stripPrefix(null));
	}
}


