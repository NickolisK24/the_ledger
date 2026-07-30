package com.nikko.theledger.capture;

import com.nikko.theledger.model.LedgerEvent;
import com.nikko.theledger.model.MovementCategory;
import java.util.List;
import org.junit.Test;
import static com.nikko.theledger.capture.LedgerContainers.BANK;
import static com.nikko.theledger.capture.LedgerContainers.IFACE_BANKMAIN;
import static com.nikko.theledger.capture.LedgerContainers.INVENTORY;
import static com.nikko.theledger.capture.SnapshotFixtures.COINS;
import static com.nikko.theledger.capture.SnapshotFixtures.SHARK;
import static com.nikko.theledger.capture.SnapshotFixtures.SHARK_NOTED;
import static com.nikko.theledger.capture.SnapshotFixtures.SHARK_PLACEHOLDER;
import static com.nikko.theledger.capture.SnapshotFixtures.phantomCount;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Item identity is resolved before anything is diffed. A noted item is the same item; a
 * placeholder is not an item at all.
 */
public class NoteAndPlaceholderTest
{
	@Test
	public void notedAndUnnotedCollapseToTheSameIdentity()
	{
		FakeCanonicalizer raw = new FakeCanonicalizer().note(SHARK_NOTED, SHARK);
		ContainerSnapshot snapshot = ContainerSnapshot.fromRaw(INVENTORY,
			new int[]{SHARK, SHARK_NOTED}, new int[]{3, 7}, raw);

		assertEquals(1, snapshot.getQuantities().size());
		assertEquals(10, snapshot.quantityOf(SHARK));
	}

	@Test
	public void placeholdersAreSkippedEntirely()
	{
		FakeCanonicalizer raw = new FakeCanonicalizer().placeholder(SHARK_PLACEHOLDER);
		ContainerSnapshot snapshot = ContainerSnapshot.fromRaw(BANK,
			new int[]{SHARK, SHARK_PLACEHOLDER}, new int[]{5, 0}, raw);

		assertEquals(1, snapshot.getQuantities().size());
		assertEquals(0, snapshot.quantityOf(SHARK_PLACEHOLDER));
	}

	@Test
	public void emptySlotsAreIgnored()
	{
		FakeCanonicalizer raw = new FakeCanonicalizer();
		ContainerSnapshot snapshot = ContainerSnapshot.fromRaw(INVENTORY,
			new int[]{-1, 0, SHARK, -1}, new int[]{0, 0, 2, 0}, raw);

		assertEquals(1, snapshot.getQuantities().size());
		assertEquals(2, snapshot.quantityOf(SHARK));
	}

	/**
	 * Withdrawing the last of a stack leaves a placeholder behind. That placeholder must not
	 * register as anything, and the withdrawal itself is still a plain transfer.
	 */
	@Test
	public void placeholderCreationOnFinalWithdrawal()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.seed(BANK, SHARK, 5, COINS, 1000);
		f.seed(INVENTORY);

		f.menuClick("Withdraw-All", IFACE_BANKMAIN);
		// The bank keeps the slot, now holding a zero-quantity placeholder.
		f.containerChanged(BANK, SHARK_PLACEHOLDER, 0, COINS, 1000);
		f.containerChanged(INVENTORY, SHARK, 5);
		List<LedgerEvent> events = f.gameTick();

		assertEquals(SnapshotFixtures.describe(events), 0, phantomCount(events));
		assertEquals(2, SnapshotFixtures.withCategory(events, MovementCategory.TRANSFER).size());
	}

	@Test
	public void placeholderRemovalIsNotAnEvent()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.seed(BANK, SHARK_PLACEHOLDER, 0, COINS, 1000);

		f.menuClick("Release", IFACE_BANKMAIN);
		f.containerChanged(BANK, COINS, 1000);
		List<LedgerEvent> events = f.gameTick();

		assertEquals(SnapshotFixtures.describe(events), 0, events.size());
	}

	@Test
	public void fillingAllPlaceholdersIsNotAGain()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.seed(BANK, SHARK_PLACEHOLDER, 0, COINS, 1000);

		f.menuClick("Deposit-All", IFACE_BANKMAIN);
		f.seed(INVENTORY, SHARK, 20);
		f.containerChanged(INVENTORY);
		f.containerChanged(BANK, SHARK, 20, COINS, 1000);
		List<LedgerEvent> events = f.gameTick();

		assertEquals(SnapshotFixtures.describe(events), 0, phantomCount(events));
		assertEquals(2, SnapshotFixtures.withCategory(events, MovementCategory.TRANSFER).size());
	}

	/**
	 * The lookup is memoised, so a container event costs at most one composition lookup per item
	 * id it has never seen. Without this, every firing walks the whole container.
	 */
	@Test
	public void canonicalizationIsCachedPerItemId()
	{
		FakeCanonicalizer raw = new FakeCanonicalizer().note(SHARK_NOTED, SHARK);
		LedgerContainers.Canonicalizer cached = LedgerContainers.caching(raw);

		for (int i = 0; i < 50; i++)
		{
			assertEquals(SHARK, cached.resolve(SHARK));
			assertEquals(SHARK, cached.resolve(SHARK_NOTED));
		}

		assertEquals(1, raw.callsFor(SHARK));
		assertEquals(1, raw.callsFor(SHARK_NOTED));
		assertEquals(2, raw.totalCalls());
	}

	@Test
	public void cachingPreservesSkipResults()
	{
		FakeCanonicalizer raw = new FakeCanonicalizer().placeholder(SHARK_PLACEHOLDER);
		LedgerContainers.Canonicalizer cached = LedgerContainers.caching(raw);

		assertEquals(LedgerContainers.Canonicalizer.SKIP, cached.resolve(SHARK_PLACEHOLDER));
		assertEquals(LedgerContainers.Canonicalizer.SKIP, cached.resolve(SHARK_PLACEHOLDER));
		assertEquals(1, raw.callsFor(SHARK_PLACEHOLDER));
	}

	/**
	 * A full bank diffed repeatedly is the hot path. One lookup per distinct id, no matter how
	 * many times the container fires.
	 */
	@Test
	public void repeatedContainerEventsDoNotRepeatLookups()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		for (int i = 0; i < 20; i++)
		{
			f.containerChanged(BANK, SHARK, 100 + i, COINS, 1000);
			f.gameTick();
		}
		assertTrue("expected one lookup per distinct id, got " + f.rawCanonicalizer().totalCalls(),
			f.rawCanonicalizer().totalCalls() <= 2);
	}
}
