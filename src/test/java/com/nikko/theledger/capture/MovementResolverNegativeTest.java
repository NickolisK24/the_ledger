package com.nikko.theledger.capture;

import com.nikko.theledger.model.LedgerEvent;
import com.nikko.theledger.model.MovementCategory;
import java.util.List;
import org.junit.Test;
import static com.nikko.theledger.capture.LedgerContainers.BANK;
import static com.nikko.theledger.capture.LedgerContainers.EQUIPMENT;
import static com.nikko.theledger.capture.LedgerContainers.IFACE_BANKMAIN;
import static com.nikko.theledger.capture.LedgerContainers.IFACE_BANK_DEPOSITBOX;
import static com.nikko.theledger.capture.LedgerContainers.IFACE_GE_COLLECT;
import static com.nikko.theledger.capture.LedgerContainers.IFACE_GE_OFFERS;
import static com.nikko.theledger.capture.LedgerContainers.INVENTORY;
import static com.nikko.theledger.capture.SnapshotFixtures.ABYSSAL_WHIP;
import static com.nikko.theledger.capture.SnapshotFixtures.COINS;
import static com.nikko.theledger.capture.SnapshotFixtures.DRAGON_BONES;
import static com.nikko.theledger.capture.SnapshotFixtures.RUNE_ARROW;
import static com.nikko.theledger.capture.SnapshotFixtures.RUNE_PLATEBODY;
import static com.nikko.theledger.capture.SnapshotFixtures.SHARK;
import static com.nikko.theledger.capture.SnapshotFixtures.phantomCount;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Every case here must produce exactly zero gain or loss events.
 * <p>
 * These matter at least as much as the positive fixtures. Every existing profit tracker gets
 * one of these wrong, and each wrong one is a number the user is told they earned or lost when
 * nothing of the sort happened.
 */
public class MovementResolverNegativeTest
{
	@Test
	public void depositTwoHundredItemsIntoAnOpenBank()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.seed(BANK, RUNE_ARROW, 1000);
		f.seed(INVENTORY, RUNE_ARROW, 200);

		f.menuClick("Deposit-All", IFACE_BANKMAIN);
		f.containerChanged(INVENTORY);
		f.containerChanged(BANK, RUNE_ARROW, 1200);
		List<LedgerEvent> events = f.gameTick();

