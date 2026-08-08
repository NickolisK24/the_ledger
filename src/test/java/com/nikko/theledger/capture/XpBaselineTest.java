package com.nikko.theledger.capture;

import com.nikko.theledger.model.EventType;
import com.nikko.theledger.model.LedgerEvent;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * Experience baselines and what may and may not invalidate them.
 * <p>
 * {@code StatChanged.getXp()} is a lifetime cumulative total. That total does not change when a
 * region loads, and it does not change when the same account logs back in — so clearing the
 * baselines on every STATE_RESET threw away the next gain in every skill, 27 times in one
 * observed session, for no correctness benefit at all. Only a change of account can genuinely
 * invalidate them.
 */
public class XpBaselineTest
{
	@Test
	public void teleportDoesNotLoseXpAttribution()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		assertNull(f.xp("SLAYER", 4_200_000));

		f.advanceTick();
		f.stateReset("LOADING");

		f.advanceTick();
		LedgerEvent gain = f.xp("SLAYER", 4_200_412);

		assertNotNull("a region load must not swallow the next gain", gain);
		assertEquals(EventType.XP_GAIN, gain.getType());
		assertEquals(Integer.valueOf(412), gain.getXpDelta());
	}

	@Test
	public void regionLoadThatKeepsBaselinesAlsoKeepsXpAttribution()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.xp("MINING", 1_000_000);

		f.advanceTick();
		f.regionLoadKeepingBaselines();

		f.advanceTick();
		LedgerEvent gain = f.xp("MINING", 1_000_065);

		assertNotNull(gain);
		assertEquals(Integer.valueOf(65), gain.getXpDelta());
	}

	@Test
	public void relogToTheSameAccountDoesNotLoseXpAttribution()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.xp("FISHING", 500_000);

		// Log out and back in. The session does not rotate, because the account did not change.
		f.stateReset("LOGIN_SCREEN");
		f.advanceTick();
		f.stateReset("LOGGING_IN");
		f.advanceTick();
		f.stateReset("LOADING");
		f.advanceTick();

		LedgerEvent gain = f.xp("FISHING", 500_800);

		assertNotNull("a lifetime total is still valid after a relog", gain);
		assertEquals(Integer.valueOf(800), gain.getXpDelta());
	}

	@Test
	public void hopWorldsDoesNotLoseXpAttribution()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.xp("AGILITY", 250_000);
		f.stateReset("HOPPING");
		f.advanceTick();

		assertEquals(Integer.valueOf(45), f.xp("AGILITY", 250_045).getXpDelta());
	}

	/**
	 * The one event that does invalidate a baseline. A different account has a different lifetime
	 * total, and subtracting one from the other would log millions of experience as a single gain.
	 */
	@Test
	public void accountSwitchResetsXpBaselines()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.xp("SLAYER", 4_200_000);
		f.advanceTick();

		f.rotateAccount();

		assertNull("the first reading on a new account must seed, not log",
			f.xp("SLAYER", 12_000));
		f.advanceTick();
		assertEquals(Integer.valueOf(30), f.xp("SLAYER", 12_030).getXpDelta());
	}

	// ---- Experience cannot go down ----

	/**
	 * A negative delta is proof of a stale or wrong baseline, never a real event. It reseeds
	 * silently and is counted, so a mistake is visible instead of being logged as fact.
	 */
	@Test
	public void negativeDeltaReseedsAndEmitsNothing()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.xp("MAGIC", 5_000_000);
		f.advanceTick();

		assertNull(f.xp("MAGIC", 4_000_000));
		assertEquals(1, f.resolver().getNegativeXpReseeds());
		assertEquals(0, SnapshotFixtures.ofType(f.allEvents(), EventType.XP_GAIN).size());
	}

	@Test
	public void theReseededBaselineIsTheOneUsedNext()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.xp("MAGIC", 5_000_000);
		f.advanceTick();
		f.xp("MAGIC", 4_000_000);       // repaired to 4,000,000
		f.advanceTick();

		LedgerEvent gain = f.xp("MAGIC", 4_000_010);

		assertNotNull(gain);
		assertEquals("delta must be measured from the repaired baseline, not the stale one",
			Integer.valueOf(10), gain.getXpDelta());
	}

	@Test
	public void aCleanSessionNeverReseedsOnANegative()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.xp("COOKING", 100);
		for (int i = 1; i <= 20; i++)
		{
			f.advanceTick();
			f.xp("COOKING", 100 + i * 5);
		}
		assertEquals(0, f.resolver().getNegativeXpReseeds());
		assertEquals(20, SnapshotFixtures.ofType(f.allEvents(), EventType.XP_GAIN).size());
	}

	@Test
	public void skillsAreTrackedIndependently()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.xp("ATTACK", 1000);
		f.xp("STRENGTH", 2000);
		f.advanceTick();

		assertEquals(Integer.valueOf(10), f.xp("ATTACK", 1010).getXpDelta());
		assertNull(f.xp("STRENGTH", 2000));
		assertEquals(Integer.valueOf(7), f.xp("STRENGTH", 2007).getXpDelta());
	}

	@Test
	public void firstReadingStillNeverLogsTheLifetimeTotal()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		assertNull(f.xp("MAGIC", 200_000_000));
		assertEquals(0, SnapshotFixtures.ofType(f.allEvents(), EventType.XP_GAIN).size());
	}
}
