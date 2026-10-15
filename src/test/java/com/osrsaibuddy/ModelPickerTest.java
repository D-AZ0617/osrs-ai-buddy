package com.osrsaibuddy;

import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The model picker: a menu button whose menu lists the models, then "Type a model name…" and "Refresh list". What the
 * player chooses is saved, once, and nothing else is: not typing, not Escape, not what the panel shows itself. Runs
 * headless.
 */
public class ModelPickerTest
{
	private static final List<String> CLAUDE = Arrays.asList("claude-opus-5-5", "claude-haiku-4-5", "claude-sonnet-5-5");
	private static final String TIP = "New messages go to claude-opus-5-5. Click to pick another, or to type its name.";

	private final List<String> chosen = new ArrayList<>();
	private int refreshes;

	/** Runs {@code test} on the EDT, then what it left queued there (the picker hands choices on a moment later). */
	private static void onEdt(Runnable test) throws Throwable
	{
		try
		{
			SwingUtilities.invokeAndWait(test);
			SwingUtilities.invokeAndWait(() ->
			{
			});
		}
		catch (InvocationTargetException e)
		{
			throw e.getCause();
		}
	}

	private ModelPicker picker() throws Throwable
	{
		ModelPicker[] made = new ModelPicker[1];
		onEdt(() ->
		{
			made[0] = new ModelPicker(new ModelPicker.Actions()
			{
				@Override
				public void choose(String model)
				{
					chosen.add(model);
				}

				@Override
				public void refresh()
				{
					refreshes++;
				}
			});
			// In the composer's row, as wide as the sidebar.
			JPanel row = new JPanel(null);
			row.add(made[0]);
			row.setSize(220, 30);
			made[0].setBounds(0, 0, 190, 30);
			made[0].show("claude-opus-5-5", CLAUDE, TIP, null, null);
		});
		return made[0];
	}

	@SuppressWarnings("unchecked")
	private static JList<String> models(JPopupMenu menu)
	{
		for (Component c : menu.getComponents())
		{
			if (c instanceof JScrollPane)
			{
				return (JList<String>) ((JScrollPane) c).getViewport().getView();
			}
		}
		return null;
	}

	private static JMenuItem item(JPopupMenu menu, String text)
	{
		for (Component c : menu.getComponents())
		{
			if (c instanceof JMenuItem && text.equals(((JMenuItem) c).getText()))
			{
				return (JMenuItem) c;
			}
		}
		return null;
	}

	private static String note(JPopupMenu menu)
	{
		for (Component c : menu.getComponents())
		{
			if (c instanceof JTextArea)
			{
				return ((JTextArea) c).getText();
			}
		}
		return null;
	}

	/** The menu's row for model {@code i}, as drawn: its labels are the name and the tick. */
	private static Container row(JList<String> list, int i)
	{
		return (Container) list.getCellRenderer().getListCellRendererComponent(list, list.getModel().getElementAt(i), i,
			false, false);
	}

	private static boolean ticked(JList<String> list, int i)
	{
		return ((JLabel) row(list, i).getComponent(1)).getIcon() != null;
	}

	/** A click on the menu's row for model {@code i}, as the mouse makes it. */
	private static void click(JList<String> list, int i)
	{
		list.setSize(list.getPreferredSize());
		Rectangle cell = list.getCellBounds(i, i);
		list.dispatchEvent(new MouseEvent(list, MouseEvent.MOUSE_RELEASED, 0, MouseEvent.BUTTON1_DOWN_MASK,
			cell.x + 5, cell.y + cell.height / 2, 1, false, MouseEvent.BUTTON1));
	}

	@Test
	public void itsAMenuButtonWithTheModelsNameAndAChevron() throws Throwable
	{
		ModelPicker p = picker();
		onEdt(() ->
		{
			assertEquals("claude-opus-5-5", p.button.getText());
			assertEquals(TIP, p.button.getToolTipText());
			assertTrue(p.button.getIcon() instanceof Glyph);
			assertTrue("no box: the button draws nothing of its own until the mouse is over it",
				!p.button.isContentAreaFilled() && !p.button.isBorderPainted());
			p.show("", CLAUDE, "The model new messages go to.", null, null);
			assertEquals(ModelPicker.NONE, p.button.getText());
		});
	}

	@Test
	public void theMenuListsTheModelsThenTypeAndRefresh() throws Throwable
	{
		ModelPicker p = picker();
		onEdt(() ->
		{
			JPopupMenu menu = p.menu();
			JList<String> list = models(menu);
			assertNotNull(list);
			assertEquals(CLAUDE, listItems(list));
			assertTrue("the one that's set is ticked", ticked(list, 0));
			assertFalse(ticked(list, 1));
			assertFalse(ticked(list, 2));
			assertNull("nothing to explain", note(menu));
			// The models, a line, then typing a name and refreshing the list, in that order.
			Component[] parts = menu.getComponents();
			assertTrue(parts[0] instanceof JScrollPane);
			assertTrue(parts[1] instanceof JPopupMenu.Separator);
			assertEquals(ModelPicker.TYPE, ((JMenuItem) parts[2]).getText());
			assertEquals(ModelPicker.REFRESH, ((JMenuItem) parts[3]).getText());
			assertTrue(item(menu, ModelPicker.REFRESH).isEnabled());
			// Typing a name and refreshing look like the models over them, not greyed out or squeezed under them.
			for (String text : new String[]{ModelPicker.TYPE, ModelPicker.REFRESH})
			{
				JMenuItem item = item(menu, text);
				assertEquals(PanelStyle.TEXT_COLOR, item.getForeground());
				assertEquals(PanelStyle.TEXT_FONT, item.getFont());
				assertEquals(list.getFixedCellHeight(), item.getPreferredSize().height);
			}
		});
	}

