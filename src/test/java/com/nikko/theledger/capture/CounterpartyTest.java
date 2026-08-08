package com.nikko.theledger.capture;

import com.nikko.theledger.model.LedgerEvent;
import com.nikko.theledger.model.MovementCategory;
import java.util.List;
import org.junit.Test;
import static com.nikko.theledger.capture.LedgerContainers.BANK;
import static com.nikko.theledger.capture.LedgerContainers.EQUIPMENT;
import static com.nikko.theledger.capture.LedgerContainers.IFACE_BANKMAIN;
import static com.nikko.theledger.capture.LedgerContainers.INVENTORY;
import static com.nikko.theledger.capture.SnapshotFixtures.ABYSSAL_WHIP;
import static com.nikko.theledger.capture.SnapshotFixtures.COINS;
import static com.nikko.theledger.capture.SnapshotFixtures.RUNE_ARROW;
import static com.nikko.theledger.capture.SnapshotFixtures.RUNE_PLATEBODY;
import static com.nikko.theledger.capture.SnapshotFixtures.SHARK;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * One-legged movements.
 * <p>
 * UNKNOWN suppresses phantoms on the container that has no baseline. It does nothing for that
 * container's partner. A real session banked worn items while the equipment container had never
 * been observed, and the bank leg was reported on its own as ten items appearing from nowhere.
 * <p>
 * Transfer netting needs BOTH sides seeded, and nothing used to check that precondition. Now a
 * movement whose plausible counterpart is invisible is UNVERIFIED — not a gain, not a loss, and
 * explicitly excluded from cost accounting.
 */
public class CounterpartyTest
{
	// ---- Direction 1: the seeded container reports, the unseeded one cannot ----

	@Test
	public void bankGainWhileEquipmentIsUnseeded()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.seed(BANK, COINS, 1000);
		f.seed(INVENTORY);
		// Equipment never observed: exactly the state a session is in until gear first changes.
		assertFalse(f.isSeeded(EQUIPMENT));

		f.menuClick("Deposit worn items", IFACE_BANKMAIN);
		f.containerChanged(BANK, COINS, 1000, RUNE_PLATEBODY, 1, ABYSSAL_WHIP, 1);
		List<LedgerEvent> events = f.gameTick();

