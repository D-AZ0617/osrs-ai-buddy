package com.osrsaibuddy;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.FontMetrics;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Rectangle;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.ScrollPaneConstants;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.PluginPanel;

/**
 * The sidebar, laid out like a chat app: the chat's title (which picks another chat), New chat and a menu at the top,
 * a banner when the setup needs something, the transcript, and the composer at the bottom. Everything here runs on the
 * Swing EDT. The rows ({@link MessageRow}), the composer ({@link Composer}) and the rest are their own classes; this one
 * puts them together and keeps them in step with the plugin.
 */
class OsrsAiBuddyPanel extends PluginPanel
{
	/** How close to the end of the transcript still counts as reading the end, in pixels. */
	private static final int BOTTOM_SLACK = 24;
	private static final String CHAT_CARD = "chat";
	private static final String EMPTY_CARD = "empty";
	private static final String SETTINGS_HINT = " Open RuneLite's settings (the wrench) and search for OSRS AI Buddy.";
	/** Above the input box when a message can't go yet because of what the banner says. */
	static final String NOT_SET_UP = "OSRS AI Buddy isn't set up yet: see the note at the top.";

	/** What the panel needs from the plugin. All on the EDT. */
	interface Host
	{
		List<Chat> chats();

		/** The chat shown, or null. */
		Chat currentChat();

		/** What's missing before messages can be sent, or null. */
		String setupProblem();

		/** What's missing before Test connection can ask the provider, or null. */
		String testProblem();

		/** What the latest Test says, for the banner, or null. */
		ConnectionCheck.Note connectionNote();

		/** The player closed the Test's banner. */
		void dismissNote();

		/** The model new messages go to, the models to offer, and the picker's tooltip. */
		String model();

		List<String> modelChoices();

		String modelTip();

		/** Why the list of models to offer is short, for the picker's menu, or null. */
		String modelNote();

		/** The player asked for the provider's list of models again. */
		void refreshModels();

		/** Sends {@code text} in the current chat; false if it can't go now. */
		boolean send(String text);

		void stop();

		/** Retry on {@code m}, the chat's last message. */
		void retry(Chat chat, Chat.Message m);

		void newChat();

		void selectChat(Chat chat);

		void renameChat(Chat chat, String name);

		void clearChat(Chat chat);

		void deleteChat(Chat chat);

		void testConnection();

		void chooseModel(String model);

		default String chatgptStatus() { return "Not connected"; }
		default boolean chatgptConnected() { return false; }
		default boolean chatgptSigningIn() { return false; }
		default void signInChatgpt() { }
		default void signOutChatgpt() { }
		default void manageChatgptUsage() { }
	}

	private final Host host;

	private final DefaultComboBoxModel<Chat> chatModel = new DefaultComboBoxModel<>();
	final JComboBox<Chat> chatSelect = new TitleSelect(chatModel);
	final FlatButton newChat = new FlatButton(null, new Glyph(Glyph.Shape.PLUS, 14), "New chat");
	final FlatButton menu = new FlatButton(null, new Glyph(Glyph.Shape.MORE, 14),
		"Rename, clear or delete this chat, or test the connection");
	final Banner banner;
	private final CardLayout cards = new CardLayout();
	private final JPanel center = new JPanel(cards);
	private final JPanel transcript = new TranscriptPanel();
	final JScrollPane transcriptScroll = new JScrollPane(transcript);
	/** Over the transcript's bottom right corner while the player isn't reading its end: takes them there. */
	final FlatButton jump = new FlatButton(null, new Glyph(Glyph.Shape.DOWN, 14), "Jump to the latest message")
		.filled(PanelStyle.FIELD_COLOR, PanelStyle.OUTLINE_COLOR, true);
	final EmptyChat empty;
	final Composer composer;
	/** Redraws the line under the reply on its way ("Thinking...", the seconds) while there is one. */
	private final Timer ticker = new Timer(1000, e -> refreshLive(currentChat()));

	private boolean updatingCombo;
	/** What the transcript currently shows, to skip rebuilding it when nothing changed. */
	private String shownKey = "";
	private Chat shownChat;
	private String comboKey = "";
	/** The transcript's rows, kept between rebuilds so a new message doesn't redraw every earlier one. */
	private final Map<Chat.Message, MessageRow> rows = new IdentityHashMap<>();
	/** The reply on its way, at the end of the transcript; null when none is. */
	private MessageRow.Reply live;
	/** Scrolls down to the end that are queued and haven't run yet. */
	private int follows;

