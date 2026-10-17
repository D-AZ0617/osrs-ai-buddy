package com.osrsaibuddy;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import javax.swing.AbstractAction;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToolTip;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;

/**
 * The model picker next to Send, as chat apps have it: the model's name and a chevron, with no box, that opens a menu
 * of the provider's models (the one that's set ticked), then "Type a model name…" and "Refresh list". Choosing a
 * model, or typing a name and pressing Enter, sets the provider's model as the settings would. Nothing else does: not
 * what the panel shows itself (a model changed in the settings, a list that came in), not Escape, not leaving the box.
 * Plain text only, menu, box and tooltips alike: model names come from the provider. Swing EDT only.
 */
final class ModelPicker extends JPanel
{
	static final String TYPE = "Type a model name…";
	static final String REFRESH = "Refresh list";
	/** On the button while no model is set. */
	static final String NONE = "Choose a model";
	/** Models the menu shows before its list scrolls. */
	static final int MENU_ROWS = 8;
	/** The menu is at least this wide, even over a narrow picker. */
	private static final int MENU_MIN_WIDTH = 180;
	/** Where the text of the menu's note and of its rows starts: in line with its items' text. */
	private static final int TEXT_INSET = 6;

	/** What the picker asks the panel to do. */
	interface Actions
	{
		/** The player chose {@code model}: the provider's model becomes it. */
		void choose(String model);

		/** The player asked for the provider's list of models again. */
		void refresh();
	}

	/** The model's name and a chevron: opens the menu. */
	final FlatButton button = new FlatButton(NONE, new Glyph(Glyph.Shape.CHEVRON_DOWN, 9), null);
	/** Where a model's name is typed, in place of the button, after "Type a model name…". */
	final NameField field = new NameField();
	private final RoundBox fieldBox = new RoundBox(new BorderLayout(), PanelStyle.FIELD_COLOR, PanelStyle.OUTLINE_COLOR);
	/** Hears the player's choice, a moment later on the EDT (it changes the settings, which redraws the panel). */
	private final Actions actions;
	/** The model that's set, as far as the picker knows. */
	private String current = "";
	private List<String> choices = Collections.emptyList();
	/** Why the list is short, for the menu; null when it isn't. */
	private String note;
	/** Why Refresh list can't ask the provider now, or null when it can. */
	private String refreshProblem;
    boolean allowCustomModels = true;

	ModelPicker(Actions actions)
	{
		super(null);
		this.actions = actions;
		setOpaque(false);
		button.setFont(PanelStyle.SMALL_FONT);
		button.setForeground(PanelStyle.MUTED_COLOR);
		// The chevron after the name, as a menu button's.
		button.setHorizontalTextPosition(SwingConstants.LEADING);
		button.setHorizontalAlignment(SwingConstants.LEFT);
		button.setIconTextGap(4);
		button.setBorder(new EmptyBorder(4, 6, 4, 6));
		button.addActionListener(e -> openMenu());
		add(button);

		field.setFont(PanelStyle.SMALL_FONT);
		field.setForeground(PanelStyle.TEXT_COLOR);
		field.setCaretColor(PanelStyle.TEXT_COLOR);
		field.setOpaque(false);
		field.setBorder(new EmptyBorder(3, 6, 3, 6));
		// Enter saves the name; Escape, or going elsewhere, puts the button back as it was.
		field.addActionListener(e -> typed());
		field.getInputMap().put(KeyStroke.getKeyStroke("ESCAPE"), "ai-chat-cancel");
		field.getActionMap().put("ai-chat-cancel", new AbstractAction()
		{
			@Override
			public void actionPerformed(ActionEvent e)
			{
				stopTyping();
			}
		});
		field.addFocusListener(new FocusAdapter()
		{
			@Override
			public void focusLost(FocusEvent e)
			{
				// Not when the focus is only away for a moment, such as in another window.
				if (!e.isTemporary())
				{
					stopTyping();
				}
			}
		});
		fieldBox.add(field, BorderLayout.CENTER);
		fieldBox.setVisible(false);
		add(fieldBox);
	}

