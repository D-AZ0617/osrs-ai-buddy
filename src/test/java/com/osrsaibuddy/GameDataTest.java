package com.osrsaibuddy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.Item;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** How the player's own game data is written up for the model, from plain values (no game needed). */
public class GameDataTest
{
	private static final long MINUTE = 60_000;

	private static GameData.ItemLine item(String name, long quantity, long price)
	{
		return new GameData.ItemLine(null, name, false, quantity, price);
	}

	private static GameData.ItemLine worn(String slot, String name, long quantity, long price)
	{
		return new GameData.ItemLine(slot, name, false, quantity, price);
	}

	private static int listed(String text)
	{
		int n = 0;
		for (String line : text.split("\n"))
		{
			n += line.startsWith("- ") ? 1 : 0;
		}
		return n;
	}

	@Test
	public void itemLinesShowNamesAmountsAndPrices()
	{
		assertEquals("Abyssal whip (1,402,330 gp)", GameData.itemText(item("Abyssal whip", 1, 1_402_330)));
		assertEquals("Shark x10 (1,032 gp each, 10,320 gp total)", GameData.itemText(item("Shark", 10, 1032)));
		// Coins are 1 gp each; the line says what the stack is worth instead.
		assertEquals("Coins x1,250,000 (1,250,000 gp)", GameData.itemText(item("Coins", 1_250_000, 1)));
		assertEquals("Dragon bones (noted) x500 (2,100 gp each, 1,050,000 gp total)",
			GameData.itemText(new GameData.ItemLine(null, "Dragon bones", true, 500, 2100)));
		// Untradeable (or prices not loaded yet): no price at all, rather than "0 gp".
		assertEquals("Graceful hood", GameData.itemText(item("Graceful hood", 1, 0)));
		assertEquals("Weapon: Abyssal whip (1,402,330 gp)", GameData.itemText(worn("Weapon", "Abyssal whip", 1, 1_402_330)));
		assertEquals("Ammo: Rune arrow x512 (90 gp each, 46,080 gp total)", GameData.itemText(worn("Ammo", "Rune arrow", 512, 90)));
	}

	@Test
	public void bigStacksDontOverflow()
	{
		assertEquals("Platinum token x2,147,483,647 (1,000 gp each, 2,147,483,647,000 gp total)",
			GameData.itemText(item("Platinum token", Integer.MAX_VALUE, 1000)));
	}

	@Test
	public void equipmentListsEachSlotAndTheTotal()
	{
		String text = GameData.equipmentText(Arrays.asList(
			worn("Head", "Helm of neitiznot", 1, 52_000),
			worn("Weapon", "Abyssal whip", 1, 1_400_000),
			worn("Cape", "Fire cape", 1, 0),
			worn("Ammo", "Rune arrow", 100, 90)));
		assertTrue(text, text.startsWith("The player's worn equipment, with Grand Exchange prices from RuneLite:\n"));
		assertTrue(text, text.contains("\n- Head: Helm of neitiznot (52,000 gp)\n- Weapon: Abyssal whip (1,400,000 gp)\n"
			+ "- Cape: Fire cape\n- Ammo: Rune arrow x100 (90 gp each, 9,000 gp total)\n"));
		assertTrue(text, text.endsWith("Total value at Grand Exchange prices: 1,461,000 gp (items without a price not counted)."));
	}

	@Test
	public void totalLeavesOutTheNoteWhenEverythingHasAPrice()
	{
		String text = GameData.equipmentText(Collections.singletonList(worn("Weapon", "Abyssal whip", 1, 1_400_000)));
		assertTrue(text, text.endsWith("\nTotal value at Grand Exchange prices: 1,400,000 gp."));
	}

	@Test
	public void nothingWornOrPriced()
	{
		assertEquals("The player isn't wearing or wielding anything.", GameData.equipmentText(Collections.emptyList()));
		// No total line at all when nothing has a price.
		String text = GameData.equipmentText(Collections.singletonList(worn("Cape", "Fire cape", 1, 0)));
		assertFalse(text, text.contains("Total"));
	}

	@Test
	public void inventoryAddsUpStacksAndCountsSlots()
	{
		Item[] inv = {
			new Item(385, 1),
			new Item(-1, 0),
			null,
			new Item(995, 5000),
			new Item(385, 1),
			new Item(537, 100),
		};
		Map<Integer, Long> stacks = GameData.stacks(inv);
		assertEquals(Arrays.asList(385, 995, 537), new ArrayList<>(stacks.keySet()));
		assertEquals(Long.valueOf(2), stacks.get(385));
		assertEquals(Long.valueOf(5000), stacks.get(995));
		assertEquals(4, GameData.slotsUsed(inv));

		String text = GameData.inventoryText(new GameData.Items(Arrays.asList(
			item("Shark", 2, 1000),
			item("Coins", 5000, 1),
			new GameData.ItemLine(null, "Dragon bones", true, 100, 2000)), 4));
		assertTrue(text, text.startsWith("The player's inventory (4 of 28 slots used), with Grand Exchange prices from RuneLite:\n"));
		assertTrue(text, text.contains("\n- Shark x2 (1,000 gp each, 2,000 gp total)\n- Coins x5,000 (5,000 gp)\n"
			+ "- Dragon bones (noted) x100 (2,000 gp each, 200,000 gp total)\n"));
		assertTrue(text, text.endsWith("Total value at Grand Exchange prices: 207,000 gp."));
	}

