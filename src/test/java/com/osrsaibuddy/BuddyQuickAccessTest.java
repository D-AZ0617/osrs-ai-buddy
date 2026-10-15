package com.osrsaibuddy;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.runelite.client.util.Filepath;
import org.junit.Test;
import static org.junit.Assert.*;

public class BuddyQuickAccessTest
{
    private static Filepath scoped(Path folder) throws Exception
    {
        java.lang.reflect.Constructor<Filepath> constructor = Filepath.class.getDeclaredConstructor(Path.class, Path.class);
        constructor.setAccessible(true); return constructor.newInstance(folder, folder);
    }
    @Test public void conversationAndInformationShortcutsStayInPluginData() throws Exception
    {
        Path folder = Files.createTempDirectory("buddy-shortcuts");
        Files.writeString(folder.resolve("chats.json"), "{}");
        List<String> opened = new ArrayList<>();
        BuddyQuickAccess access = new BuddyQuickAccess(() -> scoped(folder), null, opened::add, ignored -> fail());
        access.open("conversations"); access.open("information"); access.open("data");
        assertEquals(folder.resolve("chats.json").toString(), opened.get(0));
        assertEquals(folder.resolve("buddy-information.txt").toString(), opened.get(1));
        assertEquals(folder.toString(), opened.get(2));
        assertTrue(Files.readString(folder.resolve("buddy-information.txt")).contains("Do not share the chatgpt subfolder"));
    }
    @Test public void pluginShortcutOpensFolderAndNeverExecutesJar() throws Exception
    {
        Path folder = Files.createTempDirectory("buddy-plugin-location");
        List<String> opened = new ArrayList<>();
        new BuddyQuickAccess(() -> scoped(folder), folder.resolve("buddy.jar").toUri(), opened::add, ignored -> fail()).open("plugin");
        assertEquals(folder.toString(), opened.get(0));
    }
    @Test public void repositoryIsFixedAndCoffeeDoesNothing() throws Exception
    {
        List<String> links = new ArrayList<>();
        BuddyQuickAccess access = new BuddyQuickAccess(() -> { throw new AssertionError(); }, null, ignored -> fail(), links::add);
        access.open("coffee"); assertTrue(links.isEmpty());
        access.open("github"); assertEquals(java.util.Collections.singletonList(BuddyQuickAccess.REPOSITORY), links);
    }
    @Test(expected = IllegalArgumentException.class) public void arbitraryPathsAndCredentialShortcutsAreRejected() throws Exception
    { new BuddyQuickAccess(() -> { throw new AssertionError(); }, null, ignored -> fail(), ignored -> fail()).open("chatgpt/registration.json"); }
    @Test public void missingConversationFileIsNotInvented() throws Exception
    {
        Path folder = Files.createTempDirectory("buddy-no-chats");
        BuddyQuickAccess access = new BuddyQuickAccess(() -> scoped(folder), null, ignored -> fail(), ignored -> fail());
        try { access.open("conversations"); fail(); } catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("no saved conversations")); }
        assertFalse(Files.exists(folder.resolve("chats.json")));
    }
}
