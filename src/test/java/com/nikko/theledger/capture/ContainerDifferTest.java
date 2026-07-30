package com.nikko.theledger.capture;

import java.util.List;
import org.junit.Test;
import static com.nikko.theledger.capture.LedgerContainers.BANK;
import static com.nikko.theledger.capture.LedgerContainers.INVENTORY;
import static com.nikko.theledger.capture.SnapshotFixtures.COINS;
import static com.nikko.theledger.capture.SnapshotFixtures.SHARK;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ContainerDifferTest
{
	@Test
	public void producesSignedDeltasForChangedItemsOnly()
	{
		ContainerSnapshot before = ContainerSnapshot.ofPairs(INVENTORY, COINS, 1000, SHARK, 5);
		ContainerSnapshot after = ContainerSnapshot.ofPairs(INVENTORY, COINS, 1000, SHARK, 3);

		List<ContainerDiffer.Delta> deltas = ContainerDiffer.diff(before, after);

		assertEquals(1, deltas.size());
		assertEquals(SHARK, deltas.get(0).getItemId());
		assertEquals(-2, deltas.get(0).getDelta());
		assertEquals(INVENTORY, deltas.get(0).getContainerId());
	}

	@Test
	public void treatsAbsentItemAsZero()
	{
		ContainerSnapshot before = ContainerSnapshot.empty(INVENTORY);
		ContainerSnapshot after = ContainerSnapshot.ofPairs(INVENTORY, SHARK, 4);

		List<ContainerDiffer.Delta> deltas = ContainerDiffer.diff(before, after);

		assertEquals(1, deltas.size());
		assertEquals(4, deltas.get(0).getDelta());
	}

	/**
	 * The single most important property in the codebase. An unknown baseline is not an empty
	 * one, and diffing against it must yield nothing.
	 */
	@Test
	public void unknownBaselineYieldsNoDeltas()
	{
		ContainerSnapshot unknown = ContainerSnapshot.unknown(BANK);
		ContainerSnapshot populated = ContainerSnapshot.ofPairs(BANK, COINS, 50_000_000, SHARK, 2000);

		assertTrue(ContainerDiffer.diff(unknown, populated).isEmpty());
	}

	@Test
	public void unknownIsNotEmpty()
	{
		assertFalse(ContainerSnapshot.unknown(BANK).isKnown());
		assertTrue(ContainerSnapshot.empty(BANK).isKnown());
		assertFalse(ContainerSnapshot.unknown(BANK).equals(ContainerSnapshot.empty(BANK)));
	}

	@Test
	public void identicalSnapshotsYieldNoDeltas()
	{
		ContainerSnapshot a = ContainerSnapshot.ofPairs(BANK, COINS, 7, SHARK, 9);
		ContainerSnapshot b = ContainerSnapshot.ofPairs(BANK, SHARK, 9, COINS, 7);

		assertEquals(a, b);
		assertTrue(ContainerDiffer.diff(a, b).isEmpty());
	}

	@Test
	public void deltasAreOrderedByItemId()
	{
		ContainerSnapshot before = ContainerSnapshot.empty(INVENTORY);
		ContainerSnapshot after = ContainerSnapshot.ofPairs(INVENTORY, COINS, 1, SHARK, 1);

		List<ContainerDiffer.Delta> deltas = ContainerDiffer.diff(before, after);

		assertEquals(2, deltas.size());
		assertTrue(deltas.get(0).getItemId() < deltas.get(1).getItemId());
	}

	@Test(expected = IllegalArgumentException.class)
	public void refusesToDiffDifferentContainers()
	{
		ContainerDiffer.diff(ContainerSnapshot.empty(INVENTORY), ContainerSnapshot.empty(BANK));
	}

	@Test
	public void stacksSplitAcrossSlotsAreMerged()
	{
		// Non-stackable items occupy one slot each; the snapshot counts them as a quantity.
		ContainerSnapshot snapshot = ContainerSnapshot.ofPairs(INVENTORY, SHARK, 1, SHARK, 1, SHARK, 1);
		assertEquals(3, snapshot.quantityOf(SHARK));
	}
}
