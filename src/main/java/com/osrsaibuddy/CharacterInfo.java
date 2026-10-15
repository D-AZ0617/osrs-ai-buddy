package com.osrsaibuddy;

import java.util.ArrayList;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;

/** The character details sent with a message when the player turns that on. */
final class CharacterInfo
{
	private CharacterInfo()
	{
	}

	/** Client thread only: reading quest states runs a game script. Null when there's no character to describe. */
	static String describe(Client client)
	{
		Player me = client.getLocalPlayer();
		if (me == null || me.getName() == null)
		{
			return null;
		}
		List<String> levels = new ArrayList<>();
		for (Skill s : Skill.values())
		{
			levels.add(s.getName() + " " + client.getRealSkillLevel(s));
		}
		List<String> done = new ArrayList<>();
		List<String> started = new ArrayList<>();
		for (Quest q : Quest.values())
		{
			QuestState state = q.getState(client);
			if (state == QuestState.FINISHED)
			{
				done.add(q.getName());
			}
			else if (state == QuestState.IN_PROGRESS)
			{
				started.add(q.getName());
			}
		}
		return format(me.getName(), accountType(client.getVarbitValue(VarbitID.IRONMAN)), me.getCombatLevel(),
			client.getTotalLevel(), levels, client.getVarpValue(VarPlayerID.QP), done, started);
	}

	/**
	 * The account type as the note puts it, from the game's IRONMAN varbit; null for a value this doesn't know yet. An
	 * ironman can't use the Grand Exchange or trade, which changes most advice, so a regular account says so too.
	 */
	static String accountType(int ironman)
	{
		switch (ironman)
		{
			case 0:
				return "a regular account (not an ironman)";
			case 1:
				return "an ironman";
			case 2:
				return "an ultimate ironman";
			case 3:
				return "a hardcore ironman";
			case 4:
				return "a group ironman";
			case 5:
				return "a hardcore group ironman";
			case 6:
				return "an unranked group ironman";
			default:
				return null;
		}
	}

	/** {@code accountType}: from {@link #accountType}, or null to leave it out. */
	static String format(String name, String accountType, int combat, int total, List<String> levels, int questPoints,
		List<String> done, List<String> started)
	{
		return "[Character: " + name + (accountType == null ? "" : ", " + accountType)
			+ ", combat level " + combat + ", total level " + total + ". "
			+ "Levels: " + String.join(", ", levels) + ". "
			+ "Quest points: " + questPoints + ". "
			+ "Quests completed (" + done.size() + "): " + (done.isEmpty() ? "none" : String.join(", ", done)) + ". "
			+ "Quests in progress: " + (started.isEmpty() ? "none" : String.join(", ", started)) + ".]";
	}
}
