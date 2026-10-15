package com.osrsaibuddy;

import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** How a table's columns share the width, and when it's drawn as cards instead. Pure: no drawing here. */
public class TableViewTest
{
	@Test
	public void columnsThatFitAreAsWideAsTheirText()
	{
		assertArrayEquals(new int[]{50, 120}, TableView.columnWidths(new int[]{50, 120}, 200));
		assertArrayEquals(new int[]{80, 120}, TableView.columnWidths(new int[]{80, 120}, 200));
	}

	@Test
	public void narrowColumnsKeepTheirWidthAndTheWideOnesShareTheRest()
	{
		// 200 for three: the 50 fits in a third, and keeps it; the other two share the 150 left.
		assertArrayEquals(new int[]{50, 75, 75}, TableView.columnWidths(new int[]{50, 300, 120}, 200));
		// The 70 doesn't fit in a share of 66 at first, but does in the 75 left once the 50 has its own.
		assertArrayEquals(new int[]{50, 70, 80}, TableView.columnWidths(new int[]{50, 70, 300}, 200));
		// The odd pixels go to the first of the wide ones; together they're the room there is.
		assertArrayEquals(new int[]{101, 100}, TableView.columnWidths(new int[]{400, 300}, 201));
		assertArrayEquals(new int[]{220}, TableView.columnWidths(new int[]{900}, 220));
	}

	@Test
	public void aTableThatDoesntFitTurnsIntoCards()
	{
		// Fits as it is: a table, however many columns.
		int[] five = {40, 40, 40, 40, 40};
		assertFalse(TableView.cards(five, TableView.columnWidths(five, 220)));
		// More than three that don't fit: cards.
		int[] four = {60, 60, 60, 60};
		assertTrue(TableView.cards(four, TableView.columnWidths(four, 220)));
		// Three, squeezed but none under 60: a table, its text wrapped.
		int[] three = {50, 300, 300};
		assertFalse(TableView.cards(three, TableView.columnWidths(three, 220)));
		// Squeezed under 60: cards.
		int[] squeezed = {100, 300, 300};
		assertTrue(TableView.cards(squeezed, TableView.columnWidths(squeezed, 160)));
		// A narrow column that has all it wants is fine, even under 60.
		int[] narrow = {30, 400};
		assertArrayEquals(new int[]{30, 190}, TableView.columnWidths(narrow, 220));
		assertFalse(TableView.cards(narrow, TableView.columnWidths(narrow, 220)));
	}
}

