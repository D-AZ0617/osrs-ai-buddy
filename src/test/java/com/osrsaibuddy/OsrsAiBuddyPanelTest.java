package com.osrsaibuddy;

import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollBar;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.RepaintManager;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.StyleConstants;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The panel with a stand-in plugin: the composer's button, the starters, Retry and the actions under replies, the
 * banner, the player's bubbles, the reply on its way, when the transcript follows the chat down, and the lines that show
 * more when clicked. (The drawing itself isn't tested here.) Runs headless.
 */
public class OsrsAiBuddyPanelTest
{
	/** The plugin, as far as the panel can tell: settings and chats the test sets, and a record of what was asked. */
	private static final class FakeHost implements OsrsAiBuddyPanel.Host
	{
		final List<Chat> chats = new ArrayList<>();
		Chat current;
		String problem;
		ConnectionCheck.Note note;
		boolean dismissed;
		String model = "claude-opus-5-5";
		List<String> choices = Arrays.asList("claude-opus-5-5", "claude-haiku-4-5");
		final List<String> sent = new ArrayList<>();
		final List<Chat.Message> retried = new ArrayList<>();
		final List<String> chosen = new ArrayList<>();
		int stops;
		int refreshes;
		boolean accept = true;

		FakeHost()
		{
			current = new Chat("Chat 1");
			chats.add(current);
		}

		@Override
		public List<Chat> chats()
		{
			return chats;
		}

		@Override
		public Chat currentChat()
		{
			return current;
		}

		@Override
		public String setupProblem()
		{
			return problem;
		}

		@Override
		public String testProblem()
		{
			return problem;
		}

		@Override
		public ConnectionCheck.Note connectionNote()
		{
			return dismissed ? null : note;
		}

		@Override
		public void dismissNote()
		{
			dismissed = true;
		}

		@Override
		public String model()
		{
			return model;
		}

		@Override
		public List<String> modelChoices()
		{
			return choices;
		}

		@Override
		public String modelTip()
		{
			return "The model new messages go to.";
		}

		@Override
		public String modelNote()
		{
			return null;
		}

		@Override
		public void refreshModels()
		{
			refreshes++;
		}

		@Override
		public boolean send(String text)
		{
			sent.add(text);
			return accept;
		}

		@Override
		public void stop()
		{
			stops++;
		}

		@Override
		public void retry(Chat chat, Chat.Message m)
		{
			retried.add(m);
		}

		@Override
		public void newChat()
		{
		}

		@Override
		public void selectChat(Chat chat)
		{
		}

		@Override
		public void renameChat(Chat chat, String name)
		{
		}

		@Override
		public void clearChat(Chat chat)
		{
		}

		@Override
		public void deleteChat(Chat chat)
		{
		}

		@Override
		public void testConnection()
		{
		}

		@Override
		public void chooseModel(String model)
		{
			chosen.add(model);
		}
	}

	private final FakeHost host = new FakeHost();

	/** Swing components belong on the EDT, in tests too. */
	private static void onEdt(Runnable test) throws Throwable
	{
		try
		{
			SwingUtilities.invokeAndWait(test);
		}
		catch (InvocationTargetException e)
		{
			throw e.getCause();
		}
	}

	/** Lays out {@code c} and everything in it, as showing it would: headless, nothing is shown. */
	private static void layOut(Component c)
	{
		if (c instanceof Container)
		{
			((Container) c).doLayout();
			for (Component child : ((Container) c).getComponents())
			{
				layOut(child);
			}
		}
	}

	private Chat.Message add(Chat.Role role, String text)
	{
		Chat.Message m = new Chat.Message(role, text);
		host.current.messages.add(m);
		return m;
	}

	@Test
	public void theButtonSendsStopsOrSkips() throws Throwable
	{
		onEdt(() ->
		{
			OsrsAiBuddyPanel panel = new OsrsAiBuddyPanel(host);
			Composer.ActionButton button = panel.composer.action;
			assertEquals(Composer.Mode.SEND, button.mode);
			assertEquals("Send (Enter)", button.getToolTipText());
			assertFalse("nothing to send", button.isEnabled());

			panel.composer.input.setText("  ");
			assertFalse("only spaces", button.isEnabled());
			panel.composer.input.setText("What drops a whip?");
			assertTrue(button.isEnabled());
			button.doClick();
			assertEquals(Arrays.asList("What drops a whip?"), host.sent);
			assertEquals("sent: the box is cleared", "", panel.composer.input.getText());

			// A message that can't go yet stays in the box.
			host.accept = false;
			panel.composer.input.setText("And a dragon pickaxe?");
			button.doClick();
			assertEquals("And a dragon pickaxe?", panel.composer.input.getText());

			// A reply on its way: Stop, whatever's in the box.
			host.current.messages.add(new Chat.Message(Chat.Role.USER, "q"));
			host.current.pending = new ChatApi.Pending();
			panel.refreshAll();
			assertEquals(Composer.Mode.STOP, button.mode);
			assertEquals("Stop", button.getToolTipText());
			assertTrue(button.isEnabled());
			panel.composer.input.setText("");
			assertTrue("Stop has nothing to send", button.isEnabled());
			int sent = host.sent.size();
			settle(button);
			button.doClick();
			assertEquals(1, host.stops);
			assertEquals(sent, host.sent.size());

			// A long chat's summary first: Skip, with its own tooltip.
			host.current.skipSummary = () ->
			{
			};
			panel.refreshAll();
			assertEquals(Composer.Mode.SKIP, button.mode);
			assertEquals("Skip", button.getText());
			assertEquals(Composer.SKIP_TIP, button.getToolTipText());
			settle(button);
			button.doClick();
			assertEquals(2, host.stops);

			host.current.skipSummary = null;
			host.current.pending = null;
			panel.refreshAll();
			assertEquals(Composer.Mode.SEND, button.mode);
			assertFalse(button.isEnabled());
		});
	}

	/**
	 * As if the button had only just changed what it does, however long the machine took to get here: a click now is
	 * one meant for what it did before.
	 */
	private static void unsettle(Composer.ActionButton button)
	{
		button.changedAt = System.currentTimeMillis() + 60_000;
	}

	/** As if the player waited a moment after the button changed, as anyone does before pressing it on purpose. */
	private static void settle(Composer.ActionButton button)
	{
		button.changedAt = System.currentTimeMillis() - Composer.ActionButton.SETTLE_MILLIS;
	}

	@Test
	public void aClickAsTheButtonChangesIsntTakenForTheNewOne() throws Throwable
	{
		onEdt(() ->
		{
			OsrsAiBuddyPanel panel = new OsrsAiBuddyPanel(host);
			Composer.ActionButton button = panel.composer.action;
			panel.composer.input.setText("What drops a whip?");
			button.doClick();
			assertEquals(Arrays.asList("What drops a whip?"), host.sent);
			// As the plugin does as the question goes: the button turns into Stop at once.
			add(Chat.Role.USER, "What drops a whip?");
			host.current.pending = new ChatApi.Pending();
			panel.refreshAll();
			assertEquals(Composer.Mode.STOP, button.mode);

			// A double-click's second click was meant for Send: it doesn't stop the question just sent.
			unsettle(button);
			button.doClick(0);
			assertEquals(0, host.stops);
			// Pressed a moment later, Stop stops.
			settle(button);
			button.doClick();
			assertEquals(1, host.stops);

			// Stop pressed just as the reply comes in: what's in the box isn't sent.
			host.current.pending = null;
			add(Chat.Role.ASSISTANT, "Abyssal demons.");
			panel.composer.input.setText("And a dragon pickaxe?");
			panel.refreshAll();
			assertEquals(Composer.Mode.SEND, button.mode);
			assertTrue(button.isEnabled());
			unsettle(button);
			button.doClick(0);
			assertEquals(1, host.sent.size());
			settle(button);
			button.doClick();
			assertEquals(Arrays.asList("What drops a whip?", "And a dragon pickaxe?"), host.sent);
		});
	}

	@Test
	public void theChatsTitleIsAsWideAsItIsWithItsArrowRightAfterIt() throws Throwable
	{
		onEdt(() ->
		{
			OsrsAiBuddyPanel panel = new OsrsAiBuddyPanel(host);
			panel.setSize(225, 600);
			layOut(panel);
			JComponent title = (JComponent) panel.chatSelect.getParent();
			int room = title.getWidth();
			int shown = panel.chatSelect.getWidth();
			assertTrue("not stretched to the buttons", shown < room);

			// The list holds a longer name: the title doesn't make room for it.
			Chat other = new Chat("Plans for the whole Desert Treasure II quest line, start to end");
			host.chats.add(other);
			panel.refreshAll();
			layOut(panel);
			assertEquals(shown, panel.chatSelect.getWidth());

			// Shown, it's cut to the room beside the buttons.
			host.current = other;
			panel.refreshAll();
			layOut(panel);
			assertEquals(room, panel.chatSelect.getWidth());
			assertTrue(title.getX() + title.getWidth() <= panel.newChat.getParent().getX());
		});
	}

	@Test
	public void jumpToTheLatestShowsWhileThePlayerIsntReadingTheEnd() throws Throwable
	{
		OsrsAiBuddyPanel[] made = new OsrsAiBuddyPanel[1];
		onEdt(() ->
		{
			for (int i = 0; i < 12; i++)
			{
				add(Chat.Role.USER, "Question " + i + ", long enough to wrap onto a second line in the narrow panel");
				add(Chat.Role.ASSISTANT, "Answer " + i + ", long enough to wrap onto a second line in the narrow panel, "
					+ "and onto a third one too.");
			}
			made[0] = new OsrsAiBuddyPanel(host);
			made[0].setSize(225, 400);
			layOut(made[0]);
			layOut(made[0]);
		});
		OsrsAiBuddyPanel panel = made[0];
		JScrollBar bar = panel.transcriptScroll.getVerticalScrollBar();
		// A chat opens at its end.
		onEdt(() ->
		{
			assertTrue("the chat is longer than the panel", bar.getMaximum() > 2 * bar.getVisibleAmount());
			assertEquals(bar.getMaximum(), bar.getValue() + bar.getVisibleAmount());
			assertFalse(panel.jump.isVisible());

			bar.setValue(0);
			assertTrue("scrolled up", panel.jump.isVisible());
			bar.setValue(bar.getMaximum());
			assertFalse("back at the end", panel.jump.isVisible());

			bar.setValue(0);
			panel.jump.doClick();
		});
		onEdt(() ->
		{
			assertEquals("taken to the end", bar.getMaximum(), bar.getValue() + bar.getVisibleAmount());
			assertFalse(panel.jump.isVisible());

			// A question sent from the end: the transcript grows before it's followed down, and that isn't the player
			// scrolling up.
			add(Chat.Role.USER, "And how long does the Slayer task take, roughly, if I bring a cannon along with me?");
			host.current.pending = new ChatApi.Pending();
			host.current.runStartedAt = System.currentTimeMillis();
			panel.refreshAll();
			layOut(panel);
			assertTrue(bar.getValue() + bar.getVisibleAmount() < bar.getMaximum() - 24);
			assertFalse("no flash", panel.jump.isVisible());
		});
		onEdt(() ->
		{
			assertEquals("followed", bar.getMaximum(), bar.getValue() + bar.getVisibleAmount());
			assertFalse(panel.jump.isVisible());
		});
	}

	@Test
	public void theModelMenuChoosesAndRefreshesThroughThePlugin() throws Throwable
	{
		onEdt(() ->
		{
			OsrsAiBuddyPanel panel = new OsrsAiBuddyPanel(host);
			ModelPicker picker = panel.composer.models;
			assertEquals("claude-opus-5-5", picker.button.getText());
			JPopupMenu menu = picker.menu();
			for (Component c : menu.getComponents())
			{
				if (c instanceof JMenuItem && ModelPicker.REFRESH.equals(((JMenuItem) c).getText()))
				{
					assertTrue(c.isEnabled());
					((JMenuItem) c).doClick();
				}
			}
			assertEquals(1, host.refreshes);
			picker.startTyping();
			picker.field.setText("claude-haiku-4-5");
			picker.field.postActionEvent();
		});
		// The choice reaches the plugin a moment later, once the picker's own event is over.
		onEdt(() -> assertEquals(Arrays.asList("claude-haiku-4-5"), host.chosen));
		onEdt(() ->
		{
			// Refresh list asks the provider, as Test connection does: not while that can't be done.
			host.problem = "Turn on \"Enable AI requests\" in the OSRS AI Buddy settings.";
			OsrsAiBuddyPanel panel = new OsrsAiBuddyPanel(host);
			for (Component c : panel.composer.models.menu().getComponents())
			{
				if (c instanceof JMenuItem && ModelPicker.REFRESH.equals(((JMenuItem) c).getText()))
				{
					assertFalse(c.isEnabled());
				}
			}
		});
	}

	@Test
	public void theInputBoxSaysWhatItsFor() throws Throwable
	{
		onEdt(() ->
		{
			OsrsAiBuddyPanel panel = new OsrsAiBuddyPanel(host);
			assertEquals("Ask anything…", Composer.PLACEHOLDER);
			// Painted in the box, not a tooltip: drawn while it's empty, and gone once there's text.
			panel.composer.input.setSize(200, 40);
			BufferedImage empty = paint(panel.composer.input);
			panel.composer.input.setText(" ");
			BufferedImage typed = paint(panel.composer.input);
			assertTrue("the placeholder draws something", differs(empty, typed));
		});
	}

	/**
	 * Stands in for Swing laying out what changed, which it never does off screen: keeps each component that asks for
	 * it (revalidate), and {@link #run} lays out what Swing would on screen, from the validate root over each: the
	 * scroll pane it's in, or else the top. So a box whose text grew, but that nothing asked to lay out again, keeps
	 * its height, as it would on screen. Like Swing, it queues that layout on the EDT at the first ask: after what was
	 * queued before it, and before what's queued after it, such as the panel's scroll to the end, which counts on it.
	 */
	private static final class Relayout extends RepaintManager
	{
		private final List<JComponent> asked = new ArrayList<>();
		private RepaintManager swing;
		private boolean queued;

		@Override
		public synchronized void addInvalidComponent(JComponent c)
		{
			asked.add(c);
			if (!queued)
			{
				queued = true;
				SwingUtilities.invokeLater(this::run);
			}
		}

		void install(Component c)
		{
			swing = RepaintManager.currentManager(c);
			RepaintManager.setCurrentManager(this);
		}

		void uninstall()
		{
			RepaintManager.setCurrentManager(swing);
		}

		private synchronized void run()
		{
			queued = false;
			Map<Container, Boolean> roots = new IdentityHashMap<>();
			for (JComponent c : asked)
			{
				Container root = c;
				while (!root.isValidateRoot() && root.getParent() != null)
				{
					root = root.getParent();
				}
				roots.put(root, true);
			}
			asked.clear();
			roots.keySet().forEach(OsrsAiBuddyPanelTest::layOut);
		}
	}

	/**
	 * Makes {@code edit} on the EDT, then lets what that queued run (the box measuring itself again, the layout that
	 * asks for, the transcript following its end), and what those queue in turn, as Swing would before the player's
	 * next key. Waiting on the queue itself, not on a layout the test asks for: that one would go in wherever this
	 * thread happened to post it, before or after the panel's own events, so the outcome would depend on timing.
	 */
	private static void edit(Runnable edit) throws Throwable
	{
		onEdt(edit);
		boolean[] idle = new boolean[1];
		// Each round runs everything queued before it, so it reaches one step further down a chain of invokeLater; the
		// panel's chains are a few steps long. Bounded, in case something else keeps posting.
		for (int i = 0; i < 20 && !idle[0]; i++)
		{
			onEdt(() -> idle[0] = Toolkit.getDefaultToolkit().getSystemEventQueue().peekEvent() == null);
		}
		assertTrue("the events the edit queued came to an end", idle[0]);
	}

	/** Does what {@code key} does in {@code c}, as pressing it would. */
	private static void press(JComponent c, String key)
	{
		Object name = c.getInputMap().get(KeyStroke.getKeyStroke(key));
		c.getActionMap().get(name).actionPerformed(new ActionEvent(c, ActionEvent.ACTION_PERFORMED, null));
	}

	@Test
	public void theInputBoxGrowsWithItsTextThenScrollsAndShrinksAfterSending() throws Throwable
	{
		Relayout relayout = new Relayout();
		OsrsAiBuddyPanel[] made = new OsrsAiBuddyPanel[1];
		onEdt(() ->
		{
			made[0] = new OsrsAiBuddyPanel(host);
			made[0].setSize(225, 600);
			layOut(made[0]);
			relayout.install(made[0]);
		});
		try
		{
			Composer composer = made[0].composer;
			JTextArea input = composer.input;
			onEdt(() -> assertEquals("two lines to start with", composer.linesHeight(2), composer.scroll.getHeight()));

			// Shift+Enter on the second line: the box is three lines tall at once, not after the next key.
			edit(() -> input.setText("Two\nlines"));
			edit(() -> press(input, "shift ENTER"));
			onEdt(() -> assertEquals(composer.linesHeight(3), composer.scroll.getHeight()));
			// Pasted lines.
			edit(() -> input.replaceSelection("and\na\nfew more"));
			onEdt(() -> assertEquals(composer.linesHeight(5), composer.scroll.getHeight()));
			// A long line wraps in the narrow box, and the box grows for that too.
			edit(() -> composer.fill("What should I bring to Vorkath with 99 Ranged and a dragon hunter "
				+ "crossbow, and how many kills a trip?"));
			onEdt(() -> assertTrue(composer.scroll.getHeight() >= composer.linesHeight(3)));
			edit(() -> input.setText("1\n2\n3\n4\n5\n6\n7\n8\n9\n10"));
			onEdt(() ->
			{
				assertEquals("at most six lines: then it scrolls", composer.linesHeight(6),
					composer.scroll.getHeight());
				assertTrue(input.getPreferredSize().height > composer.scroll.getViewport().getHeight());
			});

			edit(() -> composer.action.doClick());
			onEdt(() ->
			{
				assertEquals("sent", "1\n2\n3\n4\n5\n6\n7\n8\n9\n10", host.sent.get(0));
				assertEquals("back to two lines", composer.linesHeight(2), composer.scroll.getHeight());
			});
			// A message that couldn't go, back in the box.
			edit(() -> composer.restoreDraft("One\ntwo\nthree\nfour"));
			onEdt(() -> assertEquals(composer.linesHeight(4), composer.scroll.getHeight()));
		}
		finally
		{
			onEdt(relayout::uninstall);
		}
	}

	@Test
	public void aGrowingInputBoxKeepsTheEndOfTheTranscriptInView() throws Throwable
	{
		Relayout relayout = new Relayout();
		OsrsAiBuddyPanel[] made = new OsrsAiBuddyPanel[1];
		onEdt(() ->
		{
			for (int i = 0; i < 12; i++)
			{
				add(Chat.Role.USER, "Question " + i + ", long enough to wrap onto a second line in the narrow panel");
				add(Chat.Role.ASSISTANT, "Answer " + i + ", long enough to wrap onto a second line in the panel.");
			}
			made[0] = new OsrsAiBuddyPanel(host);
			made[0].setSize(225, 400);
			layOut(made[0]);
			layOut(made[0]);
			made[0].composer.input.setText("Two\nlines");
			layOut(made[0]);
		});
		OsrsAiBuddyPanel panel = made[0];
		JScrollBar bar = panel.transcriptScroll.getVerticalScrollBar();
		try
		{
			onEdt(() ->
			{
				assertEquals("at the end", bar.getMaximum(), bar.getValue() + bar.getVisibleAmount());
				relayout.install(panel);
			});
			edit(() -> press(panel.composer.input, "shift ENTER"));
			edit(() -> panel.composer.input.replaceSelection("3\n4\n5"));
			onEdt(() ->
			{
				assertEquals(panel.composer.linesHeight(5), panel.composer.scroll.getHeight());
				assertEquals("still at the end", bar.getMaximum(), bar.getValue() + bar.getVisibleAmount());
				assertFalse(panel.jump.isVisible());

				// Scrolled up to read: what's read stays where it is.
				bar.setValue(100);
			});
			edit(() -> panel.composer.input.setText(""));
			onEdt(() -> assertEquals(100, bar.getValue()));
		}
		finally
		{
			onEdt(relayout::uninstall);
		}
	}

	private static BufferedImage paint(JComponent c)
	{
		BufferedImage image = new BufferedImage(c.getWidth(), c.getHeight(),
			BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		c.paint(g);
		g.dispose();
		return image;
	}

	private static boolean differs(BufferedImage a, BufferedImage b)
	{
		for (int x = 0; x < a.getWidth(); x++)
		{
			for (int y = 0; y < a.getHeight(); y++)
			{
				if (a.getRGB(x, y) != b.getRGB(x, y))
				{
					return true;
				}
			}
		}
		return false;
	}

	@Test
	public void startersFillTheInputAndNeverSend() throws Throwable
	{
		onEdt(() ->
		{
			OsrsAiBuddyPanel panel = new OsrsAiBuddyPanel(host);
			assertTrue("an empty chat welcomes the player", panel.showsEmpty());
			assertEquals(4, panel.empty.chips.size());
			for (int i = 0; i < EmptyChat.STARTERS.size(); i++)
			{
				EmptyChat.Starter starter = EmptyChat.STARTERS.get(i);
				assertEquals(starter.label, panel.empty.chips.get(i).getText());
				panel.empty.chips.get(i).doClick();
				assertEquals(starter.text, panel.composer.input.getText());
				assertEquals("the caret waits at the end", starter.text.length(), panel.composer.input.getCaretPosition());
			}
			assertTrue("nothing is sent: that costs money", host.sent.isEmpty());
			assertEquals("How much is ", EmptyChat.STARTERS.get(1).text);
			assertTrue(panel.composer.action.isEnabled());

			add(Chat.Role.USER, "q");
			panel.refreshAll();
			assertFalse(panel.showsEmpty());
		});
	}

	@Test
	public void retryWritesAgainOnlyTheLatestReply() throws Throwable
	{
		onEdt(() ->
		{
			add(Chat.Role.USER, "Q1");
			Chat.Message a1 = add(Chat.Role.ASSISTANT, "A1");
			add(Chat.Role.USER, "Q2");
			Chat.Message a2 = add(Chat.Role.ASSISTANT, "A2");
			OsrsAiBuddyPanel panel = new OsrsAiBuddyPanel(host);
			assertTrue(panel.row(a2).offersRetry());
			assertFalse("never an older reply", panel.row(a1).offersRetry());

			MessageRow.Reply latest = (MessageRow.Reply) panel.row(a2);
			latest.actions.retry.doClick();
			assertEquals(Arrays.asList(a2), host.retried);

			// A question after it that failed: Retry moves to the error, which sends that question again.
			Chat.Message q3 = add(Chat.Role.USER, "Q3");
			q3.unanswered = true;
			Chat.Message error = add(Chat.Role.ERROR, "Couldn't reach Anthropic.");
			panel.refreshAll();
			assertFalse(panel.row(a2).offersRetry());
			assertTrue(panel.row(error).offersRetry());
			((MessageRow.Problem) panel.row(error)).retry.doClick();
			assertSame(error, host.retried.get(1));

			// Stopped: Retry goes on the note.
			host.current.messages.remove(error);
			Chat.Message stopped = add(Chat.Role.NOTE, "Stopped.");
			panel.refreshAll();
			assertTrue(panel.row(stopped).offersRetry());

			// Nothing while a reply is on its way.
			host.current.messages.remove(stopped);
			host.current.messages.remove(q3);
			host.current.pending = new ChatApi.Pending();
			panel.refreshAll();
			assertFalse(panel.row(a2).offersRetry());
		});
	}

	@Test
	public void theLatestRepliesActionsAlwaysShowAndOlderOnesOnHover() throws Throwable
	{
		onEdt(() ->
		{
			add(Chat.Role.USER, "Q1");
			Chat.Message a1 = add(Chat.Role.ASSISTANT, "A1 **bold**");
			add(Chat.Role.USER, "Q2");
			Chat.Message a2 = add(Chat.Role.ASSISTANT, "A2");
			OsrsAiBuddyPanel panel = new OsrsAiBuddyPanel(host);
			MessageRow.Reply older = (MessageRow.Reply) panel.row(a1);
			MessageRow.Reply latest = (MessageRow.Reply) panel.row(a2);
			assertTrue(latest.actions.isShown());
			assertTrue(latest.actions.copy.isVisible());
			assertFalse(older.actions.isShown());
			int height = older.actions.getPreferredSize().height;
			assertTrue("its place is kept", height > 0);

			older.setSize(200, 100);
			older.dispatchEvent(new MouseEvent(older, MouseEvent.MOUSE_ENTERED, 0, 0, 10, 10, 0, false));
			assertTrue(older.actions.isShown());
			assertTrue(older.actions.copy.isVisible());
			assertFalse("Copy, but no Retry on an older reply", older.actions.retry.isVisible());
			assertEquals("the transcript doesn't jump", height, older.actions.getPreferredSize().height);
			// Over a part of the row is still over the row.
			older.body.setBounds(0, 0, 200, 20);
			older.body.dispatchEvent(new MouseEvent(older.body, MouseEvent.MOUSE_EXITED, 0, 0, 10, 30, 0, false));
			assertTrue(older.actions.isShown());
			older.dispatchEvent(new MouseEvent(older, MouseEvent.MOUSE_EXITED, 0, 0, 10, 300, 0, false));
			assertFalse(older.actions.isShown());

			// Who answered and when, in the tooltip rather than a header line.
			assertEquals(PanelText.tooltip(a1), older.body.getToolTipText());
			assertTrue(older.body.getToolTipText().startsWith("Assistant · "));
			// Copy is the whole reply as plain text (what's copied; the clipboard itself isn't touched here).
			assertEquals("A1 bold", older.body.plainText());
		});
	}

	@Test
	public void theBannerShowsProblemsUntilFixedAndTestResultsUntilClosed() throws Throwable
	{
		onEdt(() ->
		{
			host.problem = "Turn on \"Enable AI requests\" in the OSRS AI Buddy settings, then choose a provider and add your API key.";
			OsrsAiBuddyPanel panel = new OsrsAiBuddyPanel(host);
			assertTrue(panel.banner.isVisible());
			assertEquals(host.problem + " Open RuneLite's settings (the wrench) and search for OSRS AI Buddy.",
				panel.banner.text.getText());
			assertFalse("only fixing it makes it go", panel.banner.close.isVisible());

			host.problem = null;
			panel.refreshAll();
			assertFalse("nothing to say", panel.banner.isVisible());

			host.note = new ConnectionCheck.Note(ConnectionCheck.Kind.OK, "Connected to Anthropic. claude-opus-5-5 is available.");
			panel.refreshAll();
			assertTrue(panel.banner.isVisible());
			assertEquals(host.note.text, panel.banner.text.getText());
			assertEquals(PanelStyle.OK_COLOR, panel.banner.text.getForeground());
			assertTrue(panel.banner.close.isVisible());
			panel.banner.close.doClick();
			assertTrue(host.dismissed);
			assertFalse(panel.banner.isVisible());

			host.dismissed = false;
			host.note = new ConnectionCheck.Note(ConnectionCheck.Kind.ERROR, "Anthropic didn't accept your API key.");
			panel.refreshAll();
			assertEquals(PanelStyle.ERROR_COLOR, panel.banner.text.getForeground());
		});
	}

	@Test
	public void aMessageThatCantGoYetPointsToTheBannerRatherThanRepeatIt() throws Throwable
	{
		onEdt(() ->
		{
			host.problem = "Add your Claude API key in the Claude section of the OSRS AI Buddy settings (from console.anthropic.com).";
			OsrsAiBuddyPanel panel = new OsrsAiBuddyPanel(host);
			panel.notSetUp();
			assertTrue(panel.composer.note.isVisible());
			assertEquals("OSRS AI Buddy isn't set up yet: see the note at the top.", panel.composer.note.getText());
			assertTrue("said once, at the top", panel.banner.text.getText().startsWith(host.problem));

			// A Test's result has the banner's place: the note says what's missing itself.
			host.problem = "Pick a Claude model next to Send, or set one in the Claude section of the OSRS AI Buddy settings.";
			host.note = new ConnectionCheck.Note(ConnectionCheck.Kind.OK, "Connected to Anthropic.");
			panel.refreshAll();
			panel.notSetUp();
			assertEquals(host.note.text, panel.banner.text.getText());
			assertEquals(host.problem, panel.composer.note.getText());
		});
	}

	@Test
	public void thePlayersBubblesHugShortTextOnTheRight() throws Throwable
	{
		onEdt(() ->
		{
			Chat.Message shortOne = add(Chat.Role.USER, "hi");
			Chat.Message longOne = add(Chat.Role.USER, "How do I get an abyssal whip, and what Slayer level do I need for "
				+ "abyssal demons in the catacombs?");
			OsrsAiBuddyPanel panel = new OsrsAiBuddyPanel(host);
			MessageRow.Question row = (MessageRow.Question) panel.row(shortOne);
			MessageRow.Bubble bubble = row.bubble;
			int padding = bubble.getInsets().left + bubble.getInsets().right;
			assertTrue("hugs the text", bubble.fittedWidth(200) < 60);
			assertTrue(bubble.fittedWidth(200) >= bubble.body.naturalWidth() + padding);
			assertEquals("at most 85% of the row", 170, ((MessageRow.Question) panel.row(longOne)).bubble.fittedWidth(200));

			// Laid out on the right, and as tall as the wrapped text inside it needs.
			JPanel holder = new JPanel(new StackLayout(0));
			holder.add(panel.row(longOne));
			holder.setSize(200, 1000);
			holder.doLayout();
			panel.row(longOne).doLayout();
			MessageRow.Bubble big = ((MessageRow.Question) panel.row(longOne)).bubble;
			assertEquals(170, big.getWidth());
			assertEquals(200, big.getX() + big.getWidth());
			assertEquals(StackLayout.heightFor(big.body, 170 - padding) + big.getInsets().top + big.getInsets().bottom,
				big.getHeight());
		});
	}

	@Test
	public void theReplyOnItsWayThinksThenWrites() throws Throwable
	{
		onEdt(() ->
		{
			add(Chat.Role.USER, "q");
			host.current.pending = new ChatApi.Pending();
			host.current.runStartedAt = System.currentTimeMillis();
			host.current.answering = "Claude";
			OsrsAiBuddyPanel panel = new OsrsAiBuddyPanel(host);
			MessageRow.Reply live = panel.liveRow();
			assertNotNull(live);
			assertTrue(live.status.isVisible());
			assertTrue(live.status.getSource(), live.status.getSource().startsWith("Thinking"));
			assertFalse(live.body.isVisible());
			assertFalse("nothing to copy yet", live.actions.isVisible());

			host.current.liveText = "Abyssal demons drop it";
			host.current.liveActivity.add("Read the Wiki page \"Abyssal whip\"");
			panel.refreshLive(host.current);
			assertSame("updated in place", live, panel.liveRow());
			assertFalse(live.status.isVisible());
			assertEquals("Abyssal demons drop it", live.body.getSource());
			assertEquals("Looked up Abyssal whip", live.activity.lineText());

			host.current.lookingUp = true;
			panel.refreshLive(host.current);
			assertTrue(live.status.getSource(), live.status.getSource().startsWith("Looking things up… "));
		});
	}

	@Test
	public void notesAreShortAndASummaryIsAClickAway() throws Throwable
	{
		onEdt(() ->
		{
			Chat.Message note = add(Chat.Role.NOTE, "Summary of the 24 earlier messages, sent instead of them:\n\nThe "
				+ "player is training Agility.");
			add(Chat.Role.USER, "q");
			OsrsAiBuddyPanel panel = new OsrsAiBuddyPanel(host);
			ShowMore text = ((MessageRow.Note) panel.row(note)).text;
			assertEquals("Summary of 24 earlier messages", text.lineText());
			assertEquals(Glyph.Shape.CHEVRON_DOWN, text.chevron());
			click(text);
			assertEquals("The player is training Agility.", text.more.getSource());
			assertTrue(text.more.isVisible());
			assertEquals(Glyph.Shape.CHEVRON_UP, text.chevron());
		});
	}

	@Test
	public void aQuestionJustSentIsFollowedButANoteAboveItIsNot()
	{
		Chat chat = new Chat("x");
		Chat.Message question = new Chat.Message(Chat.Role.USER, "q");
		chat.messages.add(question);
		Map<Chat.Message, Object> shown = new IdentityHashMap<>();
		assertTrue("just sent", OsrsAiBuddyPanel.follow(chat, false, false, shown));

		// The summary comes back while the player has scrolled up: its note goes in above the question, already shown.
		shown.put(question, new Object());
		chat.messages.add(0, new Chat.Message(Chat.Role.NOTE, "Summary of the 40 earlier messages, sent instead of them:"));
		assertFalse("scrolled up", OsrsAiBuddyPanel.follow(chat, false, false, shown));
		assertTrue("reading the end", OsrsAiBuddyPanel.follow(chat, false, true, shown));
		assertTrue("another chat", OsrsAiBuddyPanel.follow(chat, true, false, shown));
		assertFalse(OsrsAiBuddyPanel.follow(new Chat("empty"), false, false, shown));
	}

	private static void click(ShowMore s)
	{
		s.line.dispatchEvent(new MouseEvent(s.line, MouseEvent.MOUSE_CLICKED, 0, InputEvent.BUTTON1_DOWN_MASK, 5, 5, 1,
			false, MouseEvent.BUTTON1));
	}

	/**
	 * The text {@code v} shows, from its document. Not getText(), which writes the text out as a file would be, with
	 * the system's line ends: "\r\n" on Windows.
	 */
	private static String shown(MessageView v)
	{
		Document d = v.getDocument();
		try
		{
			return d.getText(0, d.getLength());
		}
		catch (BadLocationException e)
		{
			throw new AssertionError(e);
		}
	}

	/** How wide {@code s} must be for {@code words} to fit on its line with the chevron, as the line measures them. */
	private static int room(ShowMore s, String words)
	{
		Insets in = s.line.getInsets();
		int text = s.line.getFontMetrics(PanelStyle.SMALL_FONT).stringWidth(words + ShowMore.CHEVRON_ROOM);
		return text + in.left + in.right + ShowMore.SPARE_WIDTH;
	}

	@Test
	public void whatAReplyDidIsOneLineThatOpensAndClosesWithItsChevron() throws Throwable
	{
		onEdt(() ->
		{
			ShowMore s = MessageRow.activityLine(StyleConstants.ALIGN_LEFT);
			assertFalse("nothing to show yet", s.isVisible());
			MessageRow.showActivity(s, Collections.emptyList());
			assertFalse("nothing was looked up or shared: no line", s.isVisible());

			List<String> lines = new ArrayList<>(Arrays.asList(
				"Searched the Wiki for \"abyssal whip\"",
				"Read the Wiki page \"Abyssal whip\"",
				"Shared your equipment",
				"Checked the GE price of Dragon bones"));
			MessageRow.showActivity(s, lines);
			assertTrue(s.isVisible());
			assertEquals("Shared your equipment · Looked up Abyssal whip, Dragon bones (GE price)", s.lineText());
			assertEquals("one line, with a chevron to open it", Glyph.Shape.CHEVRON_DOWN, s.chevron());
			assertFalse(s.isOpen());
			assertEquals("Click to see everything that was shared and looked up for this message",
				s.line.getToolTipText());
			int closed = StackLayout.heightFor(s, 600);

			click(s);
			assertTrue(s.isOpen());
			assertEquals(Glyph.Shape.CHEVRON_UP, s.chevron());
			assertEquals("the line stays as it was",
				"Shared your equipment · Looked up Abyssal whip, Dragon bones (GE price)", s.lineText());
			// Each line as recorded, then the look-ups one kind to a line; plain text, never read as HTML.
			assertEquals("Shared your equipment\nWiki pages: Abyssal whip\nWiki searches: \"abyssal whip\"\n"
				+ "GE prices: Dragon bones", shown(s.more));
			assertEquals(Boolean.TRUE, s.more.getClientProperty("html.disable"));
			assertTrue("taller when open", StackLayout.heightFor(s, 600) > closed);

			// More for a reply still on its way: the open list takes it in and stays open.
			lines.add("Searched your bank for \"rune\"");
			MessageRow.showActivity(s, lines);
			assertTrue(s.isOpen());
			assertEquals("Shared your equipment\nSearched your bank for \"rune\"\nWiki pages: Abyssal whip\n"
				+ "Wiki searches: \"abyssal whip\"\nGE prices: Dragon bones", shown(s.more));

			click(s);
			assertFalse(s.isOpen());
			assertEquals(Glyph.Shape.CHEVRON_DOWN, s.chevron());
			assertEquals(closed, StackLayout.heightFor(s, 600));
		});
	}

	@Test
	public void theLineFitsItsWidthAndNeverCutsWhatWasShared() throws Throwable
	{
		// Every width here is worked out from the font, as the line works it out: fonts differ from one system to the
		// next (Ubuntu's are wider than macOS's or Windows's), and the line must fit its width in any of them.
		onEdt(() ->
		{
			List<String> lines = Arrays.asList(
				"Read the Wiki page \"Vorkath/Strategies\"",
				"Shared your equipment",
				"Read the Wiki page \"Dragon hunter crossbow\"",
				"Checked the GE price of Dragon hunter crossbow");
			ShowMore s = MessageRow.activityLine(StyleConstants.ALIGN_LEFT);
			MessageRow.showActivity(s, lines);
			String named = PanelText.summary(lines).line(t -> true);
			int oneRow = StackLayout.heightFor(s, 2000);
			assertEquals(named, s.lineText());

			// Too narrow for the names: counted, on one row, with the chevron.
			String counted = "Shared your equipment · Looked up 3 things";
			assertTrue("the names are longer than the count", room(s, named) > room(s, counted));
			assertEquals(oneRow, StackLayout.heightFor(s, room(s, counted)));
			assertEquals(counted, s.lineText());
			// Narrower than what was shared: that's never cut, so the line takes two rows, the count on its own.
			int wrapped = StackLayout.heightFor(s, room(s, "Shared your equipment") - 1);
			assertEquals("Shared your equipment\n3 look-ups", s.lineText());
			assertTrue("two rows", wrapped > oneRow);
			// Even narrower than its words: they wrap, and all of them are still there.
			wrapped = StackLayout.heightFor(s, room(s, "Shared your") - 1);
			assertEquals("Shared your equipment\n3 look-ups", s.lineText());
			assertTrue("more rows", wrapped > oneRow);

			// One look-up, after more that was shared. As the width shrinks, the look-up is named, counted, then left
			// to an ellipsis or left off, on one row, never cut down to "Looked up…" or less; only once what was shared
			// can't fit by itself does the line take two rows.
			MessageRow.showActivity(s, Arrays.asList("Shared your equipment", "Shared your inventory",
				"Read the Wiki page \"Vorkath\""));
			String shared = "Shared your equipment and inventory";
			List<String> steps = Arrays.asList(shared + " · Looked up Vorkath", shared + " · Looked up 1 thing",
				shared + " · 1 look-up", shared + " · …", shared + "…", shared);
			for (String step : steps)
			{
				// Just wide enough for this step, then a pixel short of it: the first step that fits, in that order.
				for (int width : new int[]{room(s, step), room(s, step) - 1})
				{
					String fits = steps.stream().filter(t -> room(s, t) <= width).findFirst().orElse(null);
					int height = StackLayout.heightFor(s, width);
					if (fits != null)
					{
						assertEquals(step + " at " + width, fits, s.lineText());
						assertEquals(fits, oneRow, height);
					}
					else
					{
						assertEquals(shared + "\n1 look-up", s.lineText());
						assertTrue("two rows", height > oneRow);
					}
				}
			}
		});
	}

	@Test
	public void aLineThatSaysItAllHasNothingToClick() throws Throwable
	{
		onEdt(() ->
		{
			ShowMore s = new ShowMore("Click to see everything", StyleConstants.ALIGN_LEFT);
			s.show("Summary of 2 earlier messages", "The player asked about whips.");
			click(s);
			assertTrue(s.isOpen());

			// A note with nothing more to it: the open block goes, there's no chevron, and a click does nothing.
			s.show("Stopped.", null);
			assertEquals("Stopped.", s.lineText());
			assertEquals("Stopped.", s.line.getSource());
			assertFalse(s.isOpen());
			assertNull(s.chevron());
			assertNull(s.line.getToolTipText());
			click(s);
			assertFalse(s.isOpen());

			s.show((String) null, "details");
			assertFalse(s.isVisible());
		});
	}

	@Test
	public void theCharacterDetailsOpenTheSameWayUnderTheBubble() throws Throwable
	{
		onEdt(() ->
		{
			Chat.Message q = add(Chat.Role.USER, "What should I train?");
			q.context = "[Character: Zezima]\nCombat level: 126";
			OsrsAiBuddyPanel panel = new OsrsAiBuddyPanel(host);
			ShowMore s = ((MessageRow.Question) panel.row(q)).shared;
			assertEquals("Sent your character details", s.lineText());
			assertEquals(Glyph.Shape.CHEVRON_DOWN, s.chevron());
			click(s);
			assertEquals("Sent your character details", s.lineText());
			assertEquals(Glyph.Shape.CHEVRON_UP, s.chevron());
			assertEquals("[Character: Zezima]\nCombat level: 126", shown(s.more));
			assertTrue(s.more.isVisible());
		});
	}
}