	/**
	 * Shows {@code model}, the one that's set, with {@code choices} for the menu (see {@link ConnectionCheck#choices}),
	 * {@code tip} saying how to choose (see {@link ConnectionCheck#pickerTip}), {@code note} saying why the list is
	 * short or null, and {@code refreshProblem} saying why the list can't be asked for again now, or null. Never taken
	 * for the player's choice, and leaves a name being typed alone.
	 */
	void show(String model, List<String> choices, String tip, String note, String refreshProblem)
	{
		String m = model == null ? "" : model.trim();
		if (!m.equals(current) || !button.getText().equals(m.isEmpty() ? NONE : m))
		{
			current = m;
			button.setText(m.isEmpty() ? NONE : BuddyModels.label(m));
			revalidate();
			repaint();
		}
		this.choices = new ArrayList<>(choices);
		this.note = note;
		this.refreshProblem = refreshProblem;
		button.setToolTipText(tip);
	}

	/** Whether a model's name is being typed, in place of the button. */
	boolean isTyping()
	{
		return fieldBox.isVisible();
	}

	@Override
	public void doLayout()
	{
		int w = getWidth();
		int h = getHeight();
		// As wide as the name needs, so the chevron comes right after it; a long name is cut at its end, with the whole
		// of it in the tooltip.
		Dimension b = button.getPreferredSize();
		button.setBounds(0, (h - b.height) / 2, Math.min(b.width, w), b.height);
		Dimension f = fieldBox.getPreferredSize();
		fieldBox.setBounds(0, (h - f.height) / 2, w, f.height);
	}

	@Override
	public Dimension getPreferredSize()
	{
		Dimension b = button.getPreferredSize();
		return new Dimension(b.width, Math.max(b.height, fieldBox.getPreferredSize().height));
	}

	@Override
	public Dimension getMinimumSize()
	{
		// A long name gives way to the button beside it.
		return new Dimension(0, getPreferredSize().height);
	}

	// ------------------------------------------------------------------
	// The menu
	// ------------------------------------------------------------------

	private void openMenu()
	{
		JPopupMenu menu = menu();
		// Over the composer, from its left edge: the picker sits at the bottom of the panel.
		menu.show(this, 0, -menu.getPreferredSize().height - 2);
	}

	/**
	 * The menu: why the list is short (when it is), the models, then "Type a model name…" and "Refresh list". Made
	 * again each time it opens, from what the picker was last shown.
	 */
	JPopupMenu menu()
	{
		JPopupMenu menu = new JPopupMenu();
		Container row = getParent();
		int width = Math.max(MENU_MIN_WIDTH, row != null ? row.getWidth() : getWidth());
		Insets in = menu.getInsets();
		int inner = width - in.left - in.right;
		if (note != null)
		{
			menu.add(noteArea(note, inner));
		}
		if (!choices.isEmpty())
		{
			menu.add(modelList(menu, inner));
			menu.addSeparator();
		}
		JMenuItem type = PanelStyle.menuItem(TYPE);
		type.setToolTipText("Type the name of a model that isn't listed, as the provider gives it");
		type.addActionListener(e -> startTyping());
		if (allowCustomModels) { menu.add(type); }
		JMenuItem refresh = PanelStyle.menuItem(REFRESH);
		for (JMenuItem item : new JMenuItem[]{type, refresh})
		{
			// As the models over them are: their font, colour and height, rather than a menu's own (smaller, greyer
			// and closer together, which read as greyed out next to the models).
			item.setFont(PanelStyle.TEXT_FONT);
			item.setForeground(PanelStyle.TEXT_COLOR);
			// As wide as it needs; the menu stretches it to its own width.
			item.setPreferredSize(new Dimension(item.getPreferredSize().width, rowHeight()));
		}
		refresh.setEnabled(refreshProblem == null);
		refresh.setToolTipText(refreshProblem == null ? "Ask the provider for its models again" : refreshProblem);
		refresh.addActionListener(e -> actions.refresh());
		menu.add(refresh);
		return menu;
	}