	@Test
	public void emptyInventory()
	{
		assertEquals(0, GameData.slotsUsed(new Item[28]));
		assertEquals("The player's inventory is empty (all 28 slots free).",
			GameData.inventoryText(new GameData.Items(Collections.emptyList(), 0)));
	}

	@Test
	public void bankSnapshotSkipsPlaceholdersFillersAndEmptySlots()
	{
		Item[] bank = {
			new Item(995, 1_000_000),
			// A placeholder: none of the item left.
			new Item(4151, 0),
			new Item(ItemID.BANK_FILLER, 1),
			new Item(-1, 0),
			null,
			new Item(385, 250),
		};
		GameData.BankSnapshot seen = GameData.BankSnapshot.of(bank, 42L, 1000L);
		assertTrue(Arrays.equals(new int[]{995, 385}, seen.ids));
		assertTrue(Arrays.equals(new int[]{1_000_000, 250}, seen.quantities));
		assertEquals(42L, seen.accountHash);
		assertEquals(1000L, seen.seenAt);
	}

	@Test
	public void bankSaysWhenItWasSeen()
	{
		GameData.Bank bank = new GameData.Bank(Arrays.asList(item("Coins", 1_000_000, 1), item("Shark", 250, 1000),
			item("Quest point cape", 1, 0)), 0);
		String text = GameData.bankText(bank, null, 12 * MINUTE);
		assertTrue(text, text.startsWith("The player's bank (as last seen 12 minutes ago): 3 items, placeholders left out. "
			+ "Grand Exchange prices from RuneLite:\n"));
		assertTrue(text, text.contains("\n- Coins x1,000,000 (1,000,000 gp)\n- Shark x250 (1,000 gp each, 250,000 gp total)\n"
			+ "- Quest point cape\n"));
		assertTrue(text, text.endsWith("Total value of the bank at Grand Exchange prices: 1,250,000 gp (items without a price not counted)."));
	}

	@Test
	public void emptyBank()
	{
		assertEquals("The player's bank was empty (as last seen less than a minute ago).",
			GameData.bankText(new GameData.Bank(Collections.emptyList(), 0), null, 5000));
	}

	@Test
	public void bankSearchIgnoresCase()
	{
		GameData.Bank bank = new GameData.Bank(Arrays.asList(item("Rune platebody", 1, 38_000), item("Shark", 250, 1000),
			item("Air rune", 5000, 4), item("Runite bar", 10, 12_000)), 0);
		String text = GameData.bankText(bank, "RUNE", 2 * 60 * MINUTE);
		assertTrue(text, text.startsWith("Items in the player's bank with \"RUNE\" in their name (as last seen about 2 hours ago), "
			+ "with Grand Exchange prices from RuneLite:\n"));
		assertEquals(2, listed(text));
		assertTrue(text, text.contains("\n- Rune platebody (38,000 gp)\n- Air rune x5,000 (4 gp each, 20,000 gp total)\n"));
		assertFalse(text, text.contains("Shark"));
		assertFalse(text, text.contains("Runite"));
		assertTrue(text, text.endsWith("Total value of these at Grand Exchange prices: 58,000 gp."));
	}

	@Test
	public void bankSearchWithNoMatch()
	{
		GameData.Bank bank = new GameData.Bank(Arrays.asList(item("Shark", 250, 1000), item("Coins", 10, 1)), 0);
		assertEquals("Nothing in the player's bank has \"twisted bow\" in its name (as last seen 1 minute ago; 2 items in "
			+ "all). Try a shorter or different search.", GameData.bankText(bank, "twisted bow", MINUTE));
	}

	@Test
	public void longBanksAreCutWithANote()
	{
		List<GameData.ItemLine> lines = new ArrayList<>();
		for (int i = 1; i <= 350; i++)
		{
			lines.add(item("Item " + i, 1, 10));
		}
		String text = GameData.bankText(new GameData.Bank(lines, 0), null, 0);
		assertEquals(GameData.BANK_LINES, listed(text));
		assertTrue(text, text.contains("\n- Item 300 (10 gp)\n...and 50 more items not listed. Ask again with search to "
			+ "find particular items.\n"));
		assertFalse(text, text.contains("Item 301"));
		// The total still covers the whole bank.
		assertTrue(text, text.endsWith("Total value of the bank at Grand Exchange prices: 3,500 gp."));

		String found = GameData.bankText(new GameData.Bank(lines, 0), "item 1", 0);
		// "Item 1", "Item 10".."Item 19", "Item 100".."Item 199": 111, all listed.
		assertEquals(111, listed(found));
		assertFalse(found, found.contains("not listed"));
	}

