package com.osrsaibuddy;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Keybind;
import net.runelite.client.config.Notification;
import net.runelite.client.config.Range;

@ConfigGroup(OsrsAiBuddyConfig.GROUP)
public interface OsrsAiBuddyConfig extends Config
{
	String GROUP = "osrs-ai-buddy";

	@ConfigSection(name = "ChatGPT", description = "Sign in from the panel menu and use your ChatGPT plan", position = 0)
	String GENERAL = "chatgpt";

	@ConfigSection(name = "Data & privacy", description = "Choose what account information to share", position = 1)
	String PRIVACY = "privacy";

	@ConfigSection(name = "Notifications & game chat", description = "Reply notifications and chatbox controls", position = 2)
	String GAME_CHAT = "chat";

	@ConfigItem(
		keyName = "aiRequests",
		name = "Enable AI requests",
		description = "Send questions and chat history to OpenAI using your authorized ChatGPT plan. Sign-in works separately; model lists and Wiki look-ups require this setting.",
		warning = "This feature submits your IP address and the messages you type to OpenAI, and (with \"Wiki look-ups\", on by default) the search words and page titles the assistant looks up to the OSRS Wiki: 3rd-party servers not controlled or verified by RuneLite developers.",
		section = GENERAL,
		position = 0
	)
	default boolean aiRequests()
	{
		return false;
	}

	@ConfigItem(
		keyName = "instructions",
		name = "Custom instructions",
		description = "Added to what the assistant is told before every chat, for example \"I'm an ironman\" or \"Answer in Dutch\".",
		section = GENERAL,
		position = 2
	)
	default String instructions()
	{
		return "";
	}

	@ConfigItem(
		keyName = "wikiLookups",
		name = "Wiki look-ups",
		description = "Let the assistant search and read the OSRS Wiki while it answers, and link the pages it used. The Wiki gets the search words and page titles, and your IP address. OSRS AI Buddy adds nothing about your account, but the assistant writes the search words from your question and anything you've shared. GE prices come from RuneLite's own price data and stay available with this off: they never go to the Wiki.",
		section = GENERAL,
		position = 3
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
		section = GENERAL,
		position = 2
	)
	default String chatgptPlanModel()
	{
		return "";
	}

	@ConfigItem(
		keyName = "sendCharacter",
		name = "Share character details",
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
		name = "Share items and gear",
		description = "Let the assistant look at your equipment, inventory and bank (as it was when you last had it open) when a question needs them. Each look is listed with the reply. Off by default.",
		warning = "This feature submits your IP address and the items you wear, carry and keep in your bank, with their prices, to OpenAI when the assistant looks at them: a 3rd-party server not controlled or verified by RuneLite developers.",
		section = PRIVACY,
		position = 1
	)
	default boolean shareItems()
	{
		return false;
	}

    @ConfigItem(keyName = "shareProgress", name = "Share extended progress", section = PRIVACY, position = 3,
        description = "Let Buddy read skills/XP, quest states, combat achievements, known collection-log items and completion sections, prayer/Slayer unlocks and selected currencies. Collection items are partial until supplied by the game. Off by default.",
        warning = "This feature submits your account progress, achievements, collection-log information and unlocks to OpenAI, a third-party server not controlled or verified by RuneLite developers.")
    default boolean shareProgress() { return false; }

    @ConfigItem(keyName = "shareWom", name = "Share Wise Old Man data", section = PRIVACY, position = 4,
        description = "Read your existing public WOM profile, boss kills, activities, XP, gains, records and achievements when needed. Historical data can be stale. Does not create tracking or update your profile. Off by default.",
        warning = "This feature submits your logged-in character name and IP address to Wise Old Man, and the retrieved account statistics, achievements, history and public group/competition information to OpenAI. These are third-party servers not controlled or verified by RuneLite developers.")
    default boolean shareWom() { return false; }

	@ConfigItem(
		keyName = "rememberChats",
		name = "Save chat history",
		description = "Keep your chats when RuneLite closes, saved on this computer in .runelite/plugin-data/osrs-ai-buddy (the latest 200 messages of each, and older ones still sent to the assistant). Turning this off deletes the saved copy; chats then last only while RuneLite is open.",
		section = PRIVACY,
		position = 2
	)
	default boolean rememberChats()
	{
		return true;
	}

	@ConfigItem(
		keyName = "notifyOnDone",
		name = "Notify on reply",
		description = "Notification when a reply arrives. RuneLite skips notifications while the game is focused unless you enable that here.",
		section = GAME_CHAT,
		position = 0
	)
	default Notification notifyOnDone()
	{
		return Notification.ON;
	}

	@ConfigItem(
		keyName = "echoToChat",
		name = "Show replies in game chat",
		description = "Print replies in the chatbox, not only in the side panel.",
		section = GAME_CHAT,
		position = 1
	)
	default boolean echoToChat()
	{
		return true;
	}

	@ConfigItem(
		keyName = "echoMaxChars",
		name = "Game chat reply length",
		description = "Longer replies are cut short in the chatbox; the side panel always has the full text.",
		section = GAME_CHAT,
		position = 2
	)
	default int echoMaxChars()
	{
		return 500;
	}

	@ConfigItem(
		keyName = "askHotkey",
		name = "Ask hotkey",
		description = "Opens an 'Ask:' prompt in the chatbox.",
		section = GAME_CHAT,
		position = 3
	)
	default Keybind askHotkey()
	{
		return Keybind.NOT_SET;
	}
}
