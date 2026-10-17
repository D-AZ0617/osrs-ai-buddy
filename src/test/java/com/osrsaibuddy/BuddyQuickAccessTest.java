package com.osrsaibuddy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class BuddyQuickAccessTest
{
    @Test public void onlyFixedPublicHttpsDestinationsAreOpened()
    {
        List<String> links = new ArrayList<>();
        BuddyQuickAccess access = new BuddyQuickAccess(links::add);
        access.open("information"); access.open("github"); access.open("sponsors");
        assertEquals(Arrays.asList(BuddyQuickAccess.GUIDE, BuddyQuickAccess.REPOSITORY, BuddyQuickAccess.SPONSORS), links);
        assertTrue(links.stream().allMatch(url -> url.startsWith("https://github.com/")));
    }
    @Test public void localPathsAndRemovedActionsNeverReachTheBrowser()
    {
        BuddyQuickAccess access = new BuddyQuickAccess(ignored -> fail("Invalid action reached browser"));
        for (String action : Arrays.asList("conversations", "plugin", "data", "file:///C:/", "C:/", "chatgpt/registration.json"))
        {
            try { access.open(action); fail("Accepted " + action); }
            catch (IllegalArgumentException expected) { }
        }
    }
}
