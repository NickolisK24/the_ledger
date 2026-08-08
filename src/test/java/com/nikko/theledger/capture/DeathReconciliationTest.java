package com.nikko.theledger.capture;

import com.nikko.theledger.model.EventType;
import com.nikko.theledger.model.LedgerEvent;
import com.nikko.theledger.model.MovementCategory;
import java.util.List;
import org.junit.Test;
import static com.nikko.theledger.capture.LedgerContainers.EQUIPMENT;
import static com.nikko.theledger.capture.LedgerContainers.INVENTORY;
import static com.nikko.theledger.capture.SnapshotFixtures.ABYSSAL_WHIP;
import static com.nikko.theledger.capture.SnapshotFixtures.COINS;
import static com.nikko.theledger.capture.SnapshotFixtures.RUNE_PLATEBODY;
import static com.nikko.theledger.capture.SnapshotFixtures.SHARK;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Death as a lifecycle sequence rather than a duration.
 * <p>
 * The old model started a five-tick window at {@code ActorDeath}. A live death reached its respawn
 * transition at tick 269 having died at 262, so the window had already expired: the carried
 * baselines were reseeded, the wipe was absorbed, and the recovered items surfaced later as
 * unflagged gains — a missing loss and a fabricated revenue in one event. Widening the number would
 * have moved the cliff rather than removed it, because the gap is a death animation plus a server
 * round trip and neither is constant.
 * <p>
 * Death now advances on evidence: the respawn transition moves it along, and both carried
 * containers reporting closes it. Loss is measured on the combined carried state, so an item that
 * was worn and is now in the inventory was never lost.
 */
public class DeathReconciliationTest
{
	private static final int LAW = 563;
	private static final int SOUL = 566;
	private static final int MEDALLION = 22400;

	/**
	 * The exact live timing that broke the old model: death at 262, respawn at 269.
	 */
	@Test
	public void theSevenTickRespawnThatBrokeTheFixedWindow()
	{
		SnapshotFixtures f = geared();
		while (f.tick() < 262)
		{
			f.advanceTick();
		}

		f.death();
		assertEquals(MovementResolver.DeathPhase.AWAITING_RESPAWN, f.resolver().getDeathPhase());

		for (int i = 0; i < 7; i++)
		{
			f.deathTick();
		}
		assertEquals("tick 269, and the old model had already expired at 267", 269, f.tick());
		assertTrue(f.resolver().isDeathPending());

		f.respawnLoad();
		assertEquals(MovementResolver.DeathPhase.AWAITING_WIPE, f.resolver().getDeathPhase());

		f.carriedReported(INVENTORY);
		List<LedgerEvent> events = f.carriedReported(EQUIPMENT);

		assertTrue("the wipe is accounted for", deathLosses(events) > 0);
		assertEquals(MovementResolver.DeathPhase.IDLE, f.resolver().getDeathPhase());
	}

	/**
	 * A normal death: several things lost, one kept, gear worn. The quantities are what
	 * disappeared, not what was carried.
	 */
	@Test
	public void lostQuantitiesAreWhatDisappearedNotWhatWasCarried()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.seed(INVENTORY, LAW, 10, SOUL, 10, SHARK, 4, MEDALLION, 1);
		f.seed(EQUIPMENT, RUNE_PLATEBODY, 1, ABYSSAL_WHIP, 1);

		f.death();
		f.respawnLoad();
		// Kept: the medallion. Everything else gone, and the gear with it.
		f.carriedReported(INVENTORY, MEDALLION, 1);
		List<LedgerEvent> events = f.carriedReported(EQUIPMENT);