	/** The rows of {@code menu}'s list that show when it's laid out as it opens: the first and the last. */
	private static int[] shownRows(JPopupMenu menu)
	{
		JList<String> list = models(menu);
		JScrollPane scroll = (JScrollPane) list.getParent().getParent();
		menu.setSize(menu.getPreferredSize());
		menu.doLayout();
		scroll.doLayout();
		scroll.getViewport().doLayout();
		return new int[]{list.getFirstVisibleIndex(), list.getLastVisibleIndex()};
	}

	@Test
	public void theMenuOpensWithTheModelThatsSetInViewAndTheOnesOverIt() throws Throwable
	{
		ModelPicker p = picker();
		onEdt(() ->
		{
			List<String> twelve = new ArrayList<>();
			for (int i = 0; i < 12; i++)
			{
				twelve.add("claude-model-" + i);
			}
			// Near the top: the list from its top, so the newer models over the one that's set show too.
			p.show("claude-model-1", twelve, TIP, null, null);
			assertArrayEquals(new int[]{0, ModelPicker.MENU_ROWS - 1}, shownRows(p.menu()));
			p.show("claude-model-" + (ModelPicker.MENU_ROWS - 1), twelve, TIP, null, null);
			assertArrayEquals(new int[]{0, ModelPicker.MENU_ROWS - 1}, shownRows(p.menu()));
			// Further down than the rows shown reach: it's the last of them.
			p.show("claude-model-9", twelve, TIP, null, null);
			assertArrayEquals(new int[]{10 - ModelPicker.MENU_ROWS, 9}, shownRows(p.menu()));
			p.show("claude-model-11", twelve, TIP, null, null);
			assertArrayEquals(new int[]{12 - ModelPicker.MENU_ROWS, 11}, shownRows(p.menu()));
		});
	}

	@Test
	public void choosingAModelInTheMenuSavesItOnce() throws Throwable
	{
		ModelPicker p = picker();
		onEdt(() ->
		{
			JList<String> list = models(p.menu());
			click(list, 2);
		});
		assertEquals(Arrays.asList("claude-sonnet-5-5"), chosen);
		onEdt(() -> assertEquals("claude-sonnet-5-5", p.button.getText()));
		// The settings change, and the panel shows it: no second save. The one that's set, chosen again: nothing.
		onEdt(() ->
		{
			p.show("claude-sonnet-5-5", CLAUDE, TIP, null, null);
			JList<String> list = models(p.menu());
			assertTrue(ticked(list, 2));
			click(list, 2);
		});
		assertEquals(Arrays.asList("claude-sonnet-5-5"), chosen);
	}

	@Test
	public void whatThePanelShowsIsNeverTakenForAChoice() throws Throwable
	{
		ModelPicker p = picker();
		onEdt(() ->
		{
			// A model changed in the settings, a list that came in, a list that went, no model at all.
			p.show("claude-sonnet-5-5", CLAUDE, TIP, null, null);
			p.show("claude-sonnet-5-5", Arrays.asList("claude-sonnet-5-5", "claude-opus-5-5"), "Still the same.", null,
				null);
			assertEquals("claude-sonnet-5-5", p.button.getText());
			assertEquals("Still the same.", p.button.getToolTipText());
			p.show("my-model", Collections.singletonList("my-model"), TIP, "Couldn't list the models: no.", null);
			p.show("", Collections.emptyList(), TIP, null, null);
		});
		assertTrue(chosen.isEmpty());
	}

	@Test
	public void aTypedNameIsSavedOnEnterNotWhileTypingNorOnEscape() throws Throwable
	{
		ModelPicker p = picker();
		onEdt(() ->
		{
			item(p.menu(), ModelPicker.TYPE).doClick();
			assertTrue(p.isTyping());
			assertFalse("the box takes the button's place", p.button.isVisible());
			assertEquals("the model that's set, to change", "claude-opus-5-5", p.field.getText());
			p.field.setText("my-fine");
			p.field.setText("my-fine-tune");
		});
		assertTrue("typing saves nothing", chosen.isEmpty());
		onEdt(() ->
		{
			// Enter.
			p.field.postActionEvent();
			assertFalse(p.isTyping());
			assertTrue(p.button.isVisible());
		});
		assertEquals(Arrays.asList("my-fine-tune"), chosen);

		// Escape: back to the button, nothing saved. A panel redraw meanwhile leaves the typing alone.
		onEdt(() ->
		{
			p.startTyping();
			p.field.setText("half-a-na");
			p.show("my-fine-tune", CLAUDE, TIP, null, null);
			assertEquals("half-a-na", p.field.getText());
			p.field.getActionMap().get("ai-chat-cancel").actionPerformed(null);
			assertFalse(p.isTyping());
			assertEquals("my-fine-tune", p.button.getText());

			// Nothing typed, or the model that's set: nothing to save.
			p.startTyping();
			p.field.setText("   ");
			p.field.postActionEvent();
			p.startTyping();
			p.field.postActionEvent();
		});
		assertEquals(Arrays.asList("my-fine-tune"), chosen);
	}