	@Test
	public void agoReadsNaturally()
	{
		assertEquals("less than a minute ago", GameData.ago(59_999));
		assertEquals("less than a minute ago", GameData.ago(-5000));
		assertEquals("1 minute ago", GameData.ago(MINUTE));
		assertEquals("59 minutes ago", GameData.ago(59 * MINUTE + 59_000));
		assertEquals("about 1 hour ago", GameData.ago(60 * MINUTE));
		assertEquals("about 3 hours ago", GameData.ago(200 * MINUTE));
	}

	@Test
	public void slayerTask()
	{
		assertEquals("The player's Slayer task: Abyssal demons, 87 left of 150. Slayer points: 1,234. Task streak: 56 "
				+ "(Krystilia's and Mortimer's tasks have their own streaks, not counted here).",
			GameData.slayerText(new GameData.SlayerTask("Abyssal demons", 87, 150, null, 1234, 56)));
		assertTrue(GameData.slayerText(new GameData.SlayerTask("Kalphite", 30, 0, "Kalphite Lair", 10, 2))
			.startsWith("The player's Slayer task: Kalphite, 30 left, to be done in Kalphite Lair. Slayer points: 10."));
		assertTrue(GameData.slayerText(new GameData.SlayerTask(null, 0, 0, null, 0, 0))
			.startsWith("The player has no Slayer task right now. Slayer points: 0. Task streak: 0"));
		assertTrue(GameData.slayerText(new GameData.SlayerTask(null, 12, 40, null, 0, 0))
			.startsWith("The player has a Slayer task with 12 left, but which monster it is couldn't be read."));
	}

	@Test
	public void diariesFollowTheGamesOwnCheck()
	{
		Map<Integer, Integer> varbits = new HashMap<>();
		varbits.put(VarbitID.ARDOUGNE_DIARY_EASY_COMPLETE, 1);
		varbits.put(VarbitID.ARDOUGNE_DIARY_MEDIUM_COMPLETE, 1);
		varbits.put(VarbitID.ARDOUGNE_DIARY_HARD_COMPLETE, 1);
		varbits.put(VarbitID.ARDOUGNE_DIARY_ELITE_COMPLETE, 1);
		varbits.put(VarbitID.VARROCK_DIARY_EASY_COMPLETE, 1);
		varbits.put(VarbitID.VARROCK_DIARY_HARD_COMPLETE, 1);
		// Karamja's first three tiers are 2 when done; 1 there only means started.
		varbits.put(VarbitID.ATJUN_EASY_DONE, 2);
		varbits.put(VarbitID.ATJUN_MED_DONE, 1);
		varbits.put(VarbitID.KARAMJA_DIARY_ELITE_COMPLETE, 0);
		List<GameData.Diary> diaries = GameData.diaries(id -> varbits.getOrDefault(id, 0));
		assertEquals(12, diaries.size());

		String text = GameData.diaryText(diaries);
		assertTrue(text, text.startsWith("The player's achievement diaries, by tier:\n- Ardougne: all done\n- Desert: none done\n"));
		assertTrue(text, text.contains("\n- Karamja: Easy done; Medium, Hard, Elite not done\n"));
		assertTrue(text, text.contains("\n- Varrock: Easy, Hard done; Medium, Elite not done\n"));
		assertTrue(text, text.contains("\n- Kourend & Kebos: none done\n"));
		assertTrue(text, text.contains("\n- Wilderness: none done\n"));
		assertTrue(text, text.endsWith("\nCompleted 7 of 48 tiers."));
	}

	@Test
	public void slotNamesReadAsWords()
	{
		assertEquals("Head", GameData.slotName(EquipmentInventorySlot.HEAD));
		assertEquals("Weapon", GameData.slotName(EquipmentInventorySlot.WEAPON));
		assertEquals("Ammo", GameData.slotName(EquipmentInventorySlot.AMMO));
	}

	@Test
	public void accountTypeGoesInTheCharacterNote()
	{
		assertEquals("a regular account (not an ironman)", CharacterInfo.accountType(0));
		assertEquals("an ironman", CharacterInfo.accountType(1));
		assertEquals("an ultimate ironman", CharacterInfo.accountType(2));
		assertEquals("a hardcore ironman", CharacterInfo.accountType(3));
		assertEquals("a group ironman", CharacterInfo.accountType(4));
		assertEquals("a hardcore group ironman", CharacterInfo.accountType(5));
		assertEquals("an unranked group ironman", CharacterInfo.accountType(6));
		assertNull(CharacterInfo.accountType(7));

		String s = CharacterInfo.format("Zezima", CharacterInfo.accountType(3), 126, 2277, Arrays.asList("Attack 99", "Sailing 1"),
			300, Collections.singletonList("Dragon Slayer I"), Collections.emptyList());
		assertTrue(s, s.startsWith("[Character: Zezima, a hardcore ironman, combat level 126, total level 2277. "
			+ "Levels: Attack 99, Sailing 1. Quest points: 300."));
		// An account type the plugin doesn't know yet is left out rather than guessed.
		String unknown = CharacterInfo.format("Zezima", CharacterInfo.accountType(9), 126, 2277, Collections.emptyList(),
			0, Collections.emptyList(), Collections.emptyList());
		assertTrue(unknown, unknown.startsWith("[Character: Zezima, combat level 126,"));
	}
}

