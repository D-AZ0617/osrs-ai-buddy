package com.osrsaibuddy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.SwingUtilities;
import org.junit.Test;
import static org.junit.Assert.*;

public class BuddySettingsPanelTest
{
    private static final class Actions implements BuddySettingsPanel.Actions
    {
        final Map<String, Boolean> values = new HashMap<>(); final List<String> opened = new ArrayList<>();
        boolean permit;
        public boolean value(String key) { return values.getOrDefault(key, false); }
        public void change(String key, boolean value) { values.put(key, value); }
        public boolean confirm(String warning) { return permit; }
        public void open(String action) { opened.add(action); }
    }
    @Test public void noHistoryCheckboxAndCoffeeRemainsDisabled() throws Exception
    {
        SwingUtilities.invokeAndWait(() -> {
            Actions actions = new Actions(); BuddySettingsPanel panel = new BuddySettingsPanel(actions);
            assertFalse(panel.options.containsKey("rememberChats"));
            assertFalse(panel.shortcuts.get("coffee").isEnabled()); panel.shortcuts.get("coffee").doClick(); assertTrue(actions.opened.isEmpty());
            panel.shortcuts.get("github").doClick(); assertEquals(java.util.Collections.singletonList("github"), actions.opened);
        });
    }
    @Test public void sharingRequiresConfirmationAndStaysInSync() throws Exception
    {
        SwingUtilities.invokeAndWait(() -> {
            Actions actions = new Actions(); BuddySettingsPanel panel = new BuddySettingsPanel(actions);
            panel.options.get("shareItems").doClick(); assertFalse(panel.options.get("shareItems").isSelected()); assertTrue(actions.values.isEmpty());
            actions.permit = true; panel.options.get("shareItems").doClick(); assertTrue(actions.values.get("shareItems"));
            actions.values.put("shareItems", false); panel.refresh(); assertFalse(panel.options.get("shareItems").isSelected());
        });
    }
}
