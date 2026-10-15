package com.osrsaibuddy;

import java.awt.BorderLayout;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.border.EmptyBorder;

/** Buddy's settings and user-initiated shortcuts. Preferences use the same keys as RuneLite's config panel. */
final class BuddySettingsPanel extends JPanel implements javax.swing.Scrollable
{
    interface Actions
    {
        boolean value(String key);
        void change(String key, boolean enabled);
        boolean confirm(String warning);
        void open(String action);
    }
    private final Actions actions;
    final Map<String, JCheckBox> options = new LinkedHashMap<>();
    final Map<String, FlatButton> shortcuts = new LinkedHashMap<>();

    BuddySettingsPanel(Actions actions)
    {
        this.actions = actions;
        setLayout(new StackLayout(9)); setBackground(PanelStyle.BACKGROUND);
        setBorder(new EmptyBorder(4, 0, 12, 2));
        heading("Account context");
        option("sendCharacter", "Share character overview", "Sends character name, account type, levels, quest progress, Slayer task and diaries to OpenAI, a third-party server not controlled or verified by RuneLite developers.");
        option("shareProgress", "Share achievements & collections", "Sends account achievements, collection-log observations, unlocks and selected currencies to OpenAI, a third-party server not controlled or verified by RuneLite developers.");
        option("shareItems", "Share bank, inventory & gear", "Sends equipment, inventory and observed bank contents to OpenAI, a third-party server not controlled or verified by RuneLite developers.");
        heading("Knowledge sources");
        option("wikiLookups", "Allow OSRS Wiki searches", "Sends search terms, page titles and your IP address to the OSRS Wiki. Search terms may reflect your question or shared context. This third-party server is not controlled or verified by RuneLite developers.");
        option("shareWom", "Use Wise Old Man history", "Sends your logged-in character name and IP address to Wise Old Man and retrieved statistics/history to OpenAI. These third-party servers are not controlled or verified by RuneLite developers.");
        heading("Notifications");
        option("notifyOnDone", "Reply alerts", null);
        heading("Quick access");
        JTextArea note = PanelStyle.textArea("Conversations save automatically on this PC.");
        note.setForeground(PanelStyle.MUTED_COLOR); add(note);
        shortcut("conversations", "Saved conversations", "Open the local conversations file. It can contain personal chat and account details.");
        shortcut("data", "Plugin data folder", "Open Buddy's local data folder.");
        shortcut("plugin", "Installed plugin folder", "Show the installed plugin's folder; does not execute its JAR.");
        shortcut("information", "Plugin information", "Open a local guide to Buddy's files, privacy and support.");
        shortcut("github", "GitHub repository", "Open the OSRS AI Buddy source and documentation.");
        FlatButton coffee = shortcut("coffee", "Buy me a coffee", "Work in progress — donation link not available yet.");
        coffee.setEnabled(false);
        refresh();
    }

    private void heading(String text)
    {
        JLabel label = new JLabel(text); label.setFont(PanelStyle.TITLE_FONT);
        label.setForeground(PanelStyle.TEXT_COLOR); label.setBorder(new EmptyBorder(12, 0, 3, 0)); add(label);
    }

    private void option(String key, String label, String warning)
    {
        JPanel row = new JPanel(new BorderLayout(7, 0)); row.setOpaque(false);
        JCheckBox check = new JCheckBox(); check.setOpaque(false);
        check.getAccessibleContext().setAccessibleName(label);
        JTextArea text = PanelStyle.textArea(label); text.setForeground(PanelStyle.TEXT_COLOR);
        if (warning != null) { text.setToolTipText(warning); check.setToolTipText(warning); }
        row.add(check, BorderLayout.WEST); row.add(text, BorderLayout.CENTER); add(row);
        check.addActionListener(event -> {
            if (check.isSelected() && warning != null && !actions.confirm(warning)) { check.setSelected(false); return; }
            actions.change(key, check.isSelected());
        });
        options.put(key, check);
    }

    private FlatButton shortcut(String action, String label, String tooltip)
    {
        FlatButton button = new FlatButton(label, null, tooltip)
            .filled(PanelStyle.FIELD_COLOR, PanelStyle.OUTLINE_COLOR, false);
        button.setBorder(new EmptyBorder(9, 8, 9, 8));
        if (!"coffee".equals(action)) { button.addActionListener(event -> actions.open(action)); }
        shortcuts.put(action, button); add(button); return button;
    }

    void refresh() { options.forEach((key, check) -> check.setSelected(actions.value(key))); }

    @Override public java.awt.Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
    @Override public int getScrollableUnitIncrement(java.awt.Rectangle visible, int direction, int orientation) { return 16; }
    @Override public int getScrollableBlockIncrement(java.awt.Rectangle visible, int direction, int orientation) { return Math.max(16, visible.height - 16); }
    @Override public boolean getScrollableTracksViewportWidth() { return true; }
    @Override public boolean getScrollableTracksViewportHeight() { return false; }
}
