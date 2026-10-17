package com.osrsaibuddy;

import com.google.gson.Gson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import net.runelite.client.util.Filepath;
import org.junit.Test;
import static org.junit.Assert.*;

public class ChatTranscriptsTest
{
    @Test public void readableCopiesTrackRenameClearAndDeleteWithoutTouchingUserFiles() throws Exception
    {
        Path root = Files.createTempDirectory("buddy-transcripts");
        java.lang.reflect.Constructor<Filepath> constructor = Filepath.class.getDeclaredConstructor(Path.class, Path.class);
        constructor.setAccessible(true); Filepath scoped = constructor.newInstance(root, root);
        Chat chat = new Chat("../../A [chat]");
        chat.messages.add(new Chat.Message(Chat.Role.USER, "How much XP?"));
        chat.messages.add(new Chat.Message(Chat.Role.ASSISTANT, "**125 XP**\n\nMore detail."));
        Gson gson = new Gson();
        ChatTranscripts.sync(scoped, gson, ChatStore.toJson(gson, Arrays.asList(chat), chat));
        Path folder = root.resolve("conversations");
        List<Path> paths;
        try (java.util.stream.Stream<Path> files = Files.list(folder)) { paths = files.filter(p -> p.getFileName().toString().startsWith("buddy-")).collect(Collectors.toList()); }
        assertEquals(1, paths.size()); Path transcript = paths.get(0);
        String text = Files.readString(transcript);
        assertTrue(text.contains("## You")); assertTrue(text.contains("## Buddy"));
        assertTrue(text.contains("**125 XP**\n\nMore detail."));
        chat.name = "Renamed"; chat.messages.clear();
        ChatTranscripts.sync(scoped, gson, ChatStore.toJson(gson, Arrays.asList(chat), chat));
        assertTrue(Files.readString(transcript).startsWith("# Renamed"));
        assertFalse(Files.readString(transcript).contains("125 XP"));
        Files.writeString(folder.resolve("personal-notes.md"), "keep");
        ChatTranscripts.sync(scoped, gson, ChatStore.toJson(gson, Collections.emptyList(), null));
        assertFalse(Files.exists(transcript)); assertEquals("keep", Files.readString(folder.resolve("personal-notes.md")));
    }
}
