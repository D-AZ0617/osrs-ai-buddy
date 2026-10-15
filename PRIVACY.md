# Privacy and connection details

## Off by default

AI requests, character sharing, and item sharing start disabled. Signing in requires an explicit Continue with ChatGPT action. Loading an existing sign-in at startup only reads local storage. Model discovery and inference require Enable AI requests. Turning requests off cancels outstanding requests.

## OpenAI

Sign-in opens auth.openai.com in your system browser and requests identity and ChatGPT plan-use permissions. A temporary callback listener binds only to IPv4 127.0.0.1, validates random state, and closes after completion or a three-minute timeout. The plugin uses PKCE and validates the returned ID token's signature, issuer, audience, expiry and nonce. It retains your account/client registration and displays the connected account in the menu. This initial version supports one saved account/workspace registration.

When you send a question, your text, relevant chat history or its summary, system instructions, and any permitted look-up results are sent directly to api.openai.com using your ChatGPT authorization. Long-chat summaries also consume plan usage. Requests never pass through a project-operated server. Your IP address is visible to each service you contact.

## Optional game information

Share character details permits character name, account type, levels, quest progress and, when requested, Slayer and achievement-diary data. Share items and gear permits equipment, inventory and the last observed bank contents with prices. Both settings start off. Each look-up is listed with the corresponding reply. Character and item access require being logged in to the game. No game-login credentials are read or transmitted by this plugin.

## OSRS Wiki

With Wiki look-ups enabled, model-generated search words and page titles go to oldschool.runescape.wiki. Those words can reflect information you included or explicitly shared. Wiki results return to OpenAI as question context. Grand Exchange price look-ups use RuneLite's existing price data.

## Local storage and sign-in lifetime

Data lives under .runelite/plugin-data/osrs-ai-buddy. Chats use chats.json; turning Save chat history off deletes the saved chat copy. The chatgpt subdirectory stores only the persistent random host ID and non-secret account/client registration metadata (including the connected account label). All file access goes through RuneLite's scoped Filepath API. Atomic replacement and a process lock prevent concurrent registration writers.

Access, refresh and ID tokens remain in memory and are never saved in a file, RuneLite settings, profile sync or logs. You sign in again after restarting RuneLite or re-enabling the plugin. Returning sign-in reuses the same registration and host ID. Tokens can renew during the current session. Sign out cancels requests, disables AI requests, attempts remote revocation, and clears local credentials while retaining non-secret registration metadata. If revocation cannot be confirmed, disconnect the app in ChatGPT settings.

OSRS AI Buddy uses its own storage and does not migrate credentials or account data from AI Chat. OpenAI's and the Wiki's privacy policies apply to their services.
