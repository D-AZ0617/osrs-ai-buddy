package com.osrsaibuddy;

import java.util.Arrays;
import net.runelite.client.config.ConfigItem;
import org.junit.Test;
import static org.junit.Assert.*;

public class ProviderSetupTest
{
    @Test public void requestsAndAccountSharingAreOffByDefault()
    {
        OsrsAiBuddyConfig config = new OsrsAiBuddyConfig() { };
        assertFalse(config.aiRequests()); assertFalse(config.sendCharacter()); assertFalse(config.shareItems());
        assertEquals("osrs-ai-buddy", OsrsAiBuddyConfig.GROUP);
        assertEquals("", config.chatgptPlanModel());
        ProviderSetup setup = new ProviderSetup(config, null);
        assertTrue(setup.connectionProblem().contains("Allow Buddy to answer"));
        assertNull(setup.api(null, null)); assertNull(setup.testApi(null, null));
    }
    @Test public void enablingRequestsDoesNotBypassSignIn()
    {
        OsrsAiBuddyConfig config = new OsrsAiBuddyConfig() { @Override public boolean aiRequests() { return true; } };
        ProviderSetup setup = new ProviderSetup(config, null);
        assertTrue(setup.problem().contains("Continue with ChatGPT"));
        assertNull(setup.api(null, null)); assertNull(setup.testApi(null, null));
        assertEquals("chatgptPlanModel", setup.modelKey()); assertEquals("ChatGPT", setup.service());
    }
    @Test public void onlyProjectRelevantSettingsAreExposed()
    {
        java.util.Set<String> expected = new java.util.HashSet<>(Arrays.asList("aiRequests", "instructions", "wikiLookups", "chatgptPlanModel", "sendCharacter", "shareItems", "rememberChats", "shareProgress", "shareWom", "notifyOnDone", "echoToChat", "echoMaxChars", "askHotkey"));
        java.util.Set<String> actual = new java.util.HashSet<>();
        for (java.lang.reflect.Method method : OsrsAiBuddyConfig.class.getDeclaredMethods())
        {
            ConfigItem item = method.getAnnotation(ConfigItem.class);
            if (item != null) { actual.add(item.keyName()); }
        }
        assertEquals(expected, actual);
    }
}

