# Development

Build: gradlew.bat build (Windows), ./gradlew build (macOS/Linux). Use JDK 11, 17 or 21; production classes target Java 11. Run: gradlew.bat run.

Main class: com.osrsaibuddy.OsrsAiBuddyPluginTest. Plugin class: com.osrsaibuddy.OsrsAiBuddyPlugin. Configuration group and data-directory name: osrs-ai-buddy.

Tests cover OAuth callbacks, identity verification, memory-only tokens and scoped registration storage, renewal/revocation, incomplete and headerless streams, final output items, namespaced tools, sharing gates, chat persistence, chat UI and command parsing. All automated tests use loopback stand-ins. Removed-provider tests were removed along with those implementations; ChatGPT-only configuration has its own tests.

Sign-in follows the official OpenAI documentation:
- https://developers.openai.com/siwc/token-sharing-open-source/sign-in
- https://developers.openai.com/siwc/token-sharing-open-source/profiles-and-sessions
- https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference

Responses use the public endpoint, store:false and stream:true. Never configure a backend-api endpoint or use another app's client ID. Model ordering follows the signed-in account's catalog. A completed response is required even when output items arrive separately.

Do not commit credential files, personal settings, game login credentials, chats, build logs from signed-in clients, or screenshots containing account information. Keep the original BSD attribution.

