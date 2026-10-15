package com.osrsaibuddy;

import java.lang.reflect.Proxy;
import net.runelite.api.widgets.Widget;
import org.junit.Test;
import static org.junit.Assert.*;

public class CollectionPagesTest
{
    private Widget widget(String text, int id, int opacity, int quantity, boolean hidden, Widget... children)
    {
        return (Widget) Proxy.newProxyInstance(Widget.class.getClassLoader(), new Class<?>[]{Widget.class}, (proxy, method, args) -> {
            switch (method.getName())
            {
                case "getText": return text;
                case "getItemId": return id;
                case "getOpacity": return opacity;
                case "getItemQuantity": return quantity;
                case "isHidden": return hidden;
                case "getDynamicChildren": return children;
                default: return null;
            }
        });
    }
    private Widget header(String count)
    { return widget("", -1, 0, 0, false, widget("<col=fff>Zulrah</col>", -1, 0, 0, false), widget(count, -1, 0, 0, false)); }
    private Widget items()
    { return widget("", -1, 0, 0, false, widget("", 100, 0, 2, false), widget("", 200, 128, 1, false)); }

    @Test public void capturesObtainedAndMissingWithinValidatedPage()
    {
        CollectionPages pages = new CollectionPages();
        assertTrue(pages.capture(header("Obtained: <col=ffff00>1/2</col>"), items()));
        assertEquals("obtained", pages.entries().get(0).getAsJsonObject().get("status").getAsString());
        assertEquals("missing", pages.entries().get(1).getAsJsonObject().get("status").getAsString());
        assertEquals(0, pages.entries().get(1).getAsJsonObject().get("quantity").getAsInt());
        assertEquals("Zulrah", pages.entries().get(0).getAsJsonObject().get("page").getAsString());
    }
    @Test public void rejectsPartiallyLoadedOrMismatchedPageWithoutOverwriting()
    {
        CollectionPages pages = new CollectionPages(); assertTrue(pages.capture(header("1/2"), items()));
        assertFalse(pages.capture(header("2/2"), items()));
        assertFalse(pages.capture(header("1/3"), items()));
        assertEquals(2, pages.entries().size());
    }
    @Test public void absentOrHiddenWidgetsAreUnknown()
    {
        CollectionPages pages = new CollectionPages();
        assertFalse(pages.capture(null, items()));
        assertFalse(pages.capture(header("loading"), items()));
        assertFalse(pages.capture(header("1/2"), widget("", -1, 0, 0, true)));
        assertEquals(0, pages.size());
    }
    @Test public void returnedRowsCannotMutateStoredSnapshotAndClearForgetsPages()
    {
        CollectionPages pages = new CollectionPages(); pages.capture(header("1/2"), items());
        pages.entries().get(0).getAsJsonObject().addProperty("status", "changed");
        assertEquals("obtained", pages.entries().get(0).getAsJsonObject().get("status").getAsString());
        pages.clear(); assertEquals(0, pages.entries().size());
    }
}