	/** A short, muted, wrapped line of plain text at the top of the menu. */
	private static JTextArea noteArea(String note, int width)
	{
		JTextArea area = PanelStyle.textArea(note);
		area.setFont(PanelStyle.SMALL_FONT);
		area.setForeground(PanelStyle.MUTED_COLOR);
		area.setBorder(new EmptyBorder(4, TEXT_INSET, 6, 8));
		area.setFocusable(false);
		// Its height for the menu's width: wrapped, not one long line that widens the menu.
		area.setSize(width, Short.MAX_VALUE);
		area.setPreferredSize(new Dimension(width, area.getPreferredSize().height));
		return area;
	}

	/** The models, the one that's set ticked; it scrolls past {@link #MENU_ROWS}. */
	private JScrollPane modelList(JPopupMenu menu, int width)
	{
		JList<String> list = new JList<String>(choices.toArray(new String[0]))
		{
			@Override
			public JToolTip createToolTip()
			{
				JToolTip tip = super.createToolTip();
				tip.putClientProperty("html.disable", Boolean.TRUE);
				return tip;
			}
		};
		list.setName("models");
		list.setCellRenderer(new ModelRow());
		list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		list.setOpaque(false);
		list.setFocusable(false);
		list.setFixedCellHeight(rowHeight());
		list.setVisibleRowCount(Math.min(MENU_ROWS, choices.size()));
		list.setSelectedIndex(choices.indexOf(current));
		MouseAdapter mouse = new MouseAdapter()
		{
			@Override
			public void mouseMoved(MouseEvent e)
			{
				// The row under the mouse lights up, as a menu's items do.
				int i = rowAt(list, e.getPoint());
				if (i >= 0)
				{
					list.setSelectedIndex(i);
				}
			}

			@Override
			public void mouseReleased(MouseEvent e)
			{
				int i = rowAt(list, e.getPoint());
				if (i >= 0 && SwingUtilities.isLeftMouseButton(e))
				{
					menu.setVisible(false);
					choose(list.getModel().getElementAt(i));
				}
			}
		};
		list.addMouseListener(mouse);
		list.addMouseMotionListener(mouse);
		JScrollPane scroll = new JScrollPane(list);
		scroll.setName("models");
		scroll.setBorder(null);
		scroll.setOpaque(false);
		scroll.getViewport().setOpaque(false);
		scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.getVerticalScrollBar().setUnitIncrement(list.getFixedCellHeight());
		// The menu's width, the scroll bar's included when there is one.
		int bar = choices.size() > MENU_ROWS ? scroll.getVerticalScrollBar().getPreferredSize().width : 0;
		list.setFixedCellWidth(width - bar);
		// Opening on the model that's set when it's further down a long list than the rows shown reach, as the last of
		// them; from the top otherwise, so the models over it (the newest, for Anthropic) show too. Set as a position,
		// as the list has no height yet to scroll in.
		int set = list.getSelectedIndex();
		if (set >= MENU_ROWS)
		{
			scroll.getViewport().setViewPosition(new Point(0, (set - MENU_ROWS + 1) * list.getFixedCellHeight()));
		}
		return scroll;
	}

	/** How tall each of the menu's rows is: models and items alike. */
	private int rowHeight()
	{
		return getFontMetrics(PanelStyle.TEXT_FONT).getHeight() + 10;
	}

	/** The row at {@code p}, or -1 when it isn't on one. */
	private static int rowAt(JList<String> list, Point p)
	{
		int i = list.locationToIndex(p);
		return i >= 0 && list.getCellBounds(i, i).contains(p) ? i : -1;
	}

