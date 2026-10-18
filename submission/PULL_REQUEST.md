## OSRS AI Buddy

Adds a RuneScape-focused chat companion with ChatGPT browser sign-in, OSRS Wiki searches, RuneLite GE prices, and separately opted-in live account context and read-only Wise Old Man history.

**Requires an active PAID ChatGPT subscription. The Free plan is not supported by Buddy.** OpenAI controls eligibility and shared plan/app limits. Usage/credit errors link to ChatGPT Usage settings; no API-key or separately billed API fallback exists. The plugin itself is free; an optional Buy Me A Coffee footer opens the maintainer's GitHub Sponsors page.

This is a disclosed derivative of [Brendan Ciccone's AI Chat](https://github.com/brendanciccone/osrs-ai-chat), with its BSD copyright/license retained. It has distinct branding, package, configuration and storage. The separate project focuses on ChatGPT plan OAuth instead of upstream's API-key/multi-provider setup, adds WOM and account-progress tools, and reworks conversation/settings UI. LICENSE and NOTICE are bundled under src/main/resources/META-INF for the standard Hub build.

### Review-sensitive behavior

- Explicit Sign In opens OpenAI in the system browser and temporarily listens on 127.0.0.1 for OAuth callbacks. State, nonce, PKCE and RS256 ID-token verification are used. No Jagex credentials are requested by the plugin.
- Windows sessions are remembered using current-user DPAPI through RuneLite's existing JNA runtime. Registration/session data uses scoped Filepath storage, a process lock and atomic replacement; there is no plaintext token fallback. Non-Windows sessions remain memory-only with a notice. A dedicated daemon worker performs blocking auth/storage work.
- User messages/history and opted-in context go to OpenAI. Wiki queries go to the OSRS Wiki. WOM requests are public GETs for the logged-in player only, separately opted in; no tracking creation, profile update or progress upload occurs. Requests have bounds, cache/account isolation and cancellation gates.
- Account sharing defaults off. Item-level collection coverage is partial and passively observed; unknown pages/items are not reported as missing. No gameplay actions, process launches, remote code loading or custom backend are involved.
- Local history uses JSON for restoration and generated Markdown transcripts for reading. Getting Started links to the online guide; local-file and folder-opening shortcuts have been removed. GitHub and Sponsors links are also user-clicked. Credential records are not exposed by shortcuts.
- Model selection defaults to Luna (Recommended), with account-listed mini, Sol and Astra options. Topic instructions restrict answers to RuneScape, math and plugin help, with a brief off-topic redirect; this is not represented as a guaranteed filter.

### Validation

386 automated tests passed on the official RuneLite launcher Java 11.0.30 runtime; production bytecode targets Java 11. Tests use local stand-ins and synthetic credentials. The user verified in-game developer-client login, ChatGPT replies and live account/WOM tools. These checks do not replace Hub review, particularly for OAuth callbacks and Windows native session protection.

The manifest includes the third-party data-submission warning. No credentials, private chats, game-login files or personal configuration are included in the submitted repository.

Generated-by: OpenAI Codex

Wiki integration retained: deterministic article/contributor/license credits, persistent source records through saved chats and summaries, bounded five-minute memory cache and shared Retry-After/maxlag cooldown. These changes do not claim that the Wiki has approved paid-provider use or sponsorship; policy clarification remains outstanding.