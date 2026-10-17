package com.osrsaibuddy;

import java.util.function.Consumer;

/** Fixed public HTTPS links only; no local-file, folder or process-opening actions. */
final class BuddyQuickAccess
{
    static final String REPOSITORY = "https://github.com/D-AZ0617/osrs-ai-buddy";
    static final String SPONSORS = "https://github.com/sponsors/D-AZ0617";
    static final String GUIDE = REPOSITORY + "#connect-chatgpt";
    private final Consumer<String> browse;

    BuddyQuickAccess(Consumer<String> browse) { this.browse = browse; }

    void open(String action)
    {
        switch (action)
        {
            case "github": browse.accept(REPOSITORY); break;
            case "sponsors": browse.accept(SPONSORS); break;
            case "information": browse.accept(GUIDE); break;
            default: throw new IllegalArgumentException("Unknown website shortcut");
        }
    }
}
