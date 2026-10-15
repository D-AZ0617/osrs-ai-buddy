# Privacy and connection details

## Connection and sharing

Character sharing, item sharing, extended progress sharing and WOM sharing start disabled. Sign In explicitly authorizes the ChatGPT connection; there is no separate master request checkbox. Loading a saved sign-in restores it locally, and a connected client can refresh the model list and renew its token when needed. Questions are sent when you submit a message. Signing out or disabling Buddy cancels requests.

## OpenAI

Sign-in opens auth.openai.com in your system browser and requests identity and ChatGPT plan-use permissions. A temporary callback listener binds only to IPv4 127.0.0.1, validates random state, and closes after completion or a three-minute timeout. The plugin uses PKCE and validates the returned ID token's signature, issuer, audience, expiry and nonce. It retains your account/client registration and shows connection status in the menu. This initial version supports one saved account/workspace registration.

When you send a question, your text, relevant chat history or its summary, system instructions, and any permitted look-up results are sent directly to api.openai.com using your ChatGPT authorization. Long-chat summaries also consume plan usage. Requests never pass through a project-operated server. Your IP address is visible to each service you contact.

## Optional game information

Share character overview permits character name, account type, levels, quest progress and, when requested, Slayer and achievement-diary data. Share bank, inventory & gear permits equipment, inventory and the last observed bank contents with prices. Both settings start off. Each look-up is listed with the corresponding reply. Character and item access require being logged in to the game. No game-login credentials are read or transmitted by this plugin.

## Extended progress and Wise Old Man

**Share achievements & collections** permits on-demand skills/XP, quest states, combat achievements, collection totals/section flags and observed obtained items, prayer/Slayer unlocks, and selected currencies to go to OpenAI. Data is read on the client thread. Collection items are passively observed only while Buddy and extended sharing are enabled, kept in memory, and cleared on account transitions, opt-out or shutdown. Validated opened-page snapshots include obtained and missing items, quantities, page names and observation times. Counts must agree with the grid before accepting a page; house adventure-log views are excluded. Collection coverage is partial; unvisited items remain unknown. No game input or UI navigation is generated to collect it.

**Use Wise Old Man history** permits your current logged-in character name and IP address to go to api.wiseoldman.net, and results to OpenAI as context. Available data includes public skill/boss/activity statistics, XP gains, achievements/progress, records, public competition/group membership and name changes. No other player can be selected by a model tool. Calls are read-only GET requests: Buddy never creates tracking, refreshes a profile or uploads your local progress to WOM. WOM data may already be public and may be stale, missing or unranked. WOM's privacy policy applies.

WOM requests use no OpenAI authorization, cookies or API keys; redirects are disabled. Responses are bounded and cached in memory for up to two minutes (32 entries maximum). Uncached requests are spaced at least 3.1 seconds apart; rate-limit responses impose a one-minute cooldown. Turning WOM sharing off, signing out, account transitions or shutdown cancels in-flight calls and clears the cache. Queued calls and result delivery recheck account identity and opt-in. Neither game progress nor WOM responses are independently written to disk, but information already used in saved chats remains in chat history. Turning sharing off cannot retract data already sent or remove it from existing chat messages; start a fresh chat to exclude prior shared context.

## OSRS Wiki

With Allow OSRS Wiki searches enabled, model-generated search words and page titles go to oldschool.runescape.wiki. Those words can reflect information you included or explicitly shared. Wiki results return to OpenAI as question context. Grand Exchange price look-ups use RuneLite's existing price data.

## Local storage and remembered sign-in

Data lives under .runelite/plugin-data/osrs-ai-buddy. Conversations are always saved automatically in chats.json, with no history toggle. Use Clear or Delete in Buddy to remove conversation contents/history; do not edit the file while RuneLite is using it. The chatgpt subdirectory stores registration.json: the persistent random host ID, non-secret account/client registration metadata (including the connected account label), and, by default on Windows, an encrypted session payload. Data reads and writes go through RuneLite’s scoped Filepath API. User-clicked Quick access buttons can open the conversations file, plugin data folder, installed plugin folder and a local information guide in the operating system. The plugin-folder action opens the JAR’s containing directory rather than executing it. No shortcut directly opens credential records. The GitHub action opens the public repository in the browser; Buy me a coffee remains disabled and makes no request. Atomic replacement and a process lock prevent concurrent registration writers.

ChatGPT sign-in is remembered automatically, without a separate checkbox. On Windows, access, refresh and retained ID tokens are encrypted with current-user Windows DPAPI before any write, using RuneLite’s existing JNA runtime. Machine-wide protection is never used, and there is no plaintext fallback or locally stored encryption key. The session is restored on plugin startup; expiring access tokens are refreshed automatically when needed, with replacement credentials saved atomically. A process lock and serialized refresh prevent token-rotation races. Credentials never enter RuneLite settings, profile sync, repository files or logs. Other programs running as your Windows user may access the session; Windows account protection does not isolate programs running as that same user.

Signing out removes saved tokens even if remote revocation fails. Corrupt or inaccessible protected data requires reconnection rather than a plaintext fallback. Expiry or revocation of the renewable session can still require sign-in. On other operating systems, persistent protection is not implemented yet: sign-in remains memory-only and a notice explains this limitation. Returning sign-in reuses the same registration and host ID. Sign out cancels requests, attempts remote revocation, and clears local credentials while retaining non-secret registration metadata. If revocation cannot be confirmed, disconnect the app in ChatGPT settings.

OSRS AI Buddy uses its own storage and does not migrate credentials or account data from AI Chat. OpenAI's and the Wiki's privacy policies apply to their services.
