package com.osrsaibuddy;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class BuddyModelsTest
{
    @Test public void onlyGeneralPurposeSmallModelsAreOffered()
    {
        assertEquals(Arrays.asList("gpt-6-luna", "gpt-5.4-mini"), BuddyModels.choices(Arrays.asList(
            "gpt-6-astra", "gpt-6-sol", "gpt-5-pro", "auto", "gpt-6-luna", "gpt-5.4-mini",
            "gpt-5.4-mini", "gpt-5-mini-research", "gpt-5-codex-mini", "gpt-4o-mini-audio")));
    }

    @Test public void replacesSavedFlagshipButKeepsAnAvailableLightweightChoice()
    {
        java.util.List<String> offered = Arrays.asList("gpt-6-sol", "gpt-6-luna", "gpt-5-mini");
        assertEquals("gpt-6-luna", BuddyModels.selected("gpt-6-sol", offered));
        assertEquals("gpt-5-mini", BuddyModels.selected("gpt-5-mini", offered));
    }

    @Test public void neverInventsAvailabilityOrFallsBackToFlagship()
    {
        assertEquals("", BuddyModels.selected("gpt-6-luna", null));
        assertEquals("", BuddyModels.selected("gpt-6-astra", Arrays.asList("auto", "gpt-6-astra")));
        ProviderSetup setup = new ProviderSetup(new OsrsAiBuddyConfig() {
            @Override public String chatgptPlanModel() { return "gpt-6-astra"; }
        }, null);
        setup.availableModels = () -> Arrays.asList("gpt-6-astra", "gpt-6-luna");
        assertEquals("gpt-6-luna", setup.model());
    }
}
