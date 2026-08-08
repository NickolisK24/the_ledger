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
import static org.junit.Assert.assertTrue;

/**
 * Death as it actually happens, rather than as it is convenient to model.
 * <p>
 * A real session logged a PLAYER_DEATH at tick 393 and a STATE_RESET at tick 400, and produced
 * <b>zero</b> DEATH_LOSS events for the whole session — while every death fixture passed. The
 * gap was ordering: dying triggers a respawn region load, so the reset always follows the death,
 * and the reset both closed the window and reseeded the carried containers, so the wipe was
 * absorbed as if the containers had never been seen.
 * <p>
 * These fixtures model the real sequence: ActorDeath, then the container wipe, then
 * GameStateChanged(LOADING) — and the variant where the reset lands first.
 */
public class DeathThroughLoadingTest
{
	@Test
	public void deathThenWipeThenLoadingStillProducesDeathLosses()
	{
		SnapshotFixtures f = geared();

		f.death();
		f.containerChanged(INVENTORY);
		f.containerChanged(EQUIPMENT);
		List<LedgerEvent> wipe = f.gameTick();

		// The respawn region load lands a few ticks after the death, as it did at t393 -> t400.
		f.advanceTick();
		f.advanceTick();
		LedgerEvent reset = f.stateReset("LOADING");

		assertEquals(4, SnapshotFixtures.withCategory(wipe, MovementCategory.DEATH_LOSS).size());
		assertEquals(0, SnapshotFixtures.phantomCount(wipe));
		assertEquals(EventType.STATE_RESET, reset.getType());
		assertTrue("the reset arrived while the death was unresolved",
			reset.hasFlag(LedgerEvent.FLAG_DEATH_BASELINE_HELD));
	}

	/**
	 * The case that actually broke. The load arrives BEFORE the client reports the emptied
	 * containers, so the wipe is diffed against baselines that a reset would have thrown away.
	 */
	@Test
	public void loadingBeforeTheWipeMustNotAbsorbIt()
	{
		SnapshotFixtures f = geared();

		f.death();
		f.advanceTick();
		LedgerEvent reset = f.stateReset("LOADING");
		assertTrue(reset.hasFlag(LedgerEvent.FLAG_DEATH_BASELINE_HELD));

		// Only now does the client send the emptied containers.
		f.containerChanged(INVENTORY);
		f.containerChanged(EQUIPMENT);
		List<LedgerEvent> events = f.gameTick();

		assertEquals(SnapshotFixtures.describe(events), 4,
			SnapshotFixtures.withCategory(events, MovementCategory.DEATH_LOSS).size());
		assertEquals(0, SnapshotFixtures.phantomCount(events));
		for (LedgerEvent e : events)
		{
			assertTrue(e.hasFlag(LedgerEvent.FLAG_DEATH_WINDOW));
			assertTrue(e.getQty() < 0);
		}
	}

	/**
	 * The carried containers keep their baselines through the respawn load; the bank does not,
	 * because nothing about a death tells us what is in it.
	 */
	@Test
	public void respawnLoadKeepsCarriedBaselinesAndDropsTheRest()
	{
		SnapshotFixtures f = geared();
		f.seed(LedgerContainers.BANK, COINS, 50_000_000);

		f.death();
		f.stateReset("LOADING");

		assertTrue(f.snapshotOf(INVENTORY).isKnown());
		assertTrue(f.snapshotOf(EQUIPMENT).isKnown());
		assertTrue("the bank is not part of a death", !f.snapshotOf(LedgerContainers.BANK).isKnown());
	}

	/**
	 * The wipe is measured against what was actually being carried, not against an empty guess.
	 */
	@Test
	public void deathLossQuantitiesMatchWhatWasCarried()
	{
		SnapshotFixtures f = geared();

		f.death();
		f.stateReset("LOADING");
		f.containerChanged(INVENTORY);
		f.containerChanged(EQUIPMENT);
		List<LedgerEvent> events = f.gameTick();

		int coins = 0;
		int sharks = 0;
		for (LedgerEvent e : events)
		{
			if (e.getItemId() == COINS)
			{
				coins = e.getQty();
			}
			if (e.getItemId() == SHARK)
			{
				sharks = e.getQty();
			}
		}
		assertEquals(-500_000, coins);
		assertEquals(-15, sharks);
	}

	/**
	 * A load that is not part of a death still reseeds everything, so an ordinary teleport cannot
	 * borrow the death exception.
	 */
	@Test
	public void anOrdinaryLoadStillDropsTheCarriedBaselines()
	{
		SnapshotFixtures f = geared();

		f.stateReset("LOADING");

		assertTrue(!f.snapshotOf(INVENTORY).isKnown());
		assertTrue(!f.snapshotOf(EQUIPMENT).isKnown());
	}

	/**
	 * Once the death is resolved, the next load behaves normally again.
	 */
	@Test
	public void afterTheWindowClosesLoadsReseedAgain()
	{
		SnapshotFixtures f = geared();

		f.death();
		f.stateReset("LOADING");
		f.containerChanged(INVENTORY);
		f.containerChanged(EQUIPMENT);
		f.gameTick();

		// Run well past the extended window.
		for (int i = 0; i < MovementResolver.DEFAULT_DEATH_WINDOW_TICKS * 2; i++)
		{
			f.gameTick();
		}
		f.seed(INVENTORY, SHARK, 3);
		f.stateReset("LOADING");

		assertTrue(!f.snapshotOf(INVENTORY).isKnown());
	}

	private static SnapshotFixtures geared()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.seed(INVENTORY, COINS, 500_000, SHARK, 15);
		f.seed(EQUIPMENT, RUNE_PLATEBODY, 1, ABYSSAL_WHIP, 1);
		return f;
	}
}