		assertEquals(SnapshotFixtures.describe(events), 0, phantomCount(events));
		assertEquals(2, SnapshotFixtures.withCategory(events, MovementCategory.TRANSFER).size());
		// Both legs are present and signed, so the movement is auditable.
		assertEquals(Integer.valueOf(-200), legFor(events, INVENTORY).getQty());
		assertEquals(Integer.valueOf(200), legFor(events, BANK).getQty());
	}

	@Test
	public void depositSeveralDifferentStacksIntoAnOpenBank()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.seed(BANK, COINS, 5_000_000);
		f.seed(INVENTORY, COINS, 250_000, SHARK, 12, DRAGON_BONES, 60);

		f.menuClick("Deposit inventory", IFACE_BANKMAIN);
		f.containerChanged(INVENTORY);
		f.containerChanged(BANK, COINS, 5_250_000, SHARK, 12, DRAGON_BONES, 60);
		List<LedgerEvent> events = f.gameTick();

		assertEquals(SnapshotFixtures.describe(events), 0, phantomCount(events));
		assertEquals(6, SnapshotFixtures.withCategory(events, MovementCategory.TRANSFER).size());
	}

	/**
	 * The note collapse happens before the diff, so the bank losing an unnoted id and the
	 * inventory gaining the noted one is a single item moving.
	 */
	@Test
	public void withdrawItemsAsNotes()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.seed(BANK, DRAGON_BONES, 5000);
		f.seed(INVENTORY);

		f.menuClick("Withdraw-X", IFACE_BANKMAIN);
		f.containerChanged(BANK, DRAGON_BONES, 4000);
		// The inventory receives the NOTED id, which is a different number entirely.
		f.containerChanged(INVENTORY, SnapshotFixtures.DRAGON_BONES_NOTED, 1000);
		List<LedgerEvent> events = f.gameTick();

		assertEquals(SnapshotFixtures.describe(events), 0, phantomCount(events));
		assertEquals(2, SnapshotFixtures.withCategory(events, MovementCategory.TRANSFER).size());
		// Both legs are recorded against the unnoted id.
		for (LedgerEvent e : events)
		{
			assertEquals(Integer.valueOf(DRAGON_BONES), e.getItemId());
		}
	}

	@Test
	public void depositViaDepositBoxWithTheBankNeverUpdating()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		// The bank interface was never opened this session, so its baseline is UNKNOWN.
		f.seed(INVENTORY, SHARK, 20, DRAGON_BONES, 100);
		assertTrue("bank must not have a baseline for this fixture", !f.bankSeen());

		f.menuClick("Deposit inventory", IFACE_BANK_DEPOSITBOX);
		f.containerChanged(INVENTORY);
		List<LedgerEvent> events = f.gameTick();

		assertEquals(SnapshotFixtures.describe(events), 0, phantomCount(events));
		assertEquals(2, SnapshotFixtures.withCategory(events, MovementCategory.TRANSFER).size());
		for (LedgerEvent e : events)
		{
			assertTrue(e.hasFlag(LedgerEvent.FLAG_INFERRED_DEPOSIT_BOX));
			assertTrue(e.hasFlag(LedgerEvent.FLAG_UNKNOWN_BANK_BASELINE));
		}
	}

	@Test
	public void depositBoxWornItemsAlsoLeavesEquipment()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.seed(EQUIPMENT, RUNE_PLATEBODY, 1);

		f.menuClick("Deposit worn items", IFACE_BANK_DEPOSITBOX);
		f.containerChanged(EQUIPMENT);
		List<LedgerEvent> events = f.gameTick();

		assertEquals(SnapshotFixtures.describe(events), 0, phantomCount(events));
		assertEquals(1, SnapshotFixtures.withCategory(events, MovementCategory.TRANSFER).size());
		assertTrue(events.get(0).hasFlag(LedgerEvent.FLAG_INFERRED_DEPOSIT_BOX));
	}

	/**
	 * The client applies a deposit a tick or two after the click, so the action context has to
	 * outlive the tick it happened on.
	 */
	@Test
	public void depositBoxAppliedOnALaterTickIsStillATransfer()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.seed(INVENTORY, SHARK, 20);

		f.menuClick("Deposit inventory", IFACE_BANK_DEPOSITBOX);
		f.gameTick();          // click tick, nothing moved yet
		f.containerChanged(INVENTORY);
		List<LedgerEvent> events = f.gameTick();

		assertEquals(SnapshotFixtures.describe(events), 0, phantomCount(events));
		assertEquals(1, SnapshotFixtures.withCategory(events, MovementCategory.TRANSFER).size());
	}

	@Test
	public void equipGear()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.seed(INVENTORY, ABYSSAL_WHIP, 1);
		f.seed(EQUIPMENT);

		f.worldClick("Wield");
		f.containerChanged(INVENTORY);
		f.containerChanged(EQUIPMENT, ABYSSAL_WHIP, 1);
		List<LedgerEvent> events = f.gameTick();

		assertEquals(SnapshotFixtures.describe(events), 0, phantomCount(events));
		assertEquals(2, SnapshotFixtures.withCategory(events, MovementCategory.TRANSFER).size());
	}

	@Test
	public void unequipGear()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.seed(INVENTORY);
		f.seed(EQUIPMENT, ABYSSAL_WHIP, 1);

		f.worldClick("Remove");
		f.containerChanged(EQUIPMENT);
		f.containerChanged(INVENTORY, ABYSSAL_WHIP, 1);
		List<LedgerEvent> events = f.gameTick();

		assertEquals(SnapshotFixtures.describe(events), 0, phantomCount(events));
		assertEquals(2, SnapshotFixtures.withCategory(events, MovementCategory.TRANSFER).size());
	}

	/**
	 * Slot order is discarded by the snapshot, so a reorganisation is not a change at all.
	 */
	@Test
	public void bankTabReorganisationWithNoNetQuantityChange()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.seed(BANK, COINS, 1_000_000, SHARK, 500, DRAGON_BONES, 2000, ABYSSAL_WHIP, 1);

		f.menuClick("Move", IFACE_BANKMAIN);
		f.containerChanged(BANK, ABYSSAL_WHIP, 1, DRAGON_BONES, 2000, COINS, 1_000_000, SHARK, 500);
		f.containerChanged(BANK, SHARK, 500, COINS, 1_000_000, ABYSSAL_WHIP, 1, DRAGON_BONES, 2000);
		List<LedgerEvent> events = f.gameTick();

		assertEquals(SnapshotFixtures.describe(events), 0, events.size());
	}

	// ---- Grand Exchange: items and coins leave the inventory with no container on the far side ----

	@Test
	public void placingABuyOfferIsNotACatastrophicLoss()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.seed(INVENTORY, COINS, 12_000_000);

		f.menuClick("Confirm", IFACE_GE_OFFERS);
		f.containerChanged(INVENTORY, COINS, 2_000_000);
		List<LedgerEvent> events = f.gameTick();

		assertEquals(SnapshotFixtures.describe(events), 0, phantomCount(events));
		assertEquals(1, SnapshotFixtures.withCategory(events, MovementCategory.TRANSFER).size());
		LedgerEvent leg = events.get(0);
		assertEquals(Integer.valueOf(-10_000_000), leg.getQty());
		assertTrue(leg.hasFlag(LedgerEvent.FLAG_INFERRED_GRAND_EXCHANGE));
	}

	@Test
	public void placingASellOfferIsNotALoss()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.seed(INVENTORY, ABYSSAL_WHIP, 1);

		f.menuClick("Confirm", IFACE_GE_OFFERS);
		f.containerChanged(INVENTORY);
		List<LedgerEvent> events = f.gameTick();

		assertEquals(SnapshotFixtures.describe(events), 0, phantomCount(events));
		assertTrue(events.get(0).hasFlag(LedgerEvent.FLAG_INFERRED_GRAND_EXCHANGE));
	}

	@Test
	public void collectingACompletedOfferIsNotAGain()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.seed(INVENTORY);

		f.menuClick("Collect to inventory", IFACE_GE_COLLECT);
		f.containerChanged(INVENTORY, ABYSSAL_WHIP, 1);
		List<LedgerEvent> events = f.gameTick();

		assertEquals(SnapshotFixtures.describe(events), 0, phantomCount(events));
		assertEquals(1, SnapshotFixtures.withCategory(events, MovementCategory.TRANSFER).size());
		assertTrue(events.get(0).hasFlag(LedgerEvent.FLAG_INFERRED_GRAND_EXCHANGE));
	}

	@Test
	public void collectionBoxWithdrawalToBankIsNotAGain()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.seed(BANK, COINS, 1000);

		f.menuClick("Collect to bank", IFACE_GE_COLLECT);
		f.containerChanged(BANK, COINS, 9_500_000);
		List<LedgerEvent> events = f.gameTick();

		assertEquals(SnapshotFixtures.describe(events), 0, phantomCount(events));
		assertEquals(1, SnapshotFixtures.withCategory(events, MovementCategory.TRANSFER).size());
		assertTrue(events.get(0).hasFlag(LedgerEvent.FLAG_INFERRED_GRAND_EXCHANGE));
	}

	@Test
	public void abortingAnOfferReturnsItemsWithoutAGain()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.seed(INVENTORY);

		f.menuClick("Abort offer", IFACE_GE_OFFERS);
		f.containerChanged(INVENTORY, COINS, 10_000_000);
		List<LedgerEvent> events = f.gameTick();

		assertEquals(SnapshotFixtures.describe(events), 0, phantomCount(events));
		assertTrue(events.get(0).hasFlag(LedgerEvent.FLAG_INFERRED_GRAND_EXCHANGE));
	}

	/**
	 * The inference is scoped to the Grand Exchange interface. Dropping coins on the ground with
	 * no interface open is still a real loss, and must not be swallowed.
	 */
	@Test
	public void grandExchangeInferenceDoesNotLeakToUnrelatedActions()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.seed(INVENTORY, COINS, 10_000_000);

		f.worldClick("Drop");
		f.containerChanged(INVENTORY);
		List<LedgerEvent> events = f.gameTick();

		assertEquals(1, events.size());
		assertEquals(MovementCategory.UNCLASSIFIED_LOSS, events.get(0).getCategory());
	}

	private static LedgerEvent legFor(List<LedgerEvent> events, int containerId)
	{
		for (LedgerEvent e : events)
		{
			if (e.getContainerId() != null && e.getContainerId() == containerId)
			{
				return e;
			}
		}
		throw new AssertionError("no event for container " + containerId
			+ SnapshotFixtures.describe(events));
	}
}