	OsrsAiBuddyPanel(Host host)
	{
		super(false);
		this.host = host;
		banner = new Banner(() ->
		{
			host.dismissNote();
			refreshSetup();
		});
		composer = new Composer(new Composer.Actions()
		{
			@Override
			public boolean send(String text)
			{
				return host.send(text);
			}

			@Override
			public void stop()
			{
				host.stop();
			}

			@Override
			public void chooseModel(String model)
			{
				host.chooseModel(model);
			}

			@Override
			public void refreshModels()
			{
				host.refreshModels();
			}

			@Override
			public void inputResizing()
			{
				// The transcript gets shorter or taller as the input box grows or shrinks: a player reading its end
				// stays at the end, rather than have the latest lines slide under the box.
				if (atBottom())
				{
					scrollToBottom();
				}
			}
		});
		// A starter only fills the input box: the player sends it.
		empty = new EmptyChat(composer::fill);

		setLayout(new BorderLayout(0, 8));
		setBorder(new EmptyBorder(6, 8, 8, 8));
		setBackground(PanelStyle.BACKGROUND);

		JPanel top = new JPanel(new StackLayout(6));
		top.setOpaque(false);
		top.add(header());
		top.add(banner);
		add(top, BorderLayout.NORTH);

		transcript.setLayout(new StackLayout(12));
		transcript.setBackground(PanelStyle.BACKGROUND);
		transcript.setBorder(new EmptyBorder(4, 0, 8, 2));
		transcriptScroll.setBorder(null);
		transcriptScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		transcriptScroll.getVerticalScrollBar().setUnitIncrement(16);
		transcriptScroll.getViewport().setBackground(PanelStyle.BACKGROUND);
		transcriptScroll.getVerticalScrollBar().getModel().addChangeListener(e -> refreshJump());
		jump.setBorder(new EmptyBorder(6, 6, 6, 6));
		jump.setForeground(PanelStyle.TEXT_COLOR);
		jump.setVisible(false);
		jump.addActionListener(e -> scrollToBottom());
		center.setOpaque(false);
		center.add(new Overlay(), CHAT_CARD);
		JPanel middle = new JPanel(new GridBagLayout());
		middle.setOpaque(false);
		GridBagConstraints c = new GridBagConstraints();
		c.fill = GridBagConstraints.HORIZONTAL;
		c.weightx = 1;
		middle.add(empty, c);
		center.add(middle, EMPTY_CARD);
		add(center, BorderLayout.CENTER);

		add(composer, BorderLayout.SOUTH);
		refreshAll();
	}