		assertEquals(5, deathLosses(events));
		assertEquals(-10, qtyOf(events, LAW));
		assertEquals(-10, qtyOf(events, SOUL));
		assertEquals(-4, qtyOf(events, SHARK));
		assertEquals(-1, qtyOf(events, RUNE_PLATEBODY));
		assertEquals(-1, qtyOf(events, ABYSSAL_WHIP));
		assertEquals("kept, so never lost", 0, countOf(events, MEDALLION));
		assertEquals(0, SnapshotFixtures.phantomCount(events));
	}

	@Test
	public void partOfAStackLostIsPartOfAStackLost()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.seed(INVENTORY, LAW, 100);
		f.seed(EQUIPMENT);

		f.death();
		f.respawnLoad();
		f.carriedReported(INVENTORY, LAW, 40);
		List<LedgerEvent> events = f.carriedReported(EQUIPMENT);

		assertEquals(1, deathLosses(events));
		assertEquals(-60, qtyOf(events, LAW));
	}

	@Test
	public void anItemKeptInEquipmentIsNotLost()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.seed(INVENTORY, LAW, 10);
		f.seed(EQUIPMENT, RUNE_PLATEBODY, 1);

		f.death();
		f.respawnLoad();
		f.carriedReported(INVENTORY);
		List<LedgerEvent> events = f.carriedReported(EQUIPMENT, RUNE_PLATEBODY, 1);

		assertEquals(1, deathLosses(events));
		assertEquals(-10, qtyOf(events, LAW));
		assertEquals(0, countOf(events, RUNE_PLATEBODY));
	}

	/**
	 * The reason loss is measured on the combined carried state. An item that was worn and is now
	 * in the inventory did not go anywhere; treating each container's difference as final would
	 * report it as both a loss and a gain.
	 */
	@Test
	public void anItemThatMovedFromEquipmentToInventoryIsNotLost()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.seed(INVENTORY, LAW, 10);
		f.seed(EQUIPMENT, RUNE_PLATEBODY, 1);

		f.death();
		f.respawnLoad();
		f.carriedReported(INVENTORY, RUNE_PLATEBODY, 1);
		List<LedgerEvent> events = f.carriedReported(EQUIPMENT);

		assertEquals("only the runes went", 1, deathLosses(events));
		assertEquals(-10, qtyOf(events, LAW));
		assertEquals(0, countOf(events, RUNE_PLATEBODY));
	}

	@Test
	public void anItemThatMovedFromInventoryToEquipmentIsNotLost()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.seed(INVENTORY, RUNE_PLATEBODY, 1, LAW, 10);
		f.seed(EQUIPMENT);

		f.death();
		f.respawnLoad();
		f.carriedReported(INVENTORY);
		List<LedgerEvent> events = f.carriedReported(EQUIPMENT, RUNE_PLATEBODY, 1);

		assertEquals(1, deathLosses(events));
		assertEquals(-10, qtyOf(events, LAW));
	}

	// ---- Closure is driven by evidence ----

	@Test
	public void oneContainerReportingIsNotEnough()
	{
		SnapshotFixtures f = geared();
		f.death();
		f.respawnLoad();

		List<LedgerEvent> partial = f.carriedReported(INVENTORY);

		assertTrue("still waiting on equipment", partial.isEmpty());
		assertEquals(MovementResolver.DeathPhase.AWAITING_WIPE, f.resolver().getDeathPhase());

		List<LedgerEvent> events = f.carriedReported(EQUIPMENT);
		assertTrue(deathLosses(events) > 0);
		assertEquals(MovementResolver.DeathPhase.IDLE, f.resolver().getDeathPhase());
	}

	@Test
	public void bothContainersReportingClosesItImmediately()
	{
		SnapshotFixtures f = geared();
		f.death();
		f.respawnLoad();
		f.carriedReported(INVENTORY);
		f.carriedReported(EQUIPMENT);

		assertEquals(MovementResolver.DeathPhase.IDLE, f.resolver().getDeathPhase());
		assertFalse(f.resolver().isDeathPending());
	}

	/**
	 * A container that is UNKNOWN after the transition is absence of evidence, not evidence of a
	 * wipe. Manufacturing one from it would be the same mistake first observations exist to prevent.
	 */
	@Test
	public void anUnknownCarriedContainerIsNotEvidenceOfAWipe()
	{
		SnapshotFixtures f = geared();
		f.death();
		f.respawnLoad();
		f.carriedReported(INVENTORY);

		f.stateReset("LOADING");            // knocks the baselines back to UNKNOWN
		List<LedgerEvent> events = f.deathTick();

		assertTrue("nothing may be concluded from a container we cannot see", events.isEmpty());
	}

	// ---- Abnormal lifecycle: fail closed ----

	@Test
	public void logoutDuringAPendingDeathFailsClosed()
	{
		assertFailsClosed("LOGIN_SCREEN");
	}

	@Test
	public void connectionLossDuringAPendingDeathFailsClosed()
	{
		assertFailsClosed("CONNECTION_LOST");
	}

	@Test
	public void worldHopDuringAPendingDeathFailsClosed()
	{
		assertFailsClosed("HOPPING");
	}

	private void assertFailsClosed(String reason)
	{
		SnapshotFixtures f = geared();
		f.death();

		LedgerEvent failure = f.resolver().failDeathClosed(f.tick(), f.ts(), reason);

		assertEquals(EventType.DATA_LOSS, failure.getType());
		assertTrue(failure.hasFlag(LedgerEvent.FLAG_DEATH_RECONCILE_FAILED));
		assertTrue(failure.getActionContext().contains(reason));
		assertEquals("a failed death is over, not lingering",
			MovementResolver.DeathPhase.IDLE, f.resolver().getDeathPhase());
	}

	/**
	 * The safety budget is not "the death is probably over by now". It is the point at which
	 * reconciliation has demonstrably failed and must say so rather than resume clean accounting.
	 */
	@Test
	public void theSafetyBudgetFailsClosedRatherThanResuming()
	{
		SnapshotFixtures f = geared();
		f.death();

		List<LedgerEvent> events = null;
		int ticksWaited = 0;
		for (int i = 0; i <= MovementResolver.DEATH_RECONCILE_BUDGET_TICKS + 2; i++)
		{
			events = f.deathTick();
			ticksWaited++;
			if (!events.isEmpty())
			{
				break;
			}
		}

		assertTrue("it must not fire before the budget is genuinely spent",
			ticksWaited > MovementResolver.DEATH_RECONCILE_BUDGET_TICKS);
		assertEquals(1, events.size());
		assertEquals(EventType.DATA_LOSS, events.get(0).getType());
		assertTrue(events.get(0).hasFlag(LedgerEvent.FLAG_DEATH_RECONCILE_FAILED));
		assertEquals(MovementResolver.DeathPhase.IDLE, f.resolver().getDeathPhase());
	}

	@Test
	public void theBudgetIsGenerousEnoughForARealRespawn()
	{
		SnapshotFixtures f = geared();
		f.death();
		for (int i = 0; i < 10; i++)
		{
			assertTrue("ten ticks is well inside the budget", f.deathTick().isEmpty());
		}
		assertTrue(f.resolver().isDeathPending());
	}

	@Test
	public void aDeathCannotStayPinnedIndefinitely()
	{
		SnapshotFixtures f = geared();
		f.death();
		for (int i = 0; i < MovementResolver.DEATH_RECONCILE_BUDGET_TICKS * 3; i++)
		{
			f.deathTick();
		}
		assertFalse(f.resolver().isDeathPending());
	}

	// ---- Afterwards ----

	@Test
	public void ordinaryMovementResumesAfterASuccessfulReconciliation()
	{
		SnapshotFixtures f = geared();
		f.death();
		f.respawnLoad();
		f.carriedReported(INVENTORY);
		f.carriedReported(EQUIPMENT);

		f.seed(INVENTORY, SHARK, 10);
		f.seed(EQUIPMENT);
		f.worldClick("Eat");
		f.containerChanged(INVENTORY, SHARK, 9);
		List<LedgerEvent> events = f.gameTick();

		assertEquals(1, events.size());
		assertEquals(MovementCategory.UNCLASSIFIED_LOSS, events.get(0).getCategory());
	}

	@Test
	public void aSecondDeathWorksAfterTheFirstOneCompleted()
	{
		SnapshotFixtures f = geared();
		f.death();
		f.respawnLoad();
		f.carriedReported(INVENTORY);
		f.carriedReported(EQUIPMENT);

		f.seed(INVENTORY, LAW, 25);
		f.seed(EQUIPMENT, RUNE_PLATEBODY, 1);
		f.death();
		f.respawnLoad();
		f.carriedReported(INVENTORY);
		List<LedgerEvent> events = f.carriedReported(EQUIPMENT);

		assertEquals(2, deathLosses(events));
		assertEquals(-25, qtyOf(events, LAW));
		assertEquals(2, SnapshotFixtures.ofType(f.allEvents(), EventType.PLAYER_DEATH).size());
	}

	@Test
	public void deathLossesAreNotDuplicated()
	{
		SnapshotFixtures f = geared();
		f.death();
		f.respawnLoad();
		f.carriedReported(INVENTORY);
		f.carriedReported(EQUIPMENT);

		// Further ticks must add nothing: the death is closed.
		assertTrue(f.deathTick().isEmpty());
		assertTrue(f.deathTick().isEmpty());

		List<LedgerEvent> all = f.allEvents();
		assertEquals(1, SnapshotFixtures.ofType(all, EventType.PLAYER_DEATH).size());
		assertEquals(4, deathLosses(all));
	}

	/**
	 * The frozen baseline is what was carried at the instant of death. Ordinary snapshot churn
	 * afterwards must not be able to redefine it.
	 */
	@Test
	public void laterSnapshotChurnCannotRedefineWhatWasCarried()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.seed(INVENTORY, LAW, 50);
		f.seed(EQUIPMENT, RUNE_PLATEBODY, 1);

		f.death();
		// Something rewrites the inventory before the respawn lands.
		f.seed(INVENTORY, COINS, 999);
		f.respawnLoad();
		f.carriedReported(INVENTORY);
		List<LedgerEvent> events = f.carriedReported(EQUIPMENT);

		assertEquals("measured against the 50 law runes that were actually carried",
			-50, qtyOf(events, LAW));
		assertEquals(-1, qtyOf(events, RUNE_PLATEBODY));
		assertEquals(0, countOf(events, COINS));
	}

	@Test
	public void aTransferInsideCarriedStateIsNotADeathLoss()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.seed(INVENTORY, ABYSSAL_WHIP, 1);
		f.seed(EQUIPMENT);

		f.death();
		f.respawnLoad();
		f.carriedReported(INVENTORY);
		List<LedgerEvent> events = f.carriedReported(EQUIPMENT, ABYSSAL_WHIP, 1);

		assertEquals("it only changed hands", 0, deathLosses(events));
	}

	@Test
	public void nonDeathStateResetsAreUnchanged()
	{
		SnapshotFixtures f = geared();
		LedgerEvent reset = f.stateReset("LOADING");

		assertEquals(EventType.STATE_RESET, reset.getType());
		assertFalse(reset.hasFlag(LedgerEvent.FLAG_DEATH_BASELINE_HELD));
		assertFalse(f.resolver().isDeathPending());
	}

	@Test
	public void aResetInsideAPendingDeathIsFlagged()
	{
		SnapshotFixtures f = geared();
		f.death();
		LedgerEvent reset = f.respawnLoad();

		assertTrue(reset.hasFlag(LedgerEvent.FLAG_DEATH_BASELINE_HELD));
		assertTrue(f.snapshotOf(INVENTORY).isKnown());
		assertTrue(f.snapshotOf(EQUIPMENT).isKnown());
	}

	// ---- helpers ----

	private static SnapshotFixtures geared()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.seed(INVENTORY, LAW, 10, SOUL, 10, SHARK, 4);
		f.seed(EQUIPMENT, RUNE_PLATEBODY, 1);
		return f;
	}

	private static int deathLosses(List<LedgerEvent> events)
	{
		return SnapshotFixtures.withCategory(events, MovementCategory.DEATH_LOSS).size();
	}

	private static int qtyOf(List<LedgerEvent> events, int itemId)
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

	private static int countOf(List<LedgerEvent> events, int itemId)
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
