package com.osrsaibuddy;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Keybind;
import net.runelite.client.config.Notification;

@ConfigGroup(OsrsAiBuddyConfig.GROUP)
public interface OsrsAiBuddyConfig extends Config
{
	String GROUP = "osrs-ai-buddy";

    @ConfigSection(name = "Getting started", description = "Connect from Buddy's sidebar menu, choose a model, then allow replies", position = 0)
    String GENERAL = "chatgpt";

    @ConfigSection(name = "Your adventurer", description = "Choose the live account details Buddy may use in advice", position = 1)
    String PRIVACY = "privacy";

    @ConfigSection(name = "Knowledge sources", description = "Choose external sources for game knowledge and account history", position = 2, closedByDefault = true)
    String KNOWLEDGE = "knowledge";

    @ConfigSection(name = "Conversation", description = "Personalize advice, keep chats and set a shortcut", position = 3, closedByDefault = true)
    String CONVERSATION = "conversation";

    @ConfigSection(name = "Reply delivery", description = "Control chatbox copies and reply alerts", position = 4, closedByDefault = true)
    String GAME_CHAT = "chat";

    @ConfigItem(keyName = "rememberChatgpt", name = "Keep ChatGPT connected", section = GENERAL, position = 2,
        description = "Remember your authorized ChatGPT session across restarts using encryption tied to your Windows account. On by default. Turning this off removes the saved session but keeps the current connection until Buddy closes. Other operating systems currently use session-only sign-in.",
        warning = "Stores your ChatGPT authorization locally, encrypted with Windows account protection. Anyone able to run software as your Windows user may access that session. Sign out from Buddy's menu to disconnect and clear the saved session.")
    default boolean rememberChatgpt() { return true; }

	@ConfigItem(
		keyName = "aiRequests",
		name = "Allow Buddy to answer",
		description = "Allow questions, model discovery and permitted look-ups using your ChatGPT plan. Connect through the sidebar menu first. Keep ChatGPT connected remembers sign-in on Windows by default.",
		warning = "This feature submits your IP address and the messages you type to OpenAI, and (with \"Allow OSRS Wiki searches\", on by default) the search words and page titles the assistant looks up to the OSRS Wiki: 3rd-party servers not controlled or verified by RuneLite developers.",
		section = GENERAL,
		position = 0
	)
	default boolean aiRequests()
	{
		return false;
	}

	@ConfigItem(
		keyName = "instructions",
		name = "Advice preferences",
		description = "Added to what the assistant is told before every chat, for example \"I'm an ironman\" or \"Answer in Dutch\".",
		section = CONVERSATION,
		position = 0
	)
	default String instructions()
	{
		return "";
	}

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
		section = GENERAL,
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
		keyName = "rememberChats",
		name = "Keep conversations on this PC",
		description = "Keep your chats when RuneLite closes, saved on this computer in .runelite/plugin-data/osrs-ai-buddy (the latest 200 messages of each, and older ones still sent to the assistant). Turning this off deletes the saved copy; chats then last only while RuneLite is open.",
		section = CONVERSATION,
		position = 1
	)
	default boolean rememberChats()
	{
		return true;
	}

	@ConfigItem(
		keyName = "notifyOnDone",
		name = "Reply alerts",
		description = "Notification when a reply arrives. RuneLite skips notifications while the game is focused unless you enable that here.",
		section = GAME_CHAT,
		position = 2
	)
	default Notification notifyOnDone()
	{
		return Notification.ON;
	}

	@ConfigItem(
		keyName = "echoToChat",
		name = "Mirror replies to game chat",
		description = "Print replies in the chatbox, not only in the side panel.",
		section = GAME_CHAT,
		position = 0
	)
	default boolean echoToChat()
	{
		return true;
	}

	@ConfigItem(
		keyName = "echoMaxChars",
		name = "Chatbox character limit",
		description = "Longer replies are cut short in the chatbox; the side panel always has the full text.",
		section = GAME_CHAT,
		position = 1
	)
	default int echoMaxChars()
	{
		return 500;
	}

	@ConfigItem(
		keyName = "askHotkey",
		name = "Ask Buddy shortcut",
		description = "Opens an 'Ask:' prompt in the chatbox.",
		section = CONVERSATION,
		position = 2
	)
	default Keybind askHotkey()
	{
		return Keybind.NOT_SET;
	}
}
