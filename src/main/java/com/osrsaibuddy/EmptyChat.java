package com.osrsaibuddy;

import java.awt.BorderLayout;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import javax.swing.text.StyleConstants;

/**
 * What a chat with no messages yet shows, as chat apps do: a question in the middle, and a few ways to start. A starter
 * only fills the input box, never sends: sending costs the player money, so they always press Send themselves.
 */
final class EmptyChat extends JPanel
{
	/** A way to start: the words on its chip, and what it puts in the input box. */
	static final class Starter
	{
		final String label;
		/** Ends with a space where the player carries on typing, unless it's a whole question. */
		final String text;

		Starter(String label, String text)
		{
			this.label = label;
			this.text = text;
		}
	}

	static final List<Starter> STARTERS = Collections.unmodifiableList(Arrays.asList(
		new Starter("What should I train next?", "What should I train next?"),
		new Starter("Price check an item", "How much is "),
		new Starter("Plan my Slayer task", "Help me plan my Slayer task: "),
		new Starter("Help with a quest", "Help me with the quest ")));

	final List<FlatButton> chips = new ArrayList<>();

	/** {@code fill}: puts a starter's text in the input box. */
	EmptyChat(Consumer<String> fill)
	{
		super(new StackLayout(6));
		setOpaque(false);
		setBorder(new EmptyBorder(0, 8, 0, 8));
		MessageView title = new MessageView();
		title.setTextFont(PanelStyle.HEADING_FONT);
		title.setAlignment(StyleConstants.ALIGN_CENTER);
		title.setPlainText("What can I help with?");
		title.setBorder(new EmptyBorder(0, 0, 6, 0));
		add(title);
		for (Starter s : STARTERS)
		{
			FlatButton chip = new FlatButton(s.label, null, null)
				.filled(null, PanelStyle.OUTLINE_COLOR, false);
			chip.setFont(PanelStyle.TEXT_FONT);
			chip.setForeground(PanelStyle.TEXT_COLOR);
			chip.setBorder(new EmptyBorder(7, 10, 7, 10));
			chip.setHorizontalAlignment(SwingConstants.LEFT);
			chip.addActionListener(e -> fill.accept(s.text));
			JPanel row = new JPanel(new BorderLayout());
			row.setOpaque(false);
			row.add(chip, BorderLayout.CENTER);
			chips.add(chip);
			add(row);
		}
		MessageView tip = MessageRow.mutedLine(StyleConstants.ALIGN_CENTER);
		tip.setPlainText("You can also ask from the game's chatbox: type ::buddy and your question.");
		tip.setBorder(new EmptyBorder(6, 0, 0, 0));
		tip.setVisible(true);
		add(tip);
	}
}
