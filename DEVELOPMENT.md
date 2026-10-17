# Development

Build: gradlew.bat build (Windows), ./gradlew build (macOS/Linux). Use JDK 11, 17 or 21; production classes target Java 11. Run: gradlew.bat run.

Main class: com.osrsaibuddy.OsrsAiBuddyPluginTest. Plugin class: com.osrsaibuddy.OsrsAiBuddyPlugin. Configuration group and data-directory name: osrs-ai-buddy.

Tests cover OAuth callbacks, identity verification, Windows-protected persistent sessions, opt-out, corrupt-session recovery and scoped registration storage, renewal/revocation, incomplete and headerless streams, final output items, namespaced tools, sharing gates, chat persistence, chat UI and command parsing. All automated tests use loopback stand-ins. Removed-provider tests were removed along with those implementations; ChatGPT-only configuration has its own tests.

Sign-in follows the official OpenAI documentation:
- https://developers.openai.com/siwc/token-sharing-open-source/sign-in
- https://developers.openai.com/siwc/token-sharing-open-source/profiles-and-sessions
- https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference

Responses use the public endpoint, store:false and stream:true. Never configure a backend-api endpoint or use another app's client ID. Model ordering follows the signed-in account's catalog. A completed response is required even when output items arrive separately.

Do not commit credential files, personal settings, game login credentials, chats, build logs from signed-in clients, or screenshots containing account information. Keep the original BSD attribution.


Account context: AccountContextTools offers opt-in paginated progress and WOM tools. AccountProgress reads only on the client thread and passively observes collection transmission; AccountFlags is an explicit RuneLite 1.13.2 allowlist. Update the allowlist against generated game constants after game changes. WomClient is GET-only, fixed-host, bounded, rate spaced and briefly cached. A generation check rejects queued requests after account changes/opt-out; identity and settings are checked again at delivery. Test both gates and incomplete-data semantics before extending any dataset.

Remembered ChatGPT sign-in defaults on. SessionProtection uses current-user DPAPI via RuneLite’s existing JNA runtime (UI forbidden; never machine-wide). ChatGptRegistrationStore atomically saves non-secret metadata and an encrypted allowlisted session together, binds decrypted credentials to their registration metadata, and rejects legacy plaintext token fields. Refreshes stay serialized under the registration lock. On non-Windows systems, session-only behavior remains with an explicit notice. Do not introduce plaintext fallback storage.

UI simplification: the inbox is the default startup view, with dated conversation cards and bounded plain-text previews. Setup banners, the chat selector dropdown, custom instructions, hotkey and reply echoes are removed. Requests are allowed after explicit ChatGPT sign-in, with no master request option. Session persistence is always enabled on supported Windows storage. The Configurations menu now opens BuddySettingsPanel inside Buddy because RuneLite’s generated config panel has no action-button type. Its fixed setting allowlist reads/writes the same ConfigManager keys, applies sharing warnings on opt-in. Reply alerts and the Notifier dependency are removed. Quick access uses scoped Filepath for plugin data and RuneLite LinkBrowser.open for explicit user file-opening actions. Artifact location is obtained from this plugin’s CodeSource; JARs are never executed. Conversation saving is hardcoded on. A single Buy Me A Coffee footer links to the maintainer GitHub Sponsors page. Unsent drafts are transient and repeated plus clicks reuse them; deletion returns to the inbox. BuddyModels filters the account model list to general-purpose GPT-6 Luna/Sol/Astra and GPT-5 mini IDs, defaults to Luna, and blocks absent selections at ProviderSetup before sending. The production picker has no custom-name entry. No reflection or game input is used. Include submission/WARNING.txt in the eventual Hub manifest.

ChatTranscripts derives readable Markdown files from the saved DTO under the same ChatFile writer lock. Existing JSON history loads unchanged; JSON is pretty printed on save. Generated transcript filenames use name-based UUIDs rather than titles/paths, and cleanup only targets generated filenames. The restore JSON remains authoritative. License and attribution are included in META-INF in built JARs.