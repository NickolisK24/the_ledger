package com.nikko.theledger.capture;

import com.nikko.theledger.model.EventType;
import com.nikko.theledger.model.LedgerEvent;
import java.util.List;
import org.junit.Test;
import static com.nikko.theledger.capture.LedgerContainers.BANK;
import static com.nikko.theledger.capture.LedgerContainers.EQUIPMENT;
import static com.nikko.theledger.capture.LedgerContainers.INVENTORY;
import static com.nikko.theledger.capture.SnapshotFixtures.ABYSSAL_WHIP;
import static com.nikko.theledger.capture.SnapshotFixtures.COINS;
import static com.nikko.theledger.capture.SnapshotFixtures.DRAGON_BONES;
import static com.nikko.theledger.capture.SnapshotFixtures.RUNE_PLATEBODY;
import static com.nikko.theledger.capture.SnapshotFixtures.SHARK;
import static com.nikko.theledger.capture.SnapshotFixtures.phantomCount;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Containers repopulate on every state transition. Each of these would otherwise log the
 * account's entire inventory, equipment and bank as a gain — the single largest phantom in the
 * category.
 */
public class StateResetTest
{
	@Test
	public void logOutAndBackInWithFullContainers()
	{
		SnapshotFixtures f = fullyLoadedAccount();

		LedgerEvent reset = f.stateReset("LOGIN_SCREEN");
		assertEquals(EventType.STATE_RESET, reset.getType());
		assertEquals("LOGIN_SCREEN", reset.getActionContext());

		// Logging back in, the client repopulates every container from scratch.
		List<LedgerEvent> events = repopulate(f);

		assertEquals(SnapshotFixtures.describe(events), 0, events.size());
	}

	@Test
	public void hopWorldsMidSession()
	{
		SnapshotFixtures f = fullyLoadedAccount();
		f.stateReset("HOPPING");
		List<LedgerEvent> events = repopulate(f);
		assertEquals(SnapshotFixtures.describe(events), 0, phantomCount(events));
		assertEquals(SnapshotFixtures.describe(events), 0, events.size());
	}

	@Test
	public void connectionLossAndReconnect()
	{
		SnapshotFixtures f = fullyLoadedAccount();
		f.stateReset("CONNECTION_LOST");
		List<LedgerEvent> events = repopulate(f);
		assertEquals(SnapshotFixtures.describe(events), 0, events.size());
	}

	@Test
	public void regionLoadDoesNotLogTheInventoryAsAGain()
	{
		SnapshotFixtures f = fullyLoadedAccount();
		f.stateReset("LOADING");
		List<LedgerEvent> events = repopulate(f);
		assertEquals(SnapshotFixtures.describe(events), 0, events.size());
	}

	/**
	 * A reset invalidates the bank too, so a transfer inferred afterwards is flagged as
	 * unconfirmed until the bank interface is opened again.
	 */
	@Test
	public void resetInvalidatesTheBankBaseline()
	{
		SnapshotFixtures f = fullyLoadedAccount();
		assertTrue(f.bankSeen());

		f.stateReset("HOPPING");

		assertFalse(f.bankSeen());
		assertFalse(f.snapshotOf(BANK).isKnown());
		assertFalse(f.snapshotOf(INVENTORY).isKnown());
		assertFalse(f.snapshotOf(EQUIPMENT).isKnown());
	}

	/**
	 * After the reset has been absorbed, real movements are picked up again. A reset must not
	 * mute the spine permanently.
	 */
	@Test
	public void spineResumesReportingAfterAReset()
	{
		SnapshotFixtures f = fullyLoadedAccount();
		f.stateReset("LOADING");
		repopulate(f);

		f.worldClick("Eat");
		f.containerChanged(INVENTORY, COINS, 3_000_000, SHARK, 19, DRAGON_BONES, 40);
		List<LedgerEvent> events = f.gameTick();

		assertEquals(SnapshotFixtures.describe(events), 1, events.size());
		assertEquals(Integer.valueOf(-1), events.get(0).getQty());
	}

	/**
	 * A repopulation that arrives spread over several ticks is still only a re-seed.
	 */
	@Test
	public void staggeredRepopulationAcrossTicksProducesNothing()
	{
		SnapshotFixtures f = fullyLoadedAccount();
		f.stateReset("LOGGING_IN");

		f.containerChanged(INVENTORY, COINS, 3_000_000, SHARK, 20, DRAGON_BONES, 40);
		List<LedgerEvent> first = f.gameTick();
		f.containerChanged(EQUIPMENT, RUNE_PLATEBODY, 1, ABYSSAL_WHIP, 1);
		List<LedgerEvent> second = f.gameTick();
		f.containerChanged(BANK, COINS, 80_000_000, SHARK, 5000);
		List<LedgerEvent> third = f.gameTick();

		assertEquals(SnapshotFixtures.describe(first), 0, first.size());
		assertEquals(SnapshotFixtures.describe(second), 0, second.size());
		assertEquals(SnapshotFixtures.describe(third), 0, third.size());
	}

	private static SnapshotFixtures fullyLoadedAccount()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.seed(INVENTORY, COINS, 3_000_000, SHARK, 20, DRAGON_BONES, 40);
		f.seed(EQUIPMENT, RUNE_PLATEBODY, 1, ABYSSAL_WHIP, 1);
		f.seed(BANK, COINS, 80_000_000, SHARK, 5000);
		return f;
	}

	private static List<LedgerEvent> repopulate(SnapshotFixtures f)
	{
		f.containerChanged(INVENTORY, COINS, 3_000_000, SHARK, 20, DRAGON_BONES, 40);
		f.containerChanged(EQUIPMENT, RUNE_PLATEBODY, 1, ABYSSAL_WHIP, 1);
		f.containerChanged(BANK, COINS, 80_000_000, SHARK, 5000);
		return f.gameTick();
	}
}