	/** The player chose {@code item}: the provider's model becomes it, unless it's blank or already set. */
	private void choose(String item)
	{
		String model = item == null ? "" : item.trim();
		if (model.isEmpty() || model.equals(current) || !allowCustomModels && !choices.contains(model))
		{
			return;
		}
		current = model;
		button.setText(BuddyModels.label(model));
		revalidate();
		// Not from inside the menu's or the box's own event: setting the model redraws the panel, this picker included.
		SwingUtilities.invokeLater(() -> actions.choose(model));
	}

	// ------------------------------------------------------------------
	// Typing a name
	// ------------------------------------------------------------------

	/** "Type a model name…": a small box in the button's place, with the model that's set in it, selected. */
	void startTyping()
	{
		field.setText(current);
		field.selectAll();
		button.setVisible(false);
		fieldBox.setVisible(true);
		revalidate();
		repaint();
		// Once the menu that asked for this has closed, and given the focus back.
		SwingUtilities.invokeLater(field::requestFocusInWindow);
	}

	/** Enter: the name typed is chosen (nothing, if it's blank or the model that's set). */
	private void typed()
	{
		String name = field.getText();
		stopTyping();
		choose(name);
	}

	/** The button again, in the box's place. */
	void stopTyping()
	{
		if (!fieldBox.isVisible())
		{
			return;
		}
		fieldBox.setVisible(false);
		button.setVisible(true);
		revalidate();
		repaint();
	}

	/** The box a name is typed in: plain text, as is its tooltip, saying what it's for while it's empty. */
	static final class NameField extends JTextField
	{
		NameField()
		{
			putClientProperty("html.disable", Boolean.TRUE);
			setToolTipText("Enter sets the model; Escape goes back");
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			super.paintComponent(g);
			if (getDocument().getLength() > 0)
			{
				return;
			}
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g2.setColor(PanelStyle.MUTED_COLOR);
			g2.setFont(getFont());
			FontMetrics fm = g2.getFontMetrics();
			Insets in = getInsets();
			int middle = (getHeight() - in.top - in.bottom + fm.getAscent() - fm.getDescent()) / 2;
			g2.drawString(TYPE, in.left, in.top + middle);
			g2.dispose();
		}

		@Override
		public JToolTip createToolTip()
		{
			JToolTip tip = super.createToolTip();
			tip.putClientProperty("html.disable", Boolean.TRUE);
			return tip;
		}
	}

	/** A row of the menu's list: the model's name, plain text and cut at its end if long, and a tick on the one set. */
	private final class ModelRow extends JPanel implements ListCellRenderer<String>
	{
		private final JLabel name = new JLabel();
		private final JLabel tick = new JLabel();
		private final Glyph ticked = new Glyph(Glyph.Shape.CHECK, 11, PanelStyle.TEXT_COLOR);

		ModelRow()
		{
			super(new BorderLayout(6, 0));
			// Before any text is set: the HTML renderer is picked when it is.
			for (JLabel l : new JLabel[]{name, tick})
			{
				l.putClientProperty("html.disable", Boolean.TRUE);
			}
			name.setFont(PanelStyle.TEXT_FONT);
			name.setForeground(PanelStyle.TEXT_COLOR);
			// The names line up with the menu's items under them.
			setBorder(new EmptyBorder(0, TEXT_INSET, 0, 8));
			add(name, BorderLayout.CENTER);
			add(tick, BorderLayout.EAST);
		}

		@Override
		public Component getListCellRendererComponent(JList<? extends String> list, String value, int index,
			boolean selected, boolean focus)
		{
			name.setText(BuddyModels.label(value));
			tick.setIcon(Objects.equals(value, current) ? ticked : null);
			setOpaque(selected);
			// Lit as the menu's items are under the mouse.
			Color lit = UIManager.getColor("MenuItem.selectionBackground");
			setBackground(lit != null ? lit : PanelStyle.BUBBLE_COLOR);
			// The whole name, for one too long for the menu; the list shows the row's tooltip as its own.
			setToolTipText(value);
			return this;
		}
	}
}
