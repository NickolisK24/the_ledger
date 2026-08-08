package com.nikko.theledger.capture;

import com.nikko.theledger.model.EventType;
import com.nikko.theledger.model.LedgerEvent;
import com.nikko.theledger.model.MovementCategory;
import java.util.List;
import org.junit.Test;
import static com.nikko.theledger.capture.LedgerContainers.BANK;
import static com.nikko.theledger.capture.LedgerContainers.EQUIPMENT;
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
 * Who owns a carried container's change while a death is unresolved.
 * <p>
 * A live death reconciled correctly and then, on the same tick, reported the same five losses a
 * second time as ordinary unexplained losses. Both halves ran in the order the client imposes:
 * the container changes arrive first, the game tick arrives afterwards, and the game tick
 * reconciles the death <i>before</i> it classifies what the tick buffered. Reconciliation
 * succeeded on the strength of exactly those buffered observations and closed the death — so by
 * the time the buffer reached ordinary classification the phase read IDLE, the suppression that
 * had been keyed to "is a death pending right now" no longer applied, and the wipe was counted
 * twice.
 * <p>
 * The defect is one of ownership, not of timing. A change captured while a death was open belongs
 * to that death for the rest of the tick, whatever the death does afterwards. Marking it at
 * capture is what makes the ownership outlive the phase that granted it.
 * <p>
 * Every fixture here therefore drives {@link SnapshotFixtures#containerChanged} and
 * {@link SnapshotFixtures#gameTick} rather than the resolver-level shortcut used by
 * {@link DeathReconciliationTest}. The shortcut asks reconciliation for a verdict mid-tick and
 * throws the deltas away, which is precisely the gap the live bug lived in.
 */
public class DeathOwnershipTest
{
	private static final int LAW = 563;
	private static final int SOUL = 566;

	/**
	 * The live failure, reproduced end to end: five real losses, and the five duplicates that used
	 * to accompany them.
	 */
	@Test
	public void aReconciledDeathIsNotClassifiedASecondTimeInTheSameTick()
	{
		SnapshotFixtures f = geared();

		f.death();
		f.respawnLoad();
		// The client's own order: both carried containers report, then the tick lands.
		f.containerChanged(INVENTORY);
		f.containerChanged(EQUIPMENT);
		List<LedgerEvent> events = f.gameTick();

		assertEquals("the wipe, counted once", 5, deathLosses(events));
		assertEquals("and never a second time as ordinary loss",
			0, count(events, MovementCategory.UNCLASSIFIED_LOSS));
		assertEquals(0, count(events, MovementCategory.UNCLASSIFIED_GAIN));
		assertEquals(0, SnapshotFixtures.phantomCount(events));
		assertEquals("nothing death-owned may escape into ordinary classification",
			5, SnapshotFixtures.ofType(events, EventType.ITEM_MOVEMENT).size());
		assertEquals(MovementResolver.DeathPhase.IDLE, f.resolver().getDeathPhase());
	}

	/**
	 * The same run, stated as quantities rather than counts, so a partially suppressed wipe cannot
	 * pass by emitting the right number of wrong lines.
	 */
	@Test
	public void theSurvivingLinesAreTheRealLosses()
	{
		SnapshotFixtures f = geared();

		f.death();
		f.respawnLoad();
		f.containerChanged(INVENTORY);
		f.containerChanged(EQUIPMENT);
		List<LedgerEvent> events = f.gameTick();

		assertEquals(-10, deathQty(events, LAW));
		assertEquals(-10, deathQty(events, SOUL));
		assertEquals(-4, deathQty(events, SHARK));
		assertEquals(-1, deathQty(events, RUNE_PLATEBODY));
		assertEquals(-1, deathQty(events, ABYSSAL_WHIP));
	}

	/**
	 * Ownership is granted at capture and must outlive the phase that granted it. By the time the
	 * buffer is classified the death has already closed on the strength of these very observations,
	 * so a suppression that consults the phase at classification time reads IDLE and lets them
	 * through.
	 */
	@Test
	public void ownershipSurvivesTheDeathClosingWithinTheSameTick()
	{
		SnapshotFixtures f = geared();

		f.death();
		f.respawnLoad();
		f.containerChanged(INVENTORY);
		f.containerChanged(EQUIPMENT);

		assertTrue("still open while the tick is being classified", f.resolver().isDeathPending());
		List<LedgerEvent> events = f.gameTick();
		assertFalse("and closed by the time it finished", f.resolver().isDeathPending());

		assertEquals(5, deathLosses(events));
		assertEquals(0, count(events, MovementCategory.UNCLASSIFIED_LOSS));
	}

	@Test
	public void ordinaryClassificationResumesOnTheFollowingTick()
	{
		SnapshotFixtures f = geared();

		f.death();
		f.respawnLoad();
		f.containerChanged(INVENTORY);
		f.containerChanged(EQUIPMENT);
		f.gameTick();

		f.containerChanged(INVENTORY, SHARK, 1);
		List<LedgerEvent> after = f.gameTick();

		assertEquals(1, after.size());
		assertEquals(MovementCategory.UNCLASSIFIED_GAIN, after.get(0).getCategory());
		assertEquals(1, (int) after.get(0).getQty());
	}

	// ---- Both carried containers, both directions ----

	/**
	 * Protected items land in the inventory on respawn. They are a gain in the inventory's own diff
	 * and they are not revenue: the reconciler already knows they were carried before and are
	 * carried still. The suppression that shipped covered losses only, so these arrived as
	 * unflagged gains — a fabricated income line on the worst possible tick.
	 */
	@Test
	public void protectedItemsArrivingInTheInventoryAreNotRevenue()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.seed(INVENTORY, LAW, 10);
		f.seed(EQUIPMENT, RUNE_PLATEBODY, 1, ABYSSAL_WHIP, 1, RUNE_ARROW, 50);

		f.death();
		f.respawnLoad();
		// Three items were protected and are now carried in the backpack; the runes went.
		f.containerChanged(INVENTORY, RUNE_PLATEBODY, 1, ABYSSAL_WHIP, 1, RUNE_ARROW, 50);
		f.containerChanged(EQUIPMENT);
		List<LedgerEvent> events = f.gameTick();

		assertEquals("only the runes were actually lost", 1, deathLosses(events));
		assertEquals(-10, deathQty(events, LAW));
		assertEquals(0, count(events, MovementCategory.UNCLASSIFIED_GAIN));
		assertEquals(0, count(events, MovementCategory.UNCLASSIFIED_LOSS));
		assertEquals(0, SnapshotFixtures.phantomCount(events));
		assertEquals("retained gear is not a death loss", 0, deathCount(events, RUNE_PLATEBODY));
		assertEquals(0, deathCount(events, ABYSSAL_WHIP));
		assertEquals(0, deathCount(events, RUNE_ARROW));
	}

	/**
	 * The gain-side asymmetry on its own, with the ordering defect held out of it.
	 * <p>
	 * The inventory reports its protected items a tick before the equipment reports losing them, so
	 * there is no counterpart leg to net against and the death is still plainly open. Suppression
	 * that covered losses only classified these as unexplained gains — wealth appearing out of a
	 * corpse. A death owns what it moved in both directions or it owns neither.
	 */
	@Test
	public void protectedItemsThatArriveBeforeTheirCounterpartAreNotRevenue()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.seed(INVENTORY, LAW, 10);
		f.seed(EQUIPMENT, RUNE_PLATEBODY, 1, ABYSSAL_WHIP, 1);

		f.death();
		f.respawnLoad();

		f.containerChanged(INVENTORY, RUNE_PLATEBODY, 1, ABYSSAL_WHIP, 1);
		List<LedgerEvent> first = f.gameTick();

		assertTrue("a one-legged gain inside an open death is still the death's", first.isEmpty());
		assertTrue(f.resolver().isDeathPending());

		f.containerChanged(EQUIPMENT);
		List<LedgerEvent> second = f.gameTick();

		assertEquals(1, deathLosses(second));
		assertEquals(-10, deathQty(second, LAW));
		assertEquals(0, count(f.allEvents(), MovementCategory.UNCLASSIFIED_GAIN));
		assertEquals(0, count(f.allEvents(), MovementCategory.UNCLASSIFIED_LOSS));
		assertEquals(0, SnapshotFixtures.phantomCount(f.allEvents()));
	}

	/**
	 * The mirror image: the equipment is what gains. Same rule, opposite container, so the
	 * suppression cannot be quietly one-sided.
	 */
	@Test
	public void anItemThatEndsUpWornIsNotAnEquipmentGain()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.seed(INVENTORY, RUNE_PLATEBODY, 1, LAW, 10);
		f.seed(EQUIPMENT);

		f.death();
		f.respawnLoad();
		f.containerChanged(INVENTORY);
		f.containerChanged(EQUIPMENT, RUNE_PLATEBODY, 1);
		List<LedgerEvent> events = f.gameTick();

		assertEquals(1, deathLosses(events));
		assertEquals(-10, deathQty(events, LAW));
		assertEquals(0, deathCount(events, RUNE_PLATEBODY));
		assertEquals(0, count(events, MovementCategory.UNCLASSIFIED_GAIN));
		assertEquals(0, count(events, MovementCategory.UNCLASSIFIED_LOSS));
	}

	/**
	 * A tick that is a gain, a loss and a retention at once. Suppressing by direction rather than by
	 * ownership passes half of this and fails the other half.
	 */
	@Test
	public void aTickThatGainsAndLosesAtOnceIsOwnedEntirely()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.seed(INVENTORY, LAW, 10, SOUL, 10, SHARK, 4);
		f.seed(EQUIPMENT, RUNE_PLATEBODY, 1, ABYSSAL_WHIP, 1);

		f.death();
		f.respawnLoad();
		// The whip was protected and is now carried; the platebody is still worn; the rest is gone.
		f.containerChanged(INVENTORY, ABYSSAL_WHIP, 1);
		f.containerChanged(EQUIPMENT, RUNE_PLATEBODY, 1);
		List<LedgerEvent> events = f.gameTick();

		assertEquals(3, deathLosses(events));
		assertEquals(-10, deathQty(events, LAW));
		assertEquals(-10, deathQty(events, SOUL));
		assertEquals(-4, deathQty(events, SHARK));
		assertEquals(0, deathCount(events, ABYSSAL_WHIP));
		assertEquals(0, deathCount(events, RUNE_PLATEBODY));
		assertEquals(0, count(events, MovementCategory.UNCLASSIFIED_GAIN));
		assertEquals(0, count(events, MovementCategory.UNCLASSIFIED_LOSS));
		assertEquals(0, count(events, MovementCategory.TRANSFER));
		assertEquals(0, SnapshotFixtures.phantomCount(events));
	}

	// ---- Evidence spread over more than one tick ----

	/**
	 * The containers do not have to report together. Whichever reports first is owned by the death
	 * on the tick it reported, even though that tick cannot yet conclude anything.
	 */
	@Test
	public void carriedReportsSplitAcrossTwoTicksAreBothOwned()
	{
		SnapshotFixtures f = geared();

		f.death();
		f.respawnLoad();

		f.containerChanged(INVENTORY);
		List<LedgerEvent> first = f.gameTick();

		assertTrue("no verdict is possible yet, and no ordinary movement either", first.isEmpty());
		assertEquals(MovementResolver.DeathPhase.AWAITING_WIPE, f.resolver().getDeathPhase());

		f.containerChanged(EQUIPMENT);
		List<LedgerEvent> second = f.gameTick();

		assertEquals(5, deathLosses(second));
		assertEquals(0, count(second, MovementCategory.UNCLASSIFIED_LOSS));
		assertEquals(0, count(second, MovementCategory.UNCLASSIFIED_GAIN));
		assertEquals("across both ticks, the wipe appears exactly once",
			5, deathLosses(f.allEvents()));
		assertEquals(0, SnapshotFixtures.phantomCount(f.allEvents()));
	}

	/**
	 * The ownership mark is per tick, like everything else the buffer holds. A container that
	 * reported during the death does not stay suppressed afterwards.
	 */
	@Test
	public void ownershipDoesNotLeakIntoTheNextTick()
	{
		SnapshotFixtures f = geared();

		f.death();
		f.respawnLoad();
		f.containerChanged(INVENTORY);
		f.gameTick();

		f.containerChanged(EQUIPMENT);
		f.gameTick();

		f.containerChanged(INVENTORY, COINS, 500);
		List<LedgerEvent> later = f.gameTick();

		assertEquals(1, later.size());
		assertEquals(MovementCategory.UNCLASSIFIED_GAIN, later.get(0).getCategory());
	}

	// ---- Scope ----

	/**
	 * Only the two containers a death empties are owned. A bank change during a pending death is
	 * ordinary banking and classifies as it always did.
	 */
	@Test
	public void anUntouchedContainerIsNotDeathOwned()
	{
		SnapshotFixtures f = geared();
		f.seed(BANK, COINS, 1000);

		f.death();
		f.containerChanged(BANK, COINS, 900);
		List<LedgerEvent> events = f.gameTick();

		assertEquals(1, events.size());
		assertEquals(MovementCategory.UNCLASSIFIED_LOSS, events.get(0).getCategory());
		assertEquals((Integer) BANK, events.get(0).getContainerId());
		assertTrue("still a death, still unresolved", f.resolver().isDeathPending());
	}

	/**
	 * The control. The identical container changes with no death behind them classify normally, so
	 * the suppression is scoped to a death and is not quietly swallowing carried movement in
	 * general.
	 */
	@Test
	public void theSameChangesWithoutADeathAreOrdinaryMovement()
	{
		SnapshotFixtures f = geared();

		f.containerChanged(INVENTORY);
		f.containerChanged(EQUIPMENT);
		List<LedgerEvent> events = f.gameTick();

		assertEquals(0, deathLosses(events));
		assertEquals(5, count(events, MovementCategory.UNCLASSIFIED_LOSS));
	}

	// ---- Fail-closed ----

	/**
	 * A death that never produces evidence must leave a hole, not a set of ordinary losses. The
	 * observations it collected on the way are still its own: they are covered by the DATA_LOSS
	 * marker, which says the log knows something economic happened and cannot say what.
	 */
	@Test
	public void aDeathThatFailsClosedDoesNotReleaseItsObservationsAsMovement()
	{
		SnapshotFixtures f = geared();

		f.death();
		f.respawnLoad();
		// Only one container ever reports, so reconciliation can never conclude.
		f.containerChanged(INVENTORY);
		f.gameTick();

		List<LedgerEvent> failure = null;
		for (int i = 0; i <= MovementResolver.DEATH_RECONCILE_BUDGET_TICKS + 2; i++)
		{
			failure = f.gameTick();
			if (!failure.isEmpty())
			{
				break;
			}
		}

		assertEquals(1, failure.size());
		assertEquals(EventType.DATA_LOSS, failure.get(0).getType());
		assertTrue(failure.get(0).hasFlag(LedgerEvent.FLAG_DEATH_RECONCILE_FAILED));

		List<LedgerEvent> all = f.allEvents();
		assertEquals("the hole is declared, not papered over with invented losses",
			0, count(all, MovementCategory.UNCLASSIFIED_LOSS));
		assertEquals(0, count(all, MovementCategory.UNCLASSIFIED_GAIN));
		assertEquals(0, deathLosses(all));
		assertEquals(0, SnapshotFixtures.phantomCount(all));
	}

	/**
	 * And a failed death releases the containers afterwards: the next tick is ordinary again.
	 */
	@Test
	public void movementResumesAfterADeathFailsClosed()
	{
		SnapshotFixtures f = geared();

		f.death();
		for (int i = 0; i <= MovementResolver.DEATH_RECONCILE_BUDGET_TICKS + 2; i++)
		{
			if (!f.gameTick().isEmpty())
			{
				break;
			}
		}
		assertFalse(f.resolver().isDeathPending());

		f.containerChanged(INVENTORY, LAW, 10, SOUL, 10, SHARK, 3);
		List<LedgerEvent> events = f.gameTick();

		assertEquals(1, events.size());
		assertEquals(MovementCategory.UNCLASSIFIED_LOSS, events.get(0).getCategory());
		assertEquals(-1, (int) events.get(0).getQty());
	}

	// ---- helpers ----

	private static SnapshotFixtures geared()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.seed(INVENTORY, LAW, 10, SOUL, 10, SHARK, 4);
		f.seed(EQUIPMENT, RUNE_PLATEBODY, 1, ABYSSAL_WHIP, 1);
		return f;
	}

	private static int count(List<LedgerEvent> events, MovementCategory category)
	{
		return SnapshotFixtures.withCategory(events, category).size();
	}

	private static int deathLosses(List<LedgerEvent> events)
	{
		return count(events, MovementCategory.DEATH_LOSS);
	}

	private static int deathQty(List<LedgerEvent> events, int itemId)
	{
		int total = 0;
		for (LedgerEvent e : events)
		{
			if (e.getCategory() == MovementCategory.DEATH_LOSS
				&& e.getItemId() != null && e.getItemId() == itemId)
			{
				total += e.getQty();
			}
		}
		return total;
	}

	private static int deathCount(List<LedgerEvent> events, int itemId)
	{
		int n = 0;
		for (LedgerEvent e : events)
		{
			if (e.getCategory() == MovementCategory.DEATH_LOSS
				&& e.getItemId() != null && e.getItemId() == itemId)
			{
				n++;
			}
		}
		return n;
	}
}
