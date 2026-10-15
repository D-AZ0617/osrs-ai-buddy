package com.osrsaibuddy;

import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The character note sent with a message when the player shares their character. */
public class CharacterInfoTest
{
	@Test
	public void characterInfoReadsLikeANote()
	{
		String s = CharacterInfo.format("Zezima", CharacterInfo.accountType(1), 126, 2277, List.of("Attack 99", "Sailing 1"),
			300, List.of("Dragon Slayer I"), Collections.emptyList());
		assertTrue(s, s.startsWith("[Character: Zezima, an ironman, combat level 126, total level 2277. "
			+ "Levels: Attack 99, Sailing 1."));
		assertTrue(s, s.contains("Quests completed (1): Dragon Slayer I."));
		assertTrue(s, s.endsWith("Quests in progress: none.]"));
	}

	@Test
	public void theAccountTypeIsLeftOutWhenItIsntKnown()
	{
		assertEquals("a regular account (not an ironman)", CharacterInfo.accountType(0));
		assertEquals("an unranked group ironman", CharacterInfo.accountType(6));
		assertNull(CharacterInfo.accountType(7));
		String s = CharacterInfo.format("Zezima", null, 3, 32, List.of("Attack 1"), 0, Collections.emptyList(),
			List.of("Cook's Assistant"));
		assertTrue(s, s.startsWith("[Character: Zezima, combat level 3, total level 32."));
		assertTrue(s, s.contains("Quests completed (0): none. Quests in progress: Cook's Assistant.]"));
	}
}

