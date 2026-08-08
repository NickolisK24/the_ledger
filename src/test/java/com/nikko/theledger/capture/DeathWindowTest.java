package com.nikko.theledger.capture;

import com.nikko.theledger.model.EventType;
import com.nikko.theledger.model.LedgerEvent;
import com.nikko.theledger.model.MovementCategory;
import java.util.List;
import org.junit.Test;
import static com.nikko.theledger.capture.LedgerContainers.EQUIPMENT;
import static com.nikko.theledger.capture.LedgerContainers.IFACE_BANK_DEPOSITBOX;
import static com.nikko.theledger.capture.LedgerContainers.INVENTORY;
import static com.nikko.theledger.capture.SnapshotFixtures.ABYSSAL_WHIP;
import static com.nikko.theledger.capture.SnapshotFixtures.COINS;
import static com.nikko.theledger.capture.SnapshotFixtures.RUNE_PLATEBODY;
import static com.nikko.theledger.capture.SnapshotFixtures.SHARK;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A death empties the inventory and the equipment at once. Reported as UNCLASSIFIED_LOSS it
 * looks like the player destroyed their whole kit; it needs its own category so cost accounting
 * can treat it as a risk event rather than an expense.
 */
public class DeathWindowTest
{
	@Test
	public void deathProducesOnePlayerDeathAndOnlyDeathLosses()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.seed(INVENTORY, COINS, 500_000, SHARK, 15);
		f.seed(EQUIPMENT, RUNE_PLATEBODY, 1, ABYSSAL_WHIP, 1);

		LedgerEvent death = f.death();
		f.containerChanged(INVENTORY);
		f.containerChanged(EQUIPMENT);
		List<LedgerEvent> events = f.gameTick();

		assertEquals(EventType.PLAYER_DEATH, death.getType());
		assertEquals(1, SnapshotFixtures.ofType(f.allEvents(), EventType.PLAYER_DEATH).size());

