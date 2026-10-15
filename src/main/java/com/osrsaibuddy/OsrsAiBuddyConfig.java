package com.osrsaibuddy;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Notification;

@ConfigGroup(OsrsAiBuddyConfig.GROUP)
public interface OsrsAiBuddyConfig extends Config
{
	String GROUP = "osrs-ai-buddy";

    @ConfigSection(name = "Account context", description = "Choose the live account details Buddy may use in advice", position = 0)
    String PRIVACY = "privacy";

    @ConfigSection(name = "Knowledge sources", description = "Choose external sources for game knowledge and account history", position = 1, closedByDefault = true)
    String KNOWLEDGE = "knowledge";

    @ConfigSection(name = "Notifications", description = "Choose reply notification behavior", position = 2, closedByDefault = true)
    String CONVERSATION = "conversation";

	@ConfigItem(
		keyName = "wikiLookups",
		name = "Allow OSRS Wiki searches",
		description = "Let the assistant search and read the OSRS Wiki while it answers, and link the pages it used. The Wiki gets the search words and page titles, and your IP address. OSRS AI Buddy adds nothing about your account, but the assistant writes the search words from your question and anything you've shared. GE prices come from RuneLite's own price data and stay available with this off: they never go to the Wiki.",
		section = KNOWLEDGE,
		position = 0
	)
	default boolean wikiLookups()
	{
		return true;
	}

	@ConfigItem(
		keyName = "chatgptPlanModel",
		hidden = true,
		name = "Model",
		description = "Sign in from the OSRS AI Buddy panel menu, then choose a model next to Send. Uses your authorized ChatGPT plan, without API-key fallback.",
		section = KNOWLEDGE,
		position = 1
	)
	default String chatgptPlanModel()
	{
		return "";
	}

	@ConfigItem(
		keyName = "sendCharacter",
		name = "Share character overview",
		description = "Send your character name, account type (ironman or not), levels and quest progress with your messages, and let the assistant look up your Slayer task and achievement diaries, for answers that fit your account. Off by default.",
		warning = "This feature submits your IP address, character name, account type, skill levels, quest progress, Slayer task and achievement diaries with your messages to OpenAI: a 3rd-party server not controlled or verified by RuneLite developers.",
		section = PRIVACY,
		position = 0
	)
	default boolean sendCharacter()
	{
		return false;
	}

	@ConfigItem(
		keyName = "shareItems",
		name = "Share bank, inventory & gear",
		description = "Let the assistant look at your equipment, inventory and bank (as it was when you last had it open) when a question needs them. Each look is listed with the reply. Off by default.",
		warning = "This feature submits your IP address and the items you wear, carry and keep in your bank, with their prices, to OpenAI when the assistant looks at them: a 3rd-party server not controlled or verified by RuneLite developers.",
		section = PRIVACY,
		position = 2
	)
	default boolean shareItems()
	{
		return false;
	}

    @ConfigItem(keyName = "shareProgress", name = "Share achievements & collections", section = PRIVACY, position = 1,
        description = "Let Buddy read skills/XP, quest states, combat achievements, known collection-log items and completion sections, prayer/Slayer unlocks and selected currencies. Collection items are partial until supplied by the game. Off by default.",
        warning = "This feature submits your account progress, achievements, collection-log information and unlocks to OpenAI, a third-party server not controlled or verified by RuneLite developers.")
    default boolean shareProgress() { return false; }

    @ConfigItem(keyName = "shareWom", name = "Use Wise Old Man history", section = KNOWLEDGE, position = 1,
        description = "Read your existing public WOM profile, boss kills, activities, XP, gains, records and achievements when needed. Historical data can be stale. Does not create tracking or update your profile. Off by default.",
        warning = "This feature submits your logged-in character name and IP address to Wise Old Man, and the retrieved account statistics, achievements, history and public group/competition information to OpenAI. These are third-party servers not controlled or verified by RuneLite developers.")
    default boolean shareWom() { return false; }

	@ConfigItem(
		keyName = "notifyOnDone",
		name = "Reply alerts",
		description = "Notification when a reply arrives. RuneLite skips notifications while the game is focused unless you enable that here.",
		section = CONVERSATION,
		position = 2
	)
	default Notification notifyOnDone()
	{
		return Notification.ON;
	}
}
