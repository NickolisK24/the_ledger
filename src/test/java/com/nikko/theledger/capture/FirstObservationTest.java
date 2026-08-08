package com.nikko.theledger.capture;

import com.nikko.theledger.model.LedgerEvent;
import com.nikko.theledger.model.MovementCategory;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static com.nikko.theledger.capture.LedgerContainers.BANK;
import static com.nikko.theledger.capture.LedgerContainers.EQUIPMENT;
import static com.nikko.theledger.capture.LedgerContainers.IFACE_BANKMAIN;
import static com.nikko.theledger.capture.LedgerContainers.INVENTORY;
import static com.nikko.theledger.capture.SnapshotFixtures.ABYSSAL_WHIP;
import static com.nikko.theledger.capture.SnapshotFixtures.COINS;
import static com.nikko.theledger.capture.SnapshotFixtures.DRAGON_BONES;
import static com.nikko.theledger.capture.SnapshotFixtures.DRAGON_BONES_NOTED;
import static com.nikko.theledger.capture.SnapshotFixtures.RUNE_ARROW;
import static com.nikko.theledger.capture.SnapshotFixtures.SHARK;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * First observations, and the line between corroborating a movement and inventing one.
 * <p>
 * A live session logged in wearing nothing. An empty equipment container is never allocated by
 * the client, so {@code getItemContainer(94)} returned null on every tick and the container stayed
 * UNKNOWN. The first six graceful pieces went on; the inventory reported losing three of them and
 * equipment reported its very first observation already holding exactly those three. Treating that
 * observation as nothing but a baseline threw the corroboration away, and the stream asserted
 * three clean losses the player never took.
 * <p>
 * The fix is not to decide that a null container is empty — UNKNOWN is not EMPTY, and guessing
 * would trade a phantom loss for a phantom gain. It is to keep the first observation as evidence
 * through the tick so the resolver can match it against opposite deltas, and to let it do nothing
 * else. Whatever it holds that nobody lost is gear you were already wearing, and produces no
 * event at all.
 */
public class FirstObservationTest
{
	// Graceful, at the canonical inventory identities the worn-id mapping produces.
	private static final int HOOD = 11850;
	private static final int CAPE = 11852;
	private static final int TOP = 11854;
	private static final int LEGS = 11856;
	private static final int GLOVES = 11858;
	private static final int BOOTS = 11860;

	/**
	 * The production shape, tick for tick. Inventory loses three pieces; equipment is observed for
	 * the first time holding exactly those three.
	 */
	@Test
	public void gracefulEquipAgainstAnUnknownEquipmentContainer()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.seed(INVENTORY, HOOD, 1, CAPE, 1, TOP, 1, LEGS, 1, GLOVES, 1, BOOTS, 1);
		assertFalse("the container the client never allocated", f.isSeeded(EQUIPMENT));

		f.menuClick("Wear", 149);
		f.containerChanged(INVENTORY, HOOD, 1, CAPE, 1, BOOTS, 1);
		f.containerChanged(EQUIPMENT, TOP, 1, LEGS, 1, GLOVES, 1);
		List<LedgerEvent> events = f.gameTick();

		assertEquals(SnapshotFixtures.describe(events), 0, SnapshotFixtures.phantomCount(events));
		assertEquals(0, SnapshotFixtures.unverifiedCount(events));
		assertEquals(6, SnapshotFixtures.withCategory(events, MovementCategory.TRANSFER).size());

		for (int itemId : new int[]{TOP, LEGS, GLOVES})
		{
			assertEquals("inventory leg for " + itemId, Integer.valueOf(-1),
				legFor(events, INVENTORY, itemId).getQty());
			assertEquals("equipment leg for " + itemId, Integer.valueOf(1),
				legFor(events, EQUIPMENT, itemId).getQty());
			assertTrue(legFor(events, EQUIPMENT, itemId)
				.hasFlag(LedgerEvent.FLAG_FIRST_OBSERVATION_RECONCILED));
		}
	}

	/**
	 * The whole six-piece sequence: three pieces learned through a first observation, three more
	 * through ordinary known-to-known changes. Every equip is represented exactly once.
	 */
	@Test
	public void rapidSixPieceEquipAcrossTwoTicks()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.seed(INVENTORY, HOOD, 1, CAPE, 1, TOP, 1, LEGS, 1, GLOVES, 1, BOOTS, 1);

		f.menuClick("Wear", 149);
		f.containerChanged(INVENTORY, HOOD, 1, CAPE, 1, BOOTS, 1);
		f.containerChanged(EQUIPMENT, TOP, 1, LEGS, 1, GLOVES, 1);
		List<LedgerEvent> first = f.gameTick();

		f.menuClick("Wear", 149);
		f.containerChanged(INVENTORY);
		f.containerChanged(EQUIPMENT, TOP, 1, LEGS, 1, GLOVES, 1, HOOD, 1, CAPE, 1, BOOTS, 1);
		List<LedgerEvent> second = f.gameTick();

		List<LedgerEvent> all = new ArrayList<>(first);
		all.addAll(second);

		assertEquals(SnapshotFixtures.describe(all), 0, SnapshotFixtures.phantomCount(all));
		assertEquals(12, SnapshotFixtures.withCategory(all, MovementCategory.TRANSFER).size());

		// Exactly one inventory leg and one equipment leg per piece, across the whole sequence.
		for (int itemId : new int[]{HOOD, CAPE, TOP, LEGS, GLOVES, BOOTS})
		{
			assertEquals("one inventory leg for " + itemId, 1, countFor(all, INVENTORY, itemId));
			assertEquals("one equipment leg for " + itemId, 1, countFor(all, EQUIPMENT, itemId));
		}
		// Only the first tick needed corroboration.
		assertEquals(6, flagged(first));
		assertEquals(0, flagged(second));
	}

	/**
	 * Partial match. The first observation holds three items; only two of them were lost
	 * elsewhere. Those two reconcile, the third establishes baseline and produces nothing.
	 */
	@Test
	public void onlyTheMatchedPartOfAFirstObservationReconciles()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.seed(INVENTORY, TOP, 1, LEGS, 1);

		f.menuClick("Wear", 149);
		f.containerChanged(INVENTORY);
		f.containerChanged(EQUIPMENT, TOP, 1, LEGS, 1, ABYSSAL_WHIP, 1);
		List<LedgerEvent> events = f.gameTick();

		assertEquals(SnapshotFixtures.describe(events), 4, events.size());
		assertEquals(4, SnapshotFixtures.withCategory(events, MovementCategory.TRANSFER).size());
		assertEquals(0, SnapshotFixtures.phantomCount(events));
		assertEquals("the whip was already being worn and nobody lost it", 0,
			countFor(events, EQUIPMENT, ABYSSAL_WHIP));
	}

	/**
	 * The load-bearing negative. An item present in a first observation with no opposite leg
	 * anywhere must never become a gain — that is the phantom this whole design exists to avoid.
	 */
	@Test
	public void anUnmatchedFirstObservationNeverBecomesAGain()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.seed(INVENTORY, SHARK, 10);

		f.worldClick("Eat");
		f.containerChanged(INVENTORY, SHARK, 9);
		f.containerChanged(EQUIPMENT, ABYSSAL_WHIP, 1, HOOD, 1, CAPE, 1);
		List<LedgerEvent> events = f.gameTick();

		assertEquals(0, countFor(events, EQUIPMENT, ABYSSAL_WHIP));
		assertEquals(0, countFor(events, EQUIPMENT, HOOD));
		assertEquals(0, countFor(events, EQUIPMENT, CAPE));
		assertEquals("no gain events at all", 0,
			SnapshotFixtures.withCategory(events, MovementCategory.UNCLASSIFIED_GAIN).size());
		// The shark really was eaten, and is still reported as the loss it is.
		assertEquals(1, events.size());
		assertEquals(MovementCategory.UNCLASSIFIED_LOSS, events.get(0).getCategory());
	}

	/**
	 * A first observation cannot corroborate a gain either. Equipment turning up for the first time
	 * says nothing about whether it gave anything away, because there was no baseline for it to
	 * have given anything away from.
	 */
	@Test
	public void aFirstObservationCannotExplainAGainElsewhere()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.seed(INVENTORY);

		f.worldClick("Remove");
		f.containerChanged(INVENTORY, ABYSSAL_WHIP, 1);
		f.containerChanged(EQUIPMENT, HOOD, 1);
		List<LedgerEvent> events = f.gameTick();

		assertEquals(1, events.size());
		assertEquals(MovementCategory.UNCLASSIFIED_GAIN, events.get(0).getCategory());
		assertEquals(Integer.valueOf(ABYSSAL_WHIP), events.get(0).getItemId());
		assertFalse(events.get(0).hasFlag(LedgerEvent.FLAG_FIRST_OBSERVATION_RECONCILED));
	}

	/**
	 * Once observed, the container behaves like any other. The tick after a first observation is
	 * an ordinary known-to-known diff.
	 */
	@Test
	public void theTickAfterAFirstObservationIsOrdinary()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.seed(INVENTORY, TOP, 1);
		f.containerChanged(INVENTORY);
		f.containerChanged(EQUIPMENT, TOP, 1);
		f.gameTick();

		f.worldClick("Remove");
		f.containerChanged(EQUIPMENT);
		f.containerChanged(INVENTORY, TOP, 1);
		List<LedgerEvent> events = f.gameTick();

		assertEquals(2, SnapshotFixtures.withCategory(events, MovementCategory.TRANSFER).size());
		assertEquals(0, flagged(events));
		assertEquals(0, SnapshotFixtures.phantomCount(events));
	}

	/**
	 * KNOWN-EMPTY is not UNKNOWN. A container observed to be empty has a baseline, so a later
	 * inventory loss with no equipment gain is a real loss and is reported as one.
	 */
	@Test
	public void knownEmptyIsNotUnknown()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.seed(INVENTORY, SHARK, 10);
		f.seed(EQUIPMENT);
		assertTrue("observed and empty is still observed", f.isSeeded(EQUIPMENT));

		f.worldClick("Eat");
		f.containerChanged(INVENTORY, SHARK, 9);
		List<LedgerEvent> events = f.gameTick();

		assertEquals(1, SnapshotFixtures.phantomCount(events));
		assertEquals(0, SnapshotFixtures.unverifiedCount(events));
		assertTrue(events.get(0).getFlags().isEmpty());
	}

	/**
	 * A bank that was never opened stays UNKNOWN. Nothing about first-observation reconciliation
	 * gives it a baseline it has not earned.
	 */
	@Test
	public void anUnopenedBankStaysUnknown()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.loggedIn(COINS, 1000);

		assertFalse(f.isSeeded(BANK));
		assertFalse(f.snapshotOf(BANK).isKnown());

		f.worldClick("Attack");
		f.containerChanged(INVENTORY, COINS, 1000, ABYSSAL_WHIP, 1);
		f.gameTick();

		assertFalse("still never opened", f.isSeeded(BANK));
	}

	/**
	 * The first time the bank IS opened, it is a first observation like any other: its contents
	 * corroborate nothing on their own and produce no gains.
	 */
	@Test
	public void openingTheBankForTheFirstTimeProducesNoGains()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.loggedIn(COINS, 1000);

		f.menuClick("Bank", IFACE_BANKMAIN);
		f.containerChanged(BANK, COINS, 80_000_000, SHARK, 5000, DRAGON_BONES, 2000);
		List<LedgerEvent> events = f.gameTick();

		assertEquals(SnapshotFixtures.describe(events), 0, events.size());
		assertTrue(f.isSeeded(BANK));
	}

	@Test
	public void ordinaryBankTransfersAreUnchanged()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.loggedIn(RUNE_ARROW, 200);
		f.seed(BANK, RUNE_ARROW, 1000);

		f.menuClick("Deposit-All", IFACE_BANKMAIN);
		f.containerChanged(INVENTORY);
		f.containerChanged(BANK, RUNE_ARROW, 1200);
		List<LedgerEvent> events = f.gameTick();

		assertEquals(2, SnapshotFixtures.withCategory(events, MovementCategory.TRANSFER).size());
		assertEquals(0, flagged(events));
	}

	@Test
	public void ordinaryEquipmentTransfersWithKnownEquipmentAreUnchanged()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.seed(INVENTORY, ABYSSAL_WHIP, 1);
		f.seed(EQUIPMENT);

		f.worldClick("Wield");
		f.containerChanged(INVENTORY);
		f.containerChanged(EQUIPMENT, ABYSSAL_WHIP, 1);
		List<LedgerEvent> events = f.gameTick();

		assertEquals(2, SnapshotFixtures.withCategory(events, MovementCategory.TRANSFER).size());
		assertEquals(0, flagged(events));
	}

	/**
	 * Identity collapse happens before any of this, so a noted item withdrawn into a container
	 * being observed for the first time still reconciles against the unnoted id.
	 */
	@Test
	public void canonicalisationStillAppliesThroughTheReconciledPath()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.seed(BANK, DRAGON_BONES, 5000);
		assertFalse(f.isSeeded(INVENTORY));

		f.menuClick("Withdraw-X", IFACE_BANKMAIN);
		f.containerChanged(BANK, DRAGON_BONES, 4000);
		f.containerChanged(INVENTORY, DRAGON_BONES_NOTED, 1000);
		List<LedgerEvent> events = f.gameTick();

		assertEquals(2, SnapshotFixtures.withCategory(events, MovementCategory.TRANSFER).size());
		assertEquals(0, SnapshotFixtures.phantomCount(events));
		for (LedgerEvent e : events)
		{
			assertEquals("both legs on the unnoted identity",
				Integer.valueOf(DRAGON_BONES), e.getItemId());
		}
	}

	// ---- Confidence must reflect capture time, not resolution time ----

	/**
	 * The secondary half of the production defect. Equipment became known during the tick, so by
	 * resolution time the counterparty rule saw a seeded container and flagged nothing — the three
	 * losses read as fully trustworthy. A movement captured while its counterpart was UNKNOWN must
	 * not become clean merely because that counterpart turned up before the tick resolved.
	 */
	@Test
	public void aLossIsNotCleanJustBecauseTheCounterpartTurnedUpLater()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.seed(INVENTORY, SHARK, 10);
		assertFalse(f.isSeeded(EQUIPMENT));

		f.worldClick("Drop");
		f.containerChanged(INVENTORY, SHARK, 9);
		// Equipment turns up in the same tick, holding something unrelated.
		f.containerChanged(EQUIPMENT, ABYSSAL_WHIP, 1);
		List<LedgerEvent> events = f.gameTick();

		assertEquals(1, events.size());
		assertTrue("captured while equipment was UNKNOWN, so it stays uncorroborated",
			events.get(0).hasFlag(LedgerEvent.FLAG_COUNTERPARTY_UNSEEDED));
		assertEquals(0, SnapshotFixtures.phantomCount(events));
		assertTrue("but it is now seeded for the next tick", f.isSeeded(EQUIPMENT));
	}

	/**
	 * And on the following tick the same loss is clean, because equipment really was known when it
	 * was captured.
	 */
	@Test
	public void theNextTickIsCleanOnceTheCounterpartWasKnownAtCapture()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.seed(INVENTORY, SHARK, 10);
		f.containerChanged(EQUIPMENT, ABYSSAL_WHIP, 1);
		f.gameTick();

		f.worldClick("Drop");
		f.containerChanged(INVENTORY, SHARK, 9);
		List<LedgerEvent> events = f.gameTick();

		assertEquals(1, events.size());
		assertTrue(events.get(0).getFlags().isEmpty());
		assertEquals(1, SnapshotFixtures.phantomCount(events));
	}

	// ---- Housekeeping ----

	@Test
	public void reconciledLegsAreNotDuplicated()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.seed(INVENTORY, TOP, 2);

		f.containerChanged(INVENTORY);
		f.containerChanged(EQUIPMENT, TOP, 2);
		List<LedgerEvent> events = f.gameTick();

		assertEquals(2, events.size());
		assertEquals(1, countFor(events, INVENTORY, TOP));
		assertEquals(1, countFor(events, EQUIPMENT, TOP));
		assertEquals(Integer.valueOf(-2), legFor(events, INVENTORY, TOP).getQty());
		assertEquals(Integer.valueOf(2), legFor(events, EQUIPMENT, TOP).getQty());
	}

	/**
	 * One first-observed item cannot corroborate two different losses. Two containers each lose
	 * two, but the observation only holds two, so exactly one loss is covered and the other stays
	 * a real, unexplained movement.
	 */
	@Test
	public void corroborationIsConsumedNotReused()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.seed(INVENTORY, TOP, 5);
		f.seed(BANK, TOP, 5);

		f.containerChanged(INVENTORY, TOP, 3);      // -2
		f.containerChanged(BANK, TOP, 3);           // -2
		f.containerChanged(EQUIPMENT, TOP, 2);      // first observation, only 2 to give
		List<LedgerEvent> events = f.gameTick();

		// Ascending container id, so the inventory loss is the one that gets corroborated.
		assertEquals(2, flagged(events));
		assertEquals(2, SnapshotFixtures.withCategory(events, MovementCategory.TRANSFER).size());
		assertEquals(Integer.valueOf(-2), legFor(events, INVENTORY, TOP).getQty());
		assertEquals("the observation gave everything it had and no more", 2,
			totalQty(events, EQUIPMENT, TOP));

		// The bank loss had nothing left to match against and is still reported.
		assertEquals(Integer.valueOf(-2), legFor(events, BANK, TOP).getQty());
		assertEquals(1, SnapshotFixtures.unverifiedCount(events));
		assertEquals(0, SnapshotFixtures.phantomCount(events));
		assertEquals(3, events.size());
	}

	@Test
	public void orderingIsDeterministic()
	{
		List<String> shapes = new ArrayList<>();
		for (int run = 0; run < 5; run++)
		{
			SnapshotFixtures f = new SnapshotFixtures();
			f.seed(INVENTORY, HOOD, 1, CAPE, 1, TOP, 1);
			f.containerChanged(INVENTORY, HOOD, 1);
			f.containerChanged(EQUIPMENT, CAPE, 1, TOP, 1);
			shapes.add(SnapshotFixtures.describe(f.gameTick()));
		}
		for (String shape : shapes)
		{
			assertEquals(shapes.get(0), shape);
		}
	}

	// ---- helpers ----

	private static LedgerEvent legFor(List<LedgerEvent> events, int containerId, int itemId)
	{
		for (LedgerEvent e : events)
		{
			if (e.getContainerId() != null && e.getContainerId() == containerId
				&& e.getItemId() != null && e.getItemId() == itemId)
			{
				return e;
			}
		}
		throw new AssertionError("no leg for container " + containerId + " item " + itemId
			+ SnapshotFixtures.describe(events));
	}

	private static int countFor(List<LedgerEvent> events, int containerId, int itemId)
	{
		int n = 0;
		for (LedgerEvent e : events)
		{
			if (e.getContainerId() != null && e.getContainerId() == containerId
				&& e.getItemId() != null && e.getItemId() == itemId)
			{
				n++;
			}
		}
		return n;
	}

	private static int totalQty(List<LedgerEvent> events, int containerId, int itemId)
	{
		int total = 0;
		for (LedgerEvent e : events)
		{
			if (e.getContainerId() != null && e.getContainerId() == containerId
				&& e.getItemId() != null && e.getItemId() == itemId)
			{
				total += e.getQty();
			}
		}
		return total;
	}

	private static int flagged(List<LedgerEvent> events)
	{
		int n = 0;
		for (LedgerEvent e : events)
		{
			if (e.hasFlag(LedgerEvent.FLAG_FIRST_OBSERVATION_RECONCILED))
			{
				n++;
			}
		}
		return n;
	}
}
