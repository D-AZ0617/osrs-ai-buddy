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
    static final String SPONSORS = "https://github.com/sponsors/D-AZ0617";
    private final Callable<Filepath> directory;
    private final URI artifact;
    private final Consumer<String> open;
    private final Consumer<String> browse;

    BuddyQuickAccess(Callable<Filepath> directory, URI artifact, Consumer<String> open, Consumer<String> browse)
    { this.directory = directory; this.artifact = artifact; this.open = open; this.browse = browse; }

    void open(String action) throws Exception
    {
        if ("github".equals(action)) { browse.accept(REPOSITORY); return; }
        if ("sponsors".equals(action)) { browse.accept(SPONSORS); return; }
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
        if (!"conversations".equals(action) && !"information".equals(action))
        { throw new IllegalArgumentException("Unknown quick-access action"); }
        Filepath folder = directory.call(); folder.createDirectories();
        if ("conversations".equals(action))
        {
            Filepath file = folder.joinSegment(ChatStore.FILE_NAME);
            if (!file.isFile()) { throw new IOException("There are no saved conversations yet. Send a message first, then try again."); }
            Filepath transcripts = folder.joinSegment(ChatTranscripts.FOLDER);
            if (!transcripts.isDirectory()) { throw new IOException("Readable conversations are not ready yet. Send a message in Buddy, then try again."); }
            open.accept(transcripts.toString()); return;
        }
        Filepath information = folder.joinSegment("buddy-information.txt");
        // This is an app-generated guide: refresh it so existing installations receive updated help.
        try (java.io.InputStream guide = BuddyQuickAccess.class.getResourceAsStream("buddy-information.txt"))
        {
            if (guide == null) { throw new IOException("The Getting Started guide is missing from this build."); }
            information.write(guide.readAllBytes());
        }
        open.accept(information.toString());
    }
}
