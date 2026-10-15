<img src="assets/osrs-ai-buddy-master.png" width="112" alt="OSRS AI Buddy: a smiling adventurer in a magical chat bubble">

# OSRS AI Buddy

A friendly Old School RuneScape companion inside RuneLite. Ask what to train next, plan a quest, look up a boss mechanic, or get help choosing your next goal—using your authorized ChatGPT plan.

**Publication status:** submission candidate. This plugin has not yet been accepted into the RuneLite Plugin Hub.

## What Buddy does

- Chats in a RuneLite sidebar, with streaming replies, multiple conversations, retry and copy.
- Accepts `::buddy <question>` in the chatbox; the command is intercepted locally and is not sent to game chat.
- Searches and reads the OSRS Wiki, and checks Grand Exchange prices.
- Optionally uses your character levels, quest progress, Slayer task and diaries for more useful advice.
- Optionally looks at equipment, inventory and the last bank snapshot captured while sharing was enabled.
- Adds opt-in Wise Old Man context: latest tracked skills/XP/ranks, boss kills, activities and clues, EHP/EHB, gains, records, achievements and their progress, public groups, competitions and name history.
- Adds live extended progress: boosted/base levels and XP, every exposed quest state, 399 known combat-achievement flags, 125 collection-section completion flags, 37 prayer/Slayer unlocks, and selected currencies.
- Passively captures obtained and missing items, quantities and page names from validated collection-log pages you open, plus obtained items the game supplies. Coverage remains partial: unvisited items are unknown.
- Keeps sharing choices separate and off by default.

## Connect ChatGPT

1. Open **OSRS AI Buddy** in the sidebar and select **⋯ → Continue with ChatGPT**.
2. Sign in on OpenAI's website, choose your account/workspace, and review the requested ChatGPT plan access. Eligibility and limits are controlled by OpenAI; an eligible plan is required.
3. Turn on **Allow Buddy to answer** in OSRS AI Buddy settings when you are ready to send questions.
4. Choose an available model next to Send; use Refresh list if needed.
5. Ask a question. Sign in again after restarting RuneLite; tokens are kept in memory only. Enable Share character overview only if you want personalized advice based on account progress.

There is no API-key field, alternate-provider selector, custom endpoint, Claude integration, or local-model setup. Buddy never switches requests to separately billed API-key usage. Review credit and plan permissions on OpenAI's consent screen; **Manage ChatGPT usage** opens your usage settings.

## Settings

- **Getting started:** allow Buddy to answer; connect and choose a model in the sidebar.
- **Your adventurer:** share a character overview, achievements/collections, or bank/inventory/gear separately.
- **Knowledge sources:** OSRS Wiki searches and opt-in Wise Old Man history.
- **Conversation:** advice preferences, local chat history and the Ask Buddy shortcut.
- **Reply delivery:** game-chat copies, character limit and reply alerts.

See [PRIVACY.md](PRIVACY.md) for what leaves your PC and how sign-in is stored.

## Build and development

Use JDK 11, 17 or 21. On Windows run `gradlew.bat build`; on macOS/Linux run `./gradlew build`. The build compiles Java 11 bytecode. `gradlew.bat run` starts RuneLite's developer client. Automated tests use local stand-ins and do not contact OpenAI, the Wiki or WOM.

The regular RuneLite/Jagex Launcher will load this plugin only after a Plugin Hub release; adding --developer-mode to the official launcher does not enable custom plugin loading in RuneLite 1.13.2.

## Account context and limits

Enable **Share achievements & collections** for current RuneLite progress and **Use Wise Old Man history** for historical statistics. Existing character and item sharing are separate. Ask “Use my account progress and WOM profile to suggest my next three goals.” Buddy retrieves relevant sections on demand; it does not insert the entire account into every question.

WOM requires an existing tracked player. Buddy only performs public GET requests; it does not create a profile, submit updates, or enable public tracking. A missing profile or unranked value is unknown, not zero. WOM data may lag behind the live game. Requests are cached for two minutes and limited to one uncached request per 3.1 seconds; additional requests during that interval report that they need to wait. There is no API key or paid service setup.

Collection-log totals and section flags come from game variables. Item details include validated snapshots of collection-log pages you browse (obtained/missing status, quantities and page names), and obtained items naturally transmitted by the game. Header counts must agree with the item grid before a page is accepted. House adventure-log views are excluded to avoid capturing another player’s log. Items not observed are never assumed missing. Observations stay in memory and clear on account transitions, disabling sharing, or plugin shutdown. No automatic UI navigation or game scripts are triggered to harvest the log. Internal task identifiers and versioned allowlists may need updating after game changes. Bank contents require opening the bank with item sharing enabled. Private storage, full account history and other unexposed progress are not guaranteed.

## Credits

Derived from Brendan Ciccone's BSD-licensed AI Chat plugin. See [NOTICE.md](NOTICE.md) and [LICENSE](LICENSE). This is an independent project with its own configuration group, Java package and data directory. It does not import AI Chat settings, API keys, chats or saved sign-ins.

