package com.osrsaibuddy;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import net.runelite.client.util.Filepath;

/** Fixed, user-clicked destinations only. Never opens authentication records or executes plugin binaries. */
final class BuddyQuickAccess
{
    static final String REPOSITORY = "https://github.com/D-AZ0617/osrs-ai-buddy";
    private final Callable<Filepath> directory;
    private final URI artifact;
    private final Consumer<String> open;
    private final Consumer<String> browse;

    BuddyQuickAccess(Callable<Filepath> directory, URI artifact, Consumer<String> open, Consumer<String> browse)
    { this.directory = directory; this.artifact = artifact; this.open = open; this.browse = browse; }

    void open(String action) throws Exception
    {
        if ("github".equals(action)) { browse.accept(REPOSITORY); return; }
        if ("coffee".equals(action)) { return; }
        if ("plugin".equals(action))
        {
            if (artifact == null || !"file".equalsIgnoreCase(artifact.getScheme()))
            { throw new IOException("The installed plugin folder could not be located."); }
            Path path = Paths.get(artifact);
            // Opening a JAR itself might execute it. Always open its containing folder instead.
            if (path.getFileName() != null && path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".jar")) { path = path.getParent(); }
            if (path == null) { throw new IOException("The installed plugin folder could not be located."); }
            open.accept(path.toString()); return;
        }
        if (!"data".equals(action) && !"conversations".equals(action) && !"information".equals(action))
        { throw new IllegalArgumentException("Unknown quick-access action"); }
        Filepath folder = directory.call(); folder.createDirectories();
        if ("data".equals(action)) { open.accept(folder.toString()); return; }
        if ("conversations".equals(action))
        {
            Filepath file = folder.joinSegment(ChatStore.FILE_NAME);
            if (!file.isFile()) { throw new IOException("There are no saved conversations yet. Send a message first, then try again."); }
            open.accept(file.toString()); return;
        }
        Filepath information = folder.joinSegment("buddy-information.txt");
        if (!information.exists())
        {
            information.write("OSRS AI Buddy\n\n"
                + "Conversations are saved automatically in chats.json. This file can contain personal chat and account details.\n"
                + "Use Clear or Delete in Buddy to remove conversation history. Do not edit chats.json while RuneLite is running.\n\n"
                + "ChatGPT sign-in is stored separately, protected by your Windows account. Do not share the chatgpt subfolder. Use Log Out to clear that session.\n\n"
                + "Plugin data folder: this folder.\nInstalled plugin folder: contains the plugin JAR, or compiled classes in a developer build.\n\n"
                + "Documentation and source: " + REPOSITORY + "\n"
                + "Privacy: " + REPOSITORY + "/blob/main/PRIVACY.md\n"
                + "Support and updates: " + REPOSITORY + "/issues\n"
                + "Buy me a coffee: work in progress.\n");
        }
        open.accept(information.toString());
    }
}
