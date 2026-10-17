## OSRS AI Buddy

**Requires an active PAID ChatGPT subscription. The ChatGPT Free plan is not supported by OSRS AI Buddy.** OpenAI controls account/workspace eligibility, model access and usage limits. Subscription usage and any additional ChatGPT credits are managed in [ChatGPT Settings - Usage](https://chatgpt.com/settings/usage); OpenAI API credits are separate and are not used by Buddy.

Adds a ChatGPT-plan-focused OSRS companion with browser sign-in, a chat sidebar, locally intercepted ::buddy questions, OSRS Wiki look-ups, GE prices, and opt-in character/item, extended account progress, and read-only Wise Old Man context. All account-sharing options default off. ChatGPT sign-in authorizes the connection; the redundant master request checkbox is removed.

This is a disclosed derivative of brendanciccone/osrs-ai-chat, not a clean-room implementation. It has a distinct package/config/storage namespace and icon. The original BSD license and attribution are preserved. Unlike the upstream multi-provider plugin, it supports ChatGPT plan OAuth only and has no API-key, Claude, compatible-endpoint or local-model configuration.

### Review-sensitive capabilities

- The explicit sign-in action starts a short-lived HTTP callback listener on 127.0.0.1 only. It uses fresh state, nonce and S256 PKCE, verifies RS256 ID tokens against OpenAI JWKS, and never uses game-login credentials.
- Remembered sign-in defaults on for Windows: OAuth access, refresh and ID tokens are protected with Windows current-user DPAPI through the existing RuneLite JNA runtime before storage. Non-secret registration metadata and the encrypted session use RuneLite Filepath in this plugin's scoped directory with atomic replacement and a file lock. No machine-wide encryption or plaintext fallback is used. On non-Windows systems the current implementation remains memory-only with an explicit notice. Plugin data reads/writes use scoped Filepath; user-clicked file-opening shortcuts pass a fixed scoped path or this plugin’s CodeSource folder to RuneLite LinkBrowser.open. No API keys are used.
- A dedicated daemon worker handles blocking auth/storage work; injected RuneLite HTTP/Gson objects are used, with redirects and response caching disabled.
- Only the configured OpenAI endpoints, OSRS Wiki and (with separate opt-in) api.wiseoldman.net receive requests. No project backend, remote code, process launching, or gameplay automation is involved. Optional game data is read on the client thread under separate opt-in gates.
- WOM performs public GET requests only, never creates tracking or updates a player. Its character-name/IP and retrieved account-data disclosure is explicit. Results are bounded, cached briefly and rate spaced; account switches and opt-out cancel/discard pending results. Collection-log data is passively observed and explicitly partial.
- This new OAuth callback and session capability needs explicit reviewer assessment; no claim of prior approval is made.

### Validation

Local build and test results are recorded in SUBMISSION.md. Tests use loopback stand-ins, not real accounts. A separate local developer build verified the ChatGPT flow before this project was rebranded; fresh sign-in under the new application name still requires user consent.

Validation: all 382 tests passed on the official RuneLite launcher's Java 11.0.30 runtime. Production classes target Java 11. Automated tests use loopback stand-ins.

Generated-by: OpenAI Codex


The Configurations menu opens Buddy’s own settings page, with ConfigManager-backed controls and opt-in sharing warnings. Conversation saving is always enabled. The footer has explicit user-clicked shortcuts for saved chats, plugin data, plugin artifact folder, a local guide and GitHub. It never directly opens credential records or executes the JAR. The donation button is disabled, has no destination or listener, and makes no request.
