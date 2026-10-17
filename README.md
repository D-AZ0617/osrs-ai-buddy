<img src="assets/osrs-ai-buddy-master.png" width="112" alt="OSRS AI Buddy: a smiling adventurer in a magical chat bubble">

# OSRS AI Buddy

A friendly Old School RuneScape companion inside RuneLite. Ask what to train next, plan a quest, look up a boss mechanic, or get help choosing your next goal—using your authorized ChatGPT plan.

**Publication status:** submission candidate. This plugin has not yet been accepted into the RuneLite Plugin Hub.

**Requires an active PAID ChatGPT subscription. The ChatGPT Free plan is not supported by OSRS AI Buddy.** OpenAI controls account/workspace eligibility, model access and usage limits. Subscription usage and any additional ChatGPT credits are managed in [ChatGPT Settings - Usage](https://chatgpt.com/settings/usage); OpenAI API credits are separate and are not used by Buddy.

## What Buddy does

- Opens to a conversation inbox with dated cards and recent-message previews. Chats support streaming replies, retry and copy.
- Accepts `::buddy <question>` in the chatbox; the command is intercepted locally and is not sent to game chat.
- Searches and reads the OSRS Wiki, and checks Grand Exchange prices.
- Optionally uses your character levels, quest progress, Slayer task and diaries for more useful advice.
- Optionally looks at equipment, inventory and the last bank snapshot captured while sharing was enabled.
- Adds opt-in Wise Old Man context: latest tracked skills/XP/ranks, boss kills, activities and clues, EHP/EHB, gains, records, achievements and their progress, public groups, competitions and name history.
- Adds live extended progress: boosted/base levels and XP, every exposed quest state, 399 known combat-achievement flags, 125 collection-section completion flags, 37 prayer/Slayer unlocks, and selected currencies.
- Passively captures obtained and missing items, quantities and page names from validated collection-log pages you open, plus obtained items the game supplies. Coverage remains partial: unvisited items are unknown.
- Keeps sharing choices separate and off by default.

## Connect ChatGPT

1. Open **OSRS AI Buddy** in the sidebar and select **⋯ → Sign In**.
2. Sign in on OpenAI's website, choose your account/workspace, and review the requested ChatGPT plan access. An active paid ChatGPT subscription is required; the Free plan is not supported. OpenAI controls eligibility and limits.
3. Once connected, Buddy is ready to answer; no separate enable-requests switch is needed.
4. Buddy selects an account-listed lightweight model, preferring Luna, then GPT-5 mini. Use Refresh list if needed. Luna is labeled (Recommended). Account-listed Astra and Sol models are available by explicit selection; custom model names are blocked.
5. Ask a question. On Windows, ChatGPT sign-in is remembered automatically: your encrypted session is restored after restarts. Sign in once again when moving from the older memory-only build. Enable Share character overview only if you want personalized advice based on account progress.

There is no API-key field, alternate-provider selector, custom endpoint, Claude integration, or local-model setup. Buddy never switches requests to separately billed API-key usage. Review credit and plan permissions on OpenAI's consent screen; usage limits can be managed in [ChatGPT settings](https://chatgpt.com/settings/usage).

Your session renews automatically when needed. Signing out clears the saved session. Access revocation or expiry of the renewable session can still require another sign-in. Persistent sign-in currently supports Windows; other operating systems retain session-only behavior.

## Settings

Open **⋯ → Configurations** for Buddy’s settings page. Its controls use the same saved preferences as RuneLite’s standard plugin settings.

- **Account context:** character overview, achievements/collections, and bank/inventory/gear, each opt-in.
- **Knowledge sources:** OSRS Wiki searches and opt-in Wise Old Man history.
- **Quick access:** Getting Started, Saved Conversations, Installed Plugin Folder, and GitHub Repository. The conversations screen has a single Buy Me A Coffee footer button linking to GitHub Sponsors; support is optional.

The chat menu contains Rename, Clear, Delete, Configurations, ChatGPT connection status, and Sign In or Log Out. Clicking a chat title returns to the conversation inbox. New conversations start with the plus button. Empty inboxes show a muted, recessed gray message. Replies stay in Buddy's panel; game-chat mirroring and the Ask shortcut are removed. ChatGPT sign-in is always remembered on Windows, with encrypted local storage. Conversations are always saved locally; the history checkbox has been removed. Use Clear or Delete to remove a conversation. Local shortcuts never directly open credential records or execute the plugin JAR.

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


Unsent plus-button drafts are not saved, repeated plus clicks reuse the draft, and deleting a chat returns to the inbox. Empty legacy Chat-number placeholders are removed without removing conversations containing messages. Getting Started refreshes the extensive local help guide, including a text mascot.

Lightweight selection reduces accidental flagship usage, but does not reserve quota or guarantee unlimited messages. Model availability and plan-wide limits remain controlled by ChatGPT. Sending waits if no supported lightweight model is listed.

Buddy is scoped to RuneScape, genuine math questions, and Buddy/RuneLite help. Unrelated requests receive a short RuneScape redirect, with no unrelated tool lookups. These are model instructions rather than a deterministic filter: refusals still use ChatGPT allowance and model adherence can vary.

Saved Conversations opens a folder of readable Markdown transcripts, one per chat, with an index, titles and timestamps. Buddy retains formatted chats.json internally for reliable restoration and compatibility. Generated transcripts reflect renames, clears and deletes; manual transcript edits are not imported. Reply alerts are removed from both settings views and disabled.

If submitting a message fails because of plan eligibility, usage limits or exhausted credits, Buddy explains the paid subscription requirement and links to ChatGPT Usage settings. Check the applicable plan or app limit, wait for an allowance reset, or add ChatGPT credits if offered. Temporary service/rate-limit and expired-session errors have separate troubleshooting; purchasing credits is not a universal fix.