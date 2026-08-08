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
import static org.junit.Assert.assertTrue;

/**
 * The case the guard fixtures missed: a container that never seeds for an entire session.
 * <p>
 * Equipment only fires {@code ItemContainerChanged} when it changes, so a session with no gear
 * swap left it UNKNOWN from login to logout. That is the normal case, not an edge case — and it
 * meant the counterparty rule fired on 100% of inventory movements. A real session produced 22 of
 * 22 unclassified losses flagged, every one of them rune consumption from casting teleports.
 * <p>
 * Those runes are destroyed. There is nothing to corroborate and never was, so "uncorroborated"
 * was the wrong claim about the largest cost category Phase 2 will have.
 * <p>
 * The fix is to stop waiting: read every tracked container directly at login. These fixtures use
 * {@link SnapshotFixtures#loggedIn} for exactly that, which is what the plugin now does on
 * startUp and on reaching LOGGED_IN.
 */
public class EagerSeedingTest
{
	/**
	 * The headline regression. Play for a while, never touch your gear, and nothing is flagged.
	 */
	@Test
	public void aSessionWithNoGearChangeProducesNoCounterpartyFlagsAfterStartup()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.loggedIn(COINS, 100_000, SHARK, 20, 554, 5000, 563, 500, 566, 500);

		int runes = 500;
		for (int cast = 0; cast < 11; cast++)
		{
			runes -= 2;
			f.worldClick("Cast");
			f.containerChanged(INVENTORY, COINS, 100_000, SHARK, 20, 554, 5000, 563, runes, 566, runes);
			List<LedgerEvent> events = f.gameTick();

			assertEquals("cast " + cast + SnapshotFixtures.describe(events), 0,
				SnapshotFixtures.unverifiedCount(events));
			assertEquals("cast " + cast, 2, SnapshotFixtures.phantomCount(events));
		}

		assertEquals(0, SnapshotFixtures.unverifiedCount(f.allEvents()));
		assertEquals(22, SnapshotFixtures.phantomCount(f.allEvents()));
	}

	/**
	 * A consumed rune has no counterpart by nature — it is destroyed. It must count as a real
	 * loss, because it is the raw material of every consumable cost figure Phase 2 will produce.
	 */
	@Test
	public void consumableUseIsNeverFlagged()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.loggedIn(SHARK, 10, 554, 1000);

		f.worldClick("Eat");
		f.containerChanged(INVENTORY, SHARK, 9, 554, 1000);
		List<LedgerEvent> eaten = f.gameTick();

		f.worldClick("Cast");
		f.containerChanged(INVENTORY, SHARK, 9, 554, 995);
		List<LedgerEvent> cast = f.gameTick();

		for (List<LedgerEvent> events : java.util.Arrays.asList(eaten, cast))
		{
			assertEquals(1, events.size());
			assertEquals(MovementCategory.UNCLASSIFIED_LOSS, events.get(0).getCategory());
			assertTrue("a destroyed consumable is a real cost, not an unverified one",
				events.get(0).getFlags().isEmpty());
		}
	}

	/**
	 * Seeding is not an event. Reading a container to establish a baseline must produce nothing,
	 * or logging in would report the account's entire kit as a gain.
	 */
	@Test
	public void theEagerSeedEmitsNoEvents()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.eagerSeed(INVENTORY, COINS, 3_000_000, SHARK, 20);
		f.eagerSeed(EQUIPMENT, RUNE_PLATEBODY, 1, ABYSSAL_WHIP, 1);
		f.eagerSeed(BANK, COINS, 80_000_000);

		assertEquals(0, f.gameTick().size());
		assertTrue(f.allEvents().isEmpty());
		assertTrue(f.isSeeded(INVENTORY));
		assertTrue(f.isSeeded(EQUIPMENT));
		assertTrue(f.isSeeded(BANK));
	}

	/**
	 * Equipment is readable at login even when it never changes, which is the whole point. The
	 * bank is not, and correctly stays UNKNOWN until its interface is opened.
	 */
	@Test
	public void loginSeedsCarriedContainersButNotTheBank()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.loggedIn(COINS, 1000);

		assertTrue(f.isSeeded(INVENTORY));
		assertTrue("equipment must not wait for a gear change", f.isSeeded(EQUIPMENT));
		assertTrue("the bank has nothing to know until it is opened", !f.isSeeded(BANK));
	}

	/**
	 * The flag has not been removed — it still fires during a genuine startup race, before the
	 * seed has run. That is the only situation it was ever meant to describe.
	 */
	@Test
	public void theFlagStillFiresDuringAGenuineStartupRace()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.seed(INVENTORY, ABYSSAL_WHIP, 1);
		assertTrue(!f.isSeeded(EQUIPMENT));

		f.worldClick("Wield");
		f.containerChanged(INVENTORY);
		List<LedgerEvent> racing = f.gameTick();

		assertEquals(1, SnapshotFixtures.unverifiedCount(racing));

		// Seed arrives, and the steady state is clean from here on.
		f.eagerSeed(EQUIPMENT, ABYSSAL_WHIP, 1);
		f.worldClick("Cast");
		f.containerChanged(INVENTORY, 554, 10);
		f.gameTick();
		f.worldClick("Cast");
		f.containerChanged(INVENTORY, 554, 8);
		List<LedgerEvent> settled = f.gameTick();

		assertEquals(0, SnapshotFixtures.unverifiedCount(settled));
		assertEquals(1, SnapshotFixtures.phantomCount(settled));
	}
}
