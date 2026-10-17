package com.osrsaibuddy;

import java.awt.BorderLayout;
import java.util.function.Consumer;
import javax.swing.JPanel;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;

/** A single optional support action, shown only on the Conversations page. */
final class SupportFooter extends JPanel
{
    final FlatButton coffee = new FlatButton("Buy Me A Coffee", null, "Support OSRS AI Buddy through GitHub Sponsors")
        .filled(PanelStyle.FIELD_COLOR, PanelStyle.OUTLINE_COLOR, false);

    SupportFooter(Consumer<String> open)
    {
        super(new BorderLayout()); setOpaque(false);
        setBorder(new CompoundBorder(new MatteBorder(1, 0, 0, 0, PanelStyle.OUTLINE_COLOR), new EmptyBorder(10, 0, 0, 0)));
        coffee.setBorder(new EmptyBorder(9, 8, 9, 8));
        coffee.addActionListener(event -> open.accept("sponsors")); add(coffee, BorderLayout.CENTER);
    }
}
