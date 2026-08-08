package com.nikko.theledger.capture;

import com.nikko.theledger.model.LedgerEvent;
import com.nikko.theledger.model.MovementCategory;
import java.util.List;
import org.junit.Test;
import static com.nikko.theledger.capture.LedgerContainers.BANK;
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
 * The experimental region-load path, kept behind a config toggle that defaults off.
 * <p>
 * A logged session recorded 27 STATE_RESET events in 916 ticks, every one of them LOADING, and
 * each one absorbed the next real movement in every container. The hypothesis is that a LOADING
 * reached directly from LOGGED_IN is a teleport, for which the client does not resend containers
 * — so invalidating there costs signal and buys nothing.
 * <p>
 * The hypothesis is unverified against a live client, which is why it ships off. These fixtures
 * pin what the two settings do so an A/B comparison has a fixed reference on the code side.
 */
public class RegionLoadTest
{
	@Test
	public void keepingBaselinesLeavesEveryContainerReadable()
	{
		SnapshotFixtures f = loaded();

		f.regionLoadKeepingBaselines();

		assertTrue(f.snapshotOf(INVENTORY).isKnown());
		assertTrue(f.snapshotOf(EQUIPMENT).isKnown());
		assertTrue(f.snapshotOf(BANK).isKnown());
	}

	/**
	 * The whole point: the movement after a teleport is reported instead of being swallowed.
	 */
	@Test
	public void theNextMovementAfterATeleportIsNotAbsorbed()
	{
		SnapshotFixtures f = loaded();

		f.regionLoadKeepingBaselines();
		f.worldClick("Eat");
		f.containerChanged(INVENTORY, COINS, 3_000_000, SHARK, 19);
		List<LedgerEvent> events = f.gameTick();

		assertEquals(SnapshotFixtures.describe(events), 1, events.size());
		assertEquals(MovementCategory.UNCLASSIFIED_LOSS, events.get(0).getCategory());
		assertEquals(Integer.valueOf(-1), events.get(0).getQty());
	}

	/**
	 * The same teleport under the conservative default. The eating is lost — this is the cost
	 * being traded away, stated explicitly so the comparison is honest in both directions.
	 */
	@Test
	public void underTheConservativeDefaultTheSameMovementIsAbsorbed()
	{
		SnapshotFixtures f = loaded();

		f.stateReset("LOADING");
		f.containerChanged(INVENTORY, COINS, 3_000_000, SHARK, 19);
		List<LedgerEvent> events = f.gameTick();

		assertEquals("the reseed swallows it", 0, events.size());
		assertFalse(f.snapshotOf(EQUIPMENT).isKnown());
	}

	/**
	 * Neither setting may invent anything. If the hypothesis about client behaviour is wrong and
	 * containers DO repopulate on a region change, this is where it shows up as a phantom.
	 */
	@Test
	public void repopulationAfterKeepingBaselinesProducesNoPhantom()
	{
		SnapshotFixtures f = loaded();

		f.regionLoadKeepingBaselines();
		// The client resends identical containers, which is what a repopulation looks like when
		// nothing actually changed.
		f.containerChanged(INVENTORY, COINS, 3_000_000, SHARK, 20);
		f.containerChanged(EQUIPMENT, RUNE_PLATEBODY, 1, ABYSSAL_WHIP, 1);
		f.containerChanged(BANK, COINS, 80_000_000);
		List<LedgerEvent> events = f.gameTick();

		assertEquals(SnapshotFixtures.describe(events), 0, events.size());
		assertEquals(0, SnapshotFixtures.phantomCount(events));
	}

	/**
	 * A death still has to survive the respawn load on this path. No STATE_RESET is recorded, so
	 * the window is pushed back by the transition itself rather than by the reset.
	 */
	@Test
	public void deathSurvivesARegionLoadThatKeepsBaselines()
	{
		SnapshotFixtures f = loaded();

		f.death();
		f.advanceTick();
		f.regionLoadKeepingBaselines();
		assertTrue(f.resolver().isInDeathWindow(f.tick()));

		f.containerChanged(INVENTORY);
		f.containerChanged(EQUIPMENT);
		List<LedgerEvent> events = f.gameTick();

		assertEquals(SnapshotFixtures.describe(events), 4,
			SnapshotFixtures.withCategory(events, MovementCategory.DEATH_LOSS).size());
		assertEquals(0, SnapshotFixtures.phantomCount(events));
	}

	/**
	 * The discrimination is on the PREVIOUS state, so a login or a hop still reseeds fully — those
	 * states invalidate on their own account before LOADING is ever reached.
	 */
	@Test
	public void loginAndHopStillReseedRegardless()
	{
		SnapshotFixtures f = loaded();
		f.stateReset("LOGGING_IN");
		assertFalse(f.snapshotOf(INVENTORY).isKnown());

		SnapshotFixtures g = loaded();
		g.stateReset("HOPPING");
		assertFalse(g.snapshotOf(INVENTORY).isKnown());

		SnapshotFixtures h = loaded();
		h.stateReset("CONNECTION_LOST");
		assertFalse(h.snapshotOf(INVENTORY).isKnown());
	}

	private static SnapshotFixtures loaded()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.seed(INVENTORY, COINS, 3_000_000, SHARK, 20);
		f.seed(EQUIPMENT, RUNE_PLATEBODY, 1, ABYSSAL_WHIP, 1);
		f.seed(BANK, COINS, 80_000_000);
		return f;
	}
}