	@Test
	public void refreshListAsksAgainWhenItCan() throws Throwable
	{
		ModelPicker p = picker();
		onEdt(() -> item(p.menu(), ModelPicker.REFRESH).doClick());
		assertEquals(1, refreshes);
		onEdt(() ->
		{
			String off = "Turn on \"Allow Buddy to answer\" in the OSRS AI Buddy settings.";
			p.show("claude-opus-5-5", CLAUDE, TIP, "The list fills in once AI requests are on.", off);
			JPopupMenu menu = p.menu();
			JMenuItem refresh = item(menu, ModelPicker.REFRESH);
			assertFalse(refresh.isEnabled());
			assertEquals(off, refresh.getToolTipText());
			refresh.doClick();
			assertEquals("why the list is short, in plain words", "The list fills in once AI requests are on.",
				note(menu));
		});
		assertEquals(1, refreshes);
	}

	@Test
	public void aLongListScrolls() throws Throwable
	{
		ModelPicker p = picker();
		onEdt(() ->
		{
			List<String> many = new ArrayList<>();
			for (int i = 0; i < 40; i++)
			{
				many.add("model-" + i);
			}
			many.add("claude-opus-5-5");
			p.show("claude-opus-5-5", many, TIP, null, null);
			JList<String> list = models(p.menu());
			assertEquals(ModelPicker.MENU_ROWS, list.getVisibleRowCount());
			JScrollPane scroll = (JScrollPane) list.getParent().getParent();
			assertTrue(scroll.getPreferredSize().height < list.getPreferredSize().height);
			assertEquals("opens on the model that's set", 40, list.getSelectedIndex());
		});
	}

	@Test
	public void aLongNameShowsFromItsStartWithTheWholeOfItInTheTooltip() throws Throwable
	{
		String model = "anthropic/claude-sonnet-5-5:thinking-extended-preview";
		String tip = "New messages go to " + model + ". Click to pick another, or to type its name.";
		ModelPicker p = picker();
		onEdt(() ->
		{
			p.show(model, Collections.singletonList(model), tip, null, null);
			p.setSize(150, 30);
			p.doLayout();
			assertTrue("it doesn't fit", p.button.getPreferredSize().width > 150);
			assertEquals("cut to the room there is, its end first", 150, p.button.getWidth());
			assertEquals(0, p.button.getX());
			assertEquals(model, p.button.getText());
			assertEquals(tip, p.button.getToolTipText());
			// In the menu: cut too, with the whole name as the row's tooltip.
			JList<String> list = models(p.menu());
			assertEquals(model, ((JComponent) row(list, 0)).getToolTipText());
		});
		assertTrue("nothing new to save", chosen.isEmpty());
	}

	@Test
	public void namesAreNeverHtml() throws Throwable
	{
		ModelPicker p = picker();
		onEdt(() ->
		{
			p.show("<html><b>x", Arrays.asList("<html><b>x", "<html><i>y"), TIP, "<html><u>note", null);
			assertEquals(Boolean.TRUE, p.button.getClientProperty("html.disable"));
			assertEquals(Boolean.TRUE, p.button.createToolTip().getClientProperty("html.disable"));
			assertEquals(Boolean.TRUE, p.field.getClientProperty("html.disable"));
			assertEquals(Boolean.TRUE, p.field.createToolTip().getClientProperty("html.disable"));
			JPopupMenu menu = p.menu();
			JList<String> list = models(menu);
			for (int i = 0; i < 2; i++)
			{
				for (Component label : row(list, i).getComponents())
				{
					assertEquals(Boolean.TRUE, ((JComponent) label).getClientProperty("html.disable"));
				}
			}
			assertEquals(Boolean.TRUE, list.createToolTip().getClientProperty("html.disable"));
			for (String text : new String[]{ModelPicker.TYPE, ModelPicker.REFRESH})
			{
				JMenuItem item = item(menu, text);
				assertEquals(Boolean.TRUE, item.getClientProperty("html.disable"));
				assertEquals(Boolean.TRUE, item.createToolTip().getClientProperty("html.disable"));
			}
			// The note is a text area, which never reads HTML.
			assertEquals("<html><u>note", note(menu));
		});
	}

	private static List<String> listItems(JList<String> list)
	{
		List<String> out = new ArrayList<>();
		for (int i = 0; i < list.getModel().getSize(); i++)
		{
			out.add(list.getModel().getElementAt(i));
		}
		return out;
	}
}

