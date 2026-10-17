package com.osrsaibuddy;

import java.util.ArrayList;
import java.util.List;
import javax.swing.SwingUtilities;
import org.junit.Test;
import static org.junit.Assert.*;

public class SupportFooterTest
{
    @Test public void onlyFooterButtonOpensGitHubSponsorsOnClick() throws Exception
    {
        SwingUtilities.invokeAndWait(() -> {
            List<String> actions = new ArrayList<>(); SupportFooter footer = new SupportFooter(actions::add);
            assertEquals(1, footer.getComponentCount()); assertEquals("Buy Me A Coffee", footer.coffee.getText());
            assertTrue(actions.isEmpty()); footer.coffee.doClick();
            assertEquals(java.util.Collections.singletonList("sponsors"), actions);
        });
    }
}