		assertEquals(SnapshotFixtures.describe(events), 4, events.size());
		assertEquals(4, SnapshotFixtures.withCategory(events, MovementCategory.DEATH_LOSS).size());
		assertEquals(0, SnapshotFixtures.withCategory(events, MovementCategory.UNCLASSIFIED_LOSS).size());
		for (LedgerEvent e : events)
		{
			assertTrue(e.hasFlag(LedgerEvent.FLAG_DEATH_WINDOW));
			assertTrue(e.getQty() < 0);
		}
	}

	/**
	 * Equipment is not an afterthought here — it clears alongside the inventory and both must
	 * resolve the same way.
	 */
	@Test
	public void equipmentLossesAreDeathLossesToo()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.seed(EQUIPMENT, RUNE_PLATEBODY, 1);

		f.death();
		f.containerChanged(EQUIPMENT);
		List<LedgerEvent> events = f.gameTick();

		assertEquals(1, events.size());
		assertEquals(MovementCategory.DEATH_LOSS, events.get(0).getCategory());
		assertEquals(Integer.valueOf(EQUIPMENT), events.get(0).getContainerId());
	}

	@Test
	public void windowIsStillOpenOnTheLastTickOfTheDefaultWindow()
	{
		SnapshotFixtures f = new SnapshotFixtures(MovementResolver.DEFAULT_DEATH_WINDOW_TICKS);
		f.seed(INVENTORY, SHARK, 10);

		int deathTick = f.tick();
		f.death();
		for (int i = 0; i < MovementResolver.DEFAULT_DEATH_WINDOW_TICKS; i++)
		{
			f.gameTick();
		}
		assertEquals(deathTick + MovementResolver.DEFAULT_DEATH_WINDOW_TICKS, f.tick());
		assertTrue(f.resolver().isInDeathWindow(f.tick()));

		f.containerChanged(INVENTORY);
		List<LedgerEvent> events = f.gameTick();

		assertEquals(1, events.size());
		assertEquals(MovementCategory.DEATH_LOSS, events.get(0).getCategory());
	}

	@Test
	public void windowIsClosedOneTickLater()
	{
		SnapshotFixtures f = new SnapshotFixtures(MovementResolver.DEFAULT_DEATH_WINDOW_TICKS);
		f.loggedIn(SHARK, 10);

		f.death();
		for (int i = 0; i <= MovementResolver.DEFAULT_DEATH_WINDOW_TICKS; i++)
		{
			f.gameTick();
		}
		assertFalse(f.resolver().isInDeathWindow(f.tick()));

		f.containerChanged(INVENTORY);
		List<LedgerEvent> events = f.gameTick();

		assertEquals(1, events.size());
		assertEquals(MovementCategory.UNCLASSIFIED_LOSS, events.get(0).getCategory());
	}

	/**
	 * The window is configurable precisely so its length can be checked against a real death
	 * before it is treated as settled.
	 */
	@Test
	public void windowLengthIsConfigurable()
	{
		SnapshotFixtures f = new SnapshotFixtures(0);
		f.loggedIn(SHARK, 10);

		f.death();
		f.gameTick();
		f.containerChanged(INVENTORY);
		List<LedgerEvent> events = f.gameTick();

		assertEquals(MovementCategory.UNCLASSIFIED_LOSS, events.get(0).getCategory());
	}

	/**
	 * A death takes precedence over an inference. Dying with a deposit box click still lingering
	 * must not report the wipe as a deposit.
	 */
	@Test
	public void deathBeatsDepositInference()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.seed(INVENTORY, SHARK, 10);

		f.menuClick("Deposit inventory", IFACE_BANK_DEPOSITBOX);
		f.death();
		f.containerChanged(INVENTORY);
		List<LedgerEvent> events = f.gameTick();

		assertEquals(1, events.size());
		assertEquals(MovementCategory.DEATH_LOSS, events.get(0).getCategory());
		assertFalse(events.get(0).hasFlag(LedgerEvent.FLAG_INFERRED_DEPOSIT_BOX));
	}

	/**
	 * Netting still runs first: banking during the window is a transfer, not a death loss.
	 */
	@Test
	public void transferNettingStillAppliesInsideTheWindow()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.seed(INVENTORY, ABYSSAL_WHIP, 1);
		f.seed(EQUIPMENT);

		f.death();
		f.containerChanged(INVENTORY);
		f.containerChanged(EQUIPMENT, ABYSSAL_WHIP, 1);
		List<LedgerEvent> events = f.gameTick();

		assertEquals(2, SnapshotFixtures.withCategory(events, MovementCategory.TRANSFER).size());
		assertEquals(0, SnapshotFixtures.withCategory(events, MovementCategory.DEATH_LOSS).size());
	}

	/**
	 * This test used to assert the opposite, and that assertion is why DEATH_LOSS never fired once
	 * in a real session. Dying triggers a respawn region load, so a STATE_RESET always arrives
	 * after a death — closing the window there guaranteed it was shut before the wipe could be
	 * observed. The respawn load is part of the death sequence, not the end of it.
	 */
	@Test
	public void stateResetHoldsTheDeathWindowOpenForTheRespawnLoad()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.death();
		assertTrue(f.resolver().isInDeathWindow(f.tick()));

		f.stateReset("LOADING");

		assertTrue("the respawn load must not close the window",
			f.resolver().isInDeathWindow(f.tick()));
	}

	@Test
	public void stateResetOutsideADeathStillClosesNothingAndOpensNothing()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.stateReset("LOADING");
		assertFalse(f.resolver().isInDeathWindow(f.tick()));
	}

	/**
	 * The window cannot be held open forever by a run of transitions.
	 */
	@Test
	public void deathWindowExtensionsAreBounded()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.death();
		for (int i = 0; i < MovementResolver.MAX_DEATH_WINDOW_EXTENSIONS; i++)
		{
			f.stateReset("LOADING");
			assertTrue(f.resolver().isInDeathWindow(f.tick()));
			f.advanceTick();
		}

		f.stateReset("LOADING");

		assertFalse("the window must not be extendable indefinitely",
			f.resolver().isInDeathWindow(f.tick()));
	}
}
