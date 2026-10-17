**Requires an active PAID ChatGPT subscription. The ChatGPT Free plan is not supported by OSRS AI Buddy.** OpenAI controls account/workspace eligibility, model access and usage limits. Subscription usage and any additional ChatGPT credits are managed in [ChatGPT Settings - Usage](https://chatgpt.com/settings/usage); OpenAI API credits are separate and are not used by Buddy.

# Plugin Hub submission

Candidate: OSRS AI Buddy 1.0.0
Repository: https://github.com/D-AZ0617/osrs-ai-buddy
Plugin class: com.osrsaibuddy.OsrsAiBuddyPlugin
Configuration and storage namespace: osrs-ai-buddy
Packaging: standard (no extra production dependencies)
Icon: 48x48 root icon.png, 32x32 sidebar resource, PNG with transparency
Bytecode target: Java 11
Local verification: 382 tests passed on the official RuneLite launcher Java 11.0.30 runtime, zero failures/errors. This is a build/test result, not confirmation of in-game behavior.

The upstream multi-provider implementations and their tests were removed; ChatGPT-only configuration, scoped registration storage and Windows-protected persistent session behavior have new tests. Existing chat, privacy-gate, tool, UI and persistence tests remain.

The public source package contains no sign-in credentials, game login data, personal chat history, user configuration, build logs, or compiled JARs. Inherited BSD attribution is in LICENSE and NOTICE.md.

The Plugin Hub manifest needs a public repository URL and the exact pushed 40-character commit. Include the third-party-data warning. The candidate is not a Plugin Hub release until RuneLite reviews and merges its manifest and its build succeeds.

Review-sensitive points: temporary loopback HTTP callback, JWT signature verification, a dedicated daemon worker, and non-secret registration metadata written through Filepath. These are explicitly disclosed in submission/PULL_REQUEST.md. All account sharing defaults off. Installation must include the third-party-data warning supplied in submission/WARNING.txt. No gameplay actions are automated.

The Plugin Hub recommends contributing to existing plugins when appropriate. This separate plugin focuses specifically on ChatGPT plan sign-in and intentionally excludes upstream's API-key and alternate-provider configuration. Its relationship to upstream is disclosed, not hidden by the new branding.

WOM/account-context expansion: verified 352 tests with no failures, errors or skipped tests. Added tests cover opt-in gates before reads and delivery, account changes, in-flight and queued WOM cancellation, read-only endpoint encoding, cache isolation, missing/unranked data, redirects, response-size caps, rate cooldown, pagination and partial collection coverage. All network tests use local stand-ins; live WOM behavior and in-game collection coverage still need user testing. Hub submission remains paused.

Collection-page fix: item-level obtained/missing/quantity snapshots are accepted only when visible header totals agree with the complete item grid. Page names and observation timestamps support targeted queries; unvisited coverage is unknown. Another player’s house adventure-log view is excluded. User confirmed the earlier live RuneLite/WOM data tools work in game; this newer page-reader build still needs an in-game check.

Default remembered sign-in: Windows current-user DPAPI through the existing RuneLite JNA runtime, no custom native library or new production dependency. Tokens are encrypted before scoped atomic Filepath writes. Cross-instance restore, token rotation, opt-out removal, sign-out cleanup, corrupt encrypted data, plaintext rejection and encryption failures are covered by tests with synthetic credentials. Cross-platform secure-store backends are not implemented; non-Windows sessions remain memory-only with a notice. Review this native OS protection capability explicitly before Hub acceptance.