		assertEquals(SnapshotFixtures.describe(events), 0, SnapshotFixtures.phantomCount(events));
		assertEquals(2, SnapshotFixtures.withCategory(events, MovementCategory.UNVERIFIED).size());
		for (LedgerEvent e : events)
		{
			assertTrue(e.hasFlag(LedgerEvent.FLAG_COUNTERPARTY_UNSEEDED));
			assertTrue("the direction must survive for Phase 2", e.getQty() > 0);
		}
	}

	@Test
	public void bankLossWhileInventoryIsUnseeded()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.seed(BANK, SHARK, 5000);
		f.seed(EQUIPMENT);
		assertFalse(f.isSeeded(INVENTORY));

		f.menuClick("Withdraw-10", IFACE_BANKMAIN);
		f.containerChanged(BANK, SHARK, 4990);
		List<LedgerEvent> events = f.gameTick();

		assertEquals(0, SnapshotFixtures.phantomCount(events));
		assertEquals(1, SnapshotFixtures.withCategory(events, MovementCategory.UNVERIFIED).size());
		assertTrue(events.get(0).hasFlag(LedgerEvent.FLAG_COUNTERPARTY_UNSEEDED));
	}

	// ---- Direction 2: the carried containers, either way round ----

	@Test
	public void inventoryLossWhileEquipmentIsUnseeded()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.seed(INVENTORY, ABYSSAL_WHIP, 1);
		assertFalse(f.isSeeded(EQUIPMENT));

		f.worldClick("Wield");
		f.containerChanged(INVENTORY);
		List<LedgerEvent> events = f.gameTick();

		assertEquals(0, SnapshotFixtures.phantomCount(events));
		assertEquals(MovementCategory.UNVERIFIED, events.get(0).getCategory());
		assertTrue(events.get(0).hasFlag(LedgerEvent.FLAG_COUNTERPARTY_UNSEEDED));
	}

	@Test
	public void inventoryGainWhileEquipmentIsUnseeded()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.seed(INVENTORY);
		assertFalse(f.isSeeded(EQUIPMENT));

		f.worldClick("Remove");
		f.containerChanged(INVENTORY, ABYSSAL_WHIP, 1);
		List<LedgerEvent> events = f.gameTick();

		assertEquals(0, SnapshotFixtures.phantomCount(events));
		assertEquals(MovementCategory.UNVERIFIED, events.get(0).getCategory());
	}

	@Test
	public void equipmentMovementWhileInventoryIsUnseeded()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.seed(EQUIPMENT, RUNE_PLATEBODY, 1);
		assertFalse(f.isSeeded(INVENTORY));

		f.worldClick("Remove");
		f.containerChanged(EQUIPMENT);
		List<LedgerEvent> events = f.gameTick();

		assertEquals(0, SnapshotFixtures.phantomCount(events));
		assertEquals(MovementCategory.UNVERIFIED, events.get(0).getCategory());
	}

	// ---- The rule must not swallow everything ----

	/**
	 * An unseeded bank is not a plausible counterpart for a carried container: the bank interface
	 * has to be open to move anything into or out of it, and opening it populates the container.
	 * So a kill drop before the bank has ever been opened is still a real gain.
	 */
	@Test
	public void anUnseededBankDoesNotSuppressOrdinaryInventoryGains()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.loggedIn(COINS, 1000);
		assertFalse(f.isSeeded(BANK));

		f.worldClick("Attack");
		f.containerChanged(INVENTORY, COINS, 1000, ABYSSAL_WHIP, 1);
		List<LedgerEvent> events = f.gameTick();

		assertEquals(1, events.size());
		assertEquals(MovementCategory.UNCLASSIFIED_GAIN, events.get(0).getCategory());
		assertTrue(events.get(0).getFlags().isEmpty());
	}

	@Test
	public void everythingSeededClassifiesNormally()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.loggedIn(SHARK, 10);
		f.seed(BANK, COINS, 1000);

		f.worldClick("Eat");
		f.containerChanged(INVENTORY, SHARK, 9);
		List<LedgerEvent> events = f.gameTick();

		assertEquals(1, events.size());
		assertEquals(MovementCategory.UNCLASSIFIED_LOSS, events.get(0).getCategory());
	}

	/**
	 * A genuine two-legged transfer is still a transfer. The counterparty rule only ever fires on
	 * a residual, so netting is untouched.
	 */
	@Test
	public void bothLegsPresentIsStillATransfer()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.loggedIn(RUNE_ARROW, 200);
		f.seed(BANK, RUNE_ARROW, 1000);

		f.menuClick("Deposit-All", IFACE_BANKMAIN);
		f.containerChanged(INVENTORY);
		f.containerChanged(BANK, RUNE_ARROW, 1200);
		List<LedgerEvent> events = f.gameTick();

		assertEquals(2, SnapshotFixtures.withCategory(events, MovementCategory.TRANSFER).size());
		assertEquals(0, SnapshotFixtures.withCategory(events, MovementCategory.UNVERIFIED).size());
	}

	// ---- Untracked storage: the rune pouch case ----

	/**
	 * Depositing runes from a rune pouch produced bank gains of 14,000-16,000 runes at a time in a
	 * real session, all one-legged, because the pouch is not a container the spine tracks.
	 * <p>
	 * Every container is seeded here, so the unseeded rule cannot fire. The bank still changed
	 * with nothing on the other side, and the bank can only change by transfer — which is proof
	 * enough that the counterpart was invisible.
	 */
	@Test
	public void runePouchDepositIntoASeededBankIsStillNotAGain()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.loggedIn();
		f.seed(BANK, COINS, 1000);

		f.menuClick("Deposit runes", LedgerContainers.IFACE_BANKSIDE);
		f.containerChanged(BANK, COINS, 1000, 554, 14875, 560, 15911);
		List<LedgerEvent> events = f.gameTick();

		assertEquals(SnapshotFixtures.describe(events), 0, SnapshotFixtures.phantomCount(events));
		assertEquals(2, SnapshotFixtures.withCategory(events, MovementCategory.UNVERIFIED).size());
		for (LedgerEvent e : events)
		{
			assertTrue(e.hasFlag(LedgerEvent.FLAG_COUNTERPARTY_UNTRACKED));
		}
	}

	@Test
	public void runePouchFillFromASeededBankIsStillNotALoss()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.loggedIn();
		f.seed(BANK, 554, 32000, 556, 32000);

		f.menuClick("Withdraw-All", LedgerContainers.IFACE_BANKMAIN);
		f.containerChanged(BANK, 554, 16000, 556, 16000);
		List<LedgerEvent> events = f.gameTick();

		assertEquals(0, SnapshotFixtures.phantomCount(events));
		assertEquals(2, SnapshotFixtures.withCategory(events, MovementCategory.UNVERIFIED).size());
		for (LedgerEvent e : events)
		{
			assertTrue(e.hasFlag(LedgerEvent.FLAG_COUNTERPARTY_UNTRACKED));
			assertTrue(e.getQty() < 0);
		}
	}

	/**
	 * The inventory is not subject to the untracked rule: it gains items from the world and loses
	 * them to consumption, so a one-legged inventory movement is ordinary rather than suspicious.
	 * Applying the bank's rule there would suppress every kill drop in the game.
	 */
	@Test
	public void theUntrackedRuleAppliesToTheBankOnly()
	{
		assertTrue(LedgerContainers.changesOnlyByTransfer(BANK));
		assertFalse(LedgerContainers.changesOnlyByTransfer(INVENTORY));
		assertFalse(LedgerContainers.changesOnlyByTransfer(EQUIPMENT));
	}

	/**
	 * A Grand Exchange collection into the bank is a known invisible counterpart with a specific
	 * explanation, so it keeps its own flag rather than being lumped in with untracked storage.
	 */
	@Test
	public void grandExchangeStillWinsOverTheUntrackedRule()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.loggedIn();
		f.seed(BANK, COINS, 1000);

		f.menuClick("Collect to bank", LedgerContainers.IFACE_GE_COLLECT);
		f.containerChanged(BANK, COINS, 9_500_000);
		List<LedgerEvent> events = f.gameTick();

		assertEquals(1, SnapshotFixtures.withCategory(events, MovementCategory.TRANSFER).size());
		assertTrue(events.get(0).hasFlag(LedgerEvent.FLAG_INFERRED_GRAND_EXCHANGE));
	}

	/**
	 * A death wipe is a known cause, so it outranks both counterparty rules.
	 */
	@Test
	public void deathStillWinsOverTheCounterpartyRules()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.seed(INVENTORY, SHARK, 10);
		assertFalse(f.isSeeded(EQUIPMENT));

		f.death();
		f.containerChanged(INVENTORY);
		List<LedgerEvent> events = f.gameTick();

		assertEquals(MovementCategory.DEATH_LOSS, events.get(0).getCategory());
	}
}
