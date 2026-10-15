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
- Keeps sharing choices separate and off by default.

## Connect ChatGPT

1. Open **OSRS AI Buddy** in the sidebar and select **⋯ → Continue with ChatGPT**.
2. Sign in on OpenAI's website, choose your account/workspace, and review the requested ChatGPT plan access. Eligibility and limits are controlled by OpenAI; an eligible plan is required.
3. Turn on **Enable AI requests** in OSRS AI Buddy settings when you are ready to send questions.
4. Choose an available model next to Send; use Refresh list if needed.
5. Ask a question. Sign in again after restarting RuneLite; tokens are kept in memory only. Enable Share character details only if you want personalized advice based on account progress.

There is no API-key field, alternate-provider selector, custom endpoint, Claude integration, or local-model setup. Buddy never switches requests to separately billed API-key usage. Review credit and plan permissions on OpenAI's consent screen; **Manage ChatGPT usage** opens your usage settings.

## Settings

- **ChatGPT:** enable requests, optional custom instructions, and Wiki look-ups. Choose the model directly in the chat panel.
- **Data & privacy:** share character details, share items and gear, and save chat history.
- **Notifications & game chat:** reply notifications, chatbox echoes, echo length, and an optional Ask hotkey.

See [PRIVACY.md](PRIVACY.md) for what leaves your PC and how sign-in is stored.

## Build and development

Use JDK 11, 17 or 21. On Windows run `gradlew.bat build`; on macOS/Linux run `./gradlew build`. The build compiles Java 11 bytecode. `gradlew.bat run` starts RuneLite's developer client. Automated tests use local stand-ins and do not contact OpenAI or the Wiki.

The regular RuneLite/Jagex Launcher will load this plugin only after a Plugin Hub release; adding --developer-mode to the official launcher does not enable custom plugin loading in RuneLite 1.13.2.

## Credits

Derived from Brendan Ciccone's BSD-licensed AI Chat plugin. See [NOTICE.md](NOTICE.md) and [LICENSE](LICENSE). This is an independent project with its own configuration group, Java package and data directory. It does not import AI Chat settings, API keys, chats or saved sign-ins.