	/** The chat's title, which is also the list of chats; New chat; and the menu. */
	private JComponent header()
	{
		JPanel row = new JPanel(new BorderLayout(4, 0));
		row.setOpaque(false);
		chatSelect.setRenderer(new DefaultListCellRenderer()
		{
			{
				// Chat names come from what the player typed; never let Swing render them as HTML.
				putClientProperty("html.disable", Boolean.TRUE);
			}

			@Override
			public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus)
			{
				super.getListCellRendererComponent(list, value, index, selected, focus);
				putClientProperty("html.disable", Boolean.TRUE);
				if (value instanceof Chat)
				{
					Chat chat = (Chat) value;
					// In the box itself (index -1) it's the chat's title; in the list, one chat among others.
					boolean title = index < 0;
					setFont(title ? PanelStyle.TITLE_FONT : PanelStyle.TEXT_FONT);
					setText(chat.name + (!title && chat.isRunning() ? "  (waiting)" : ""));
				}
				return this;
			}
		});
		chatSelect.addActionListener(e ->
		{
			Object sel = chatSelect.getSelectedItem();
			if (!updatingCombo && sel instanceof Chat)
			{
				host.selectChat((Chat) sel);
			}
		});
		chatSelect.setToolTipText("Your chats: pick one to go back to it");
		chatSelect.setFont(PanelStyle.TITLE_FONT);
		chatSelect.setForeground(PanelStyle.TEXT_COLOR);
		chatSelect.setBackground(PanelStyle.BACKGROUND);
		// No box around it: it reads as the chat's title, with the arrow saying it opens. FlatLaf, which RuneLite's look
		// and feel is built on, would also draw the arrow on a box of its own colour, and make the whole thing at least
		// 72 pixels wide; other looks ignore both.
		chatSelect.setBorder(new EmptyBorder(0, 0, 0, 0));
		chatSelect.putClientProperty("FlatLaf.style", Collections.singletonMap("buttonBackground", PanelStyle.BACKGROUND));
		chatSelect.putClientProperty("JComponent.minimumWidth", 0);
		// As wide as the title needs, so the arrow comes right after it; a long title is cut to the room beside the
		// buttons.
		JPanel title = new JPanel(null)
		{
			@Override
			public void doLayout()
			{
				chatSelect.setBounds(0, 0, Math.min(chatSelect.getPreferredSize().width, getWidth()), getHeight());
			}

			@Override
			public Dimension getPreferredSize()
			{
				return chatSelect.getPreferredSize();
			}
		};
		title.setOpaque(false);
		title.add(chatSelect);
		row.add(title, BorderLayout.CENTER);

		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
		buttons.setOpaque(false);
		for (FlatButton b : new FlatButton[]{newChat, menu})
		{
			b.setForeground(PanelStyle.TEXT_COLOR);
			b.setBorder(new EmptyBorder(5, 5, 5, 5));
			buttons.add(b);
		}
		newChat.addActionListener(e -> host.newChat());
		menu.addActionListener(e -> showMenu(menu));
		row.add(buttons, BorderLayout.EAST);
		return row;
	}

	/** Bring everything in line with the plugin's latest state. Cheap when nothing changed. */
	void refreshAll()
	{
		refreshSetup();
		refreshChats();
		refreshTranscript();
		refreshStatus();
	}

	/**
	 * Only the reply on its way changed: its text as it streams in, what it looked up, or what it's doing. Redraws the
	 * live reply alone, and follows it down only if the player was reading the end of the transcript.
	 */
	void refreshLive(Chat chat)
	{
		if (chat != null && chat == host.currentChat() && chat == shownChat && live != null)
		{
			boolean follow = atBottom();
			showLive(chat);
			transcript.revalidate();
			transcript.repaint();
			if (follow)
			{
				scrollToBottom();
			}
		}
		refreshStatus();
	}

	/** The banner, and the model picker. */
	private void refreshSetup()
	{
		ConnectionCheck.Note note = host.connectionNote();
		String problem = host.setupProblem();
		if (note != null)
		{
			banner.show(note.text, color(note.kind), true);
		}
		else if (problem != null)
		{
			// Until it's fixed: there's nothing else the player can do here.
			banner.show(problem + SETTINGS_HINT, PanelStyle.WARNING_COLOR, false);
		}
		else
		{
			banner.show(null, null, false);
		}
		// Refresh list asks the provider, as Test connection does: not while that can't be done.
		composer.models.show(host.model(), host.modelChoices(), host.modelTip(), host.modelNote(), host.testProblem());
		chatSelect.setEnabled(host.currentChat() != null);
	}

	private void refreshChats()
	{
		Chat currentChat = host.currentChat();
		StringBuilder key = new StringBuilder(currentChat == null ? "" : currentChat.id);
		for (Chat c : host.chats())
		{
			key.append('|').append(c.id).append(':').append(c.name).append(':').append(c.isRunning());
		}
		if (key.toString().equals(comboKey))
		{
			return;
		}
		comboKey = key.toString();
		updatingCombo = true;
		try
		{
			chatModel.removeAllElements();
			for (Chat c : host.chats())
			{
				chatModel.addElement(c);
			}
			if (currentChat != null)
			{
				chatModel.setSelectedItem(currentChat);
			}
		}
		finally
		{
			updatingCombo = false;
		}
	}

	/** What the transcript shows of a chat: when this changes, it's rebuilt. */
	private static String transcriptKey(Chat chat)
	{
		if (chat == null)
		{
			return "";
		}
		int unanswered = 0;
		int summarized = 0;
		int looked = 0;
		int shared = 0;
		for (Chat.Message m : chat.messages)
		{
			unanswered += m.unanswered ? 1 : 0;
			summarized += m.summarized ? 1 : 0;
			looked += m.activity == null ? 0 : m.activity.size();
			shared += m.context != null ? 1 : 0;
		}
		// Which message is last, too: Retry goes on it, and one can take another's place (a reply written again).
		int last = chat.messages.isEmpty() ? 0 : System.identityHashCode(chat.messages.get(chat.messages.size() - 1));
		return chat.id + ":" + chat.messages.size() + ":" + chat.isRunning() + ":" + unanswered + ":" + summarized + ":"
			+ looked + ":" + shared + ":" + last;
	}

	private void refreshTranscript()
	{
		Chat chat = host.currentChat();
		String key = transcriptKey(chat);
		if (key.equals(shownKey))
		{
			return;
		}
		boolean switched = chat != shownChat;
		boolean follow = follow(chat, switched, atBottom(), rows);
		shownKey = key;
		shownChat = chat;

		transcript.removeAll();
		live = null;
		Map<Chat.Message, MessageRow> kept = new IdentityHashMap<>();
		boolean none = chat == null || chat.messages.isEmpty();
		cards.show(center, none ? EMPTY_CARD : CHAT_CARD);
		if (!none)
		{
			Chat.Message last = chat.messages.get(chat.messages.size() - 1);
			// Retry goes on the last message: the error or note after a question that wasn't answered, or the latest
			// reply, which it writes again.
			boolean retry = RequestRunner.retryable(chat) != null || RequestRunner.regenerable(chat) != null;
			Chat.Message latest = latestReply(chat);
			for (Chat.Message m : chat.messages)
			{
				MessageRow row = rows.get(m);
				if (row == null)
				{
					row = MessageRow.of(m, this::retry);
				}
				row.show(m, m == latest, retry && m == last);
				kept.put(m, row);
				transcript.add(row);
			}
			if (chat.isRunning())
			{
				live = new MessageRow.Reply(this::retry);
				transcript.add(live);
				showLive(chat);
			}
		}
		rows.clear();
		rows.putAll(kept);
		transcript.revalidate();
		transcript.repaint();
		if (follow)
		{
			scrollToBottom();
		}
	}

	/** The chat's latest reply, whose Copy and Retry always show; null if it has none. */
	private static Chat.Message latestReply(Chat chat)
	{
		for (int i = chat.messages.size() - 1; i >= 0; i--)
		{
			if (chat.messages.get(i).role == Chat.Role.ASSISTANT)
			{
				return chat.messages.get(i);
			}
		}
		return null;
	}

	/**
	 * Whether a rebuilt transcript goes down to its end: for another chat, a question just sent (the last message, not
	 * shown before), or a player who was reading the end anyway. Not for a note put in above a question already shown
	 * (a summary that came back), which would pull a player who has scrolled up away from what they're reading.
	 */
	static boolean follow(Chat chat, boolean switched, boolean atBottom, Map<Chat.Message, ?> shown)
	{
		if (switched || atBottom)
		{
			return true;
		}
		if (chat == null || chat.messages.isEmpty())
		{
			return false;
		}
		Chat.Message last = chat.messages.get(chat.messages.size() - 1);
		return last.role == Chat.Role.USER && !shown.containsKey(last);
	}

	/** The reply on its way: "Thinking..." until its first words, then the words, with what it looked up over them. */
	private void showLive(Chat chat)
	{
		live.showLive(chat.answering, chat.liveText, chat.liveActivity, PanelText.live(chat, System.currentTimeMillis()));
	}

	/** Whether the player is reading the end of the transcript (or it all fits). */
	private boolean atBottom()
	{
		JScrollBar bar = transcriptScroll.getVerticalScrollBar();
		return bar.getValue() + bar.getVisibleAmount() >= bar.getMaximum() - BOTTOM_SLACK;
	}

	private void scrollToBottom()
	{
		// Until it's there, what the transcript grows by isn't the player scrolling up: Jump to the latest message
		// stays hidden, rather than flash for a frame each time a question is sent or a reply grows by a paragraph.
		follows++;
		refreshJump();
		// After the layout the change asked for, which is queued before this.
		SwingUtilities.invokeLater(() ->
		{
			JScrollBar bar = transcriptScroll.getVerticalScrollBar();
			bar.setValue(bar.getMaximum());
			follows--;
			refreshJump();
		});
	}

	/** Jump to the latest message shows whenever the player isn't reading the end, and isn't on the way there. */
	private void refreshJump()
	{
		boolean show = follows == 0 && !atBottom();
		if (show != jump.isVisible() && jump.getParent() != null)
		{
			jump.setVisible(show);
			jump.getParent().revalidate();
			jump.getParent().repaint();
		}
	}

	/** The composer's button (Send, Stop or Skip), and the ticker for the reply on its way. */
	void refreshStatus()
	{
		Chat chat = currentChat();
		boolean running = chat != null && chat.isRunning();
		composer.setState(chat != null, running, running && chat.isSummarizing());
		if (!running)
		{
			ticker.stop();
		}
		else if (!ticker.isRunning())
		{
			ticker.start();
		}
	}

	private Chat currentChat()
	{
		return host.currentChat();
	}

	/** A short error above the input box, e.g. when a message can't be sent yet; null clears it. */
	void showNote(String text)
	{
		composer.showNote(text);
	}

	/**
	 * A message couldn't go because OSRS AI Buddy isn't set up. The banner says what's missing until it's fixed: the note
	 * above the input box only points there, rather than say it again in red. While a Test's result has the banner's
	 * place, the note says what's missing itself.
	 */
	void notSetUp()
	{
		showNote(host.connectionNote() == null ? NOT_SET_UP : host.setupProblem());
	}

	void focusInput()
	{
		composer.input.requestFocusInWindow();
	}

	/** Put a message that couldn't be sent back in the input box, after anything already there. */
	void restoreDraft(String text)
	{
		composer.restoreDraft(text);
	}

	/** The row showing {@code m}, or null; for tests. */
	MessageRow row(Chat.Message m)
	{
		return rows.get(m);
	}

	/** The reply on its way, or null; for tests. */
	MessageRow.Reply liveRow()
	{
		return live;
	}

	/** Whether the empty chat's welcome shows, rather than the transcript. */
	boolean showsEmpty()
	{
		return empty.getParent().isVisible();
	}

	private void retry(Chat.Message m)
	{
		if (shownChat != null)
		{
			host.retry(shownChat, m);
		}
	}

	private void showMenu(JComponent anchor)
	{
		Chat chat = host.currentChat();
		if (chat == null)
		{
			return;
		}
		JPopupMenu popup = new JPopupMenu();
		popup.add(PanelStyle.menuItem("Rename…")).addActionListener(e ->
		{
			String name = JOptionPane.showInputDialog(this, "Chat name:", chat.name);
			if (name != null && !name.trim().isEmpty())
			{
				host.renameChat(chat, name.trim());
			}
		});
		popup.add(PanelStyle.menuItem("Clear chat")).addActionListener(e ->
		{
			if (confirm("OSRS AI Buddy", "Clear \"" + chat.name + "\"? The assistant forgets it too."))
			{
				host.clearChat(chat);
			}
		});
		popup.add(PanelStyle.menuItem("Delete chat")).addActionListener(e ->
		{
			if (confirm("OSRS AI Buddy", "Delete \"" + chat.name + "\"?"))
			{
				host.deleteChat(chat);
			}
		});
		popup.addSeparator();
		// Nothing is sent while AI requests are off, a test included; the tooltip says what's missing.
		JMenuItem account = PanelStyle.menuItem(host.chatgptStatus());
		account.setEnabled(false);
		popup.add(account);
		JMenuItem signIn = PanelStyle.menuItem(host.chatgptSigningIn() ? "Waiting for ChatGPT sign-in…" : "Continue with ChatGPT");
		signIn.setEnabled(!host.chatgptSigningIn());
		signIn.addActionListener(e -> host.signInChatgpt());
		popup.add(signIn);
		JMenuItem usage = PanelStyle.menuItem("Manage ChatGPT usage");
		usage.addActionListener(e -> host.manageChatgptUsage());
		popup.add(usage);
		JMenuItem signOut = PanelStyle.menuItem("Sign out of ChatGPT");
		signOut.setEnabled(host.chatgptConnected() && !host.chatgptSigningIn());
		signOut.addActionListener(e -> host.signOutChatgpt());
		popup.add(signOut);
		popup.addSeparator();
		String problem = host.testProblem();
		JMenuItem test = PanelStyle.menuItem("Test connection");
		test.setEnabled(problem == null);
		test.setToolTipText(problem == null ? "Check the connection, and list the models you can use" : problem);
		test.addActionListener(e -> host.testConnection());
		popup.add(test);
		popup.show(anchor, anchor.getWidth() - popup.getPreferredSize().width, anchor.getHeight());
	}

	/** A confirmation dialog whose text is shown as plain text, never HTML. */
	private boolean confirm(String title, String text)
	{
		JTextArea area = new JTextArea(text);
		area.setEditable(false);
		area.setOpaque(false);
		area.setFont(PanelStyle.TEXT_FONT);
		return JOptionPane.showConfirmDialog(this, area, title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE)
			== JOptionPane.OK_OPTION;
	}

	private static Color color(ConnectionCheck.Kind kind)
	{
		switch (kind)
		{
			case OK:
				return PanelStyle.OK_COLOR;
			case WARNING:
				return PanelStyle.WARNING_COLOR;
			case ERROR:
				return PanelStyle.ERROR_COLOR;
			default:
				return PanelStyle.MUTED_COLOR;
		}
	}

	/** The transcript, with Jump to the latest message floating over its bottom right corner. */
	private final class Overlay extends JPanel
	{
		/** From the transcript's edges, in pixels. */
		private static final int INSET = 8;

		Overlay()
		{
			super(null);
			setOpaque(false);
			// The first child is drawn on top.
			add(jump);
			add(transcriptScroll);
		}

		@Override
		public boolean isOptimizedDrawingEnabled()
		{
			// The button overlaps the transcript: each is drawn in full, in order.
			return false;
		}

		@Override
		public void doLayout()
		{
			transcriptScroll.setBounds(0, 0, getWidth(), getHeight());
			JScrollBar bar = transcriptScroll.getVerticalScrollBar();
			int right = bar.isVisible() ? bar.getWidth() : 0;
			Dimension d = jump.getPreferredSize();
			jump.setBounds(getWidth() - right - INSET - d.width, getHeight() - INSET - d.height, d.width, d.height);
		}

		@Override
		public Dimension getPreferredSize()
		{
			return transcriptScroll.getPreferredSize();
		}
	}

	/**
	 * The chat's title, which opens the list of chats. The look and feel makes it as wide as the longest chat's name; it
	 * only needs the shown chat's, so that its arrow comes right after the title, as in chat apps.
	 */
	private static final class TitleSelect extends JComboBox<Chat>
	{
		TitleSelect(DefaultComboBoxModel<Chat> model)
		{
			super(model);
		}

		@Override
		public Dimension getPreferredSize()
		{
			Dimension d = super.getPreferredSize();
			Object shown = getSelectedItem();
			if (shown instanceof Chat)
			{
				// The titles differ only in their names, all in the title font.
				FontMetrics fm = getFontMetrics(PanelStyle.TITLE_FONT);
				int widest = 0;
				for (int i = 0; i < getItemCount(); i++)
				{
					widest = Math.max(widest, fm.stringWidth(getItemAt(i).name));
				}
				d.width -= widest - fm.stringWidth(((Chat) shown).name);
			}
			return d;
		}
	}

	/** Tracks the viewport width so the wrapped text inside gets a real width. */
	private static class TranscriptPanel extends JPanel implements Scrollable
	{
		@Override
		public void setBounds(int x, int y, int width, int height)
		{
			boolean widthChanged = width != getWidth();
			super.setBounds(x, y, width, height);
			if (widthChanged)
			{
				// Heights depend on the width: measure again now that it's known.
				revalidate();
			}
		}

		@Override
		public Dimension getPreferredScrollableViewportSize()
		{
			return getPreferredSize();
		}

		@Override
		public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction)
		{
			return 16;
		}

		@Override
		public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction)
		{
			return orientation == SwingConstants.VERTICAL ? visible.height : visible.width;
		}

		@Override
		public boolean getScrollableTracksViewportWidth()
		{
			return true;
		}

		@Override
		public boolean getScrollableTracksViewportHeight()
		{
			return false;
		}
	}
}
