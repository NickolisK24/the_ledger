package com.nikko.theledger.capture;

import com.nikko.theledger.model.LedgerEvent;
import com.nikko.theledger.model.MovementCategory;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;
import static com.nikko.theledger.capture.LedgerContainers.BANK;
import static com.nikko.theledger.capture.LedgerContainers.EQUIPMENT;
import static com.nikko.theledger.capture.LedgerContainers.IFACE_BANKMAIN;
import static com.nikko.theledger.capture.LedgerContainers.INVENTORY;
import static com.nikko.theledger.capture.SnapshotFixtures.ABYSSAL_WHIP;
import static com.nikko.theledger.capture.SnapshotFixtures.COINS;
import static com.nikko.theledger.capture.SnapshotFixtures.RUNE_ARROW;
import static com.nikko.theledger.capture.SnapshotFixtures.SHARK;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The counterparty flags are load-bearing in a way that is easy to miss.
 * <p>
 * {@code phantomCount} excludes flagged events, so a bug that <b>adds a flag</b> now makes a
 * spurious event vanish from the metric instead of failing a fixture. Over-applying
 * COUNTERPARTY_UNSEEDED would turn every negative fixture green while the spine quietly invented
 * movements. That inverts the safety property the negative fixtures exist to provide.
 * <p>
 * So the flags get their own discipline: exhaustive over every container and every combination of
 * seeded baselines, asserting the exact flag for each. Nothing here goes through the resolver's
 * higher-level rules, because the point is to pin the decision itself.
 */
public class FlagDisciplineTest
{
	private static MovementResolver.BaselineStatus seeded(int... containerIds)
	{
		Set<Integer> set = new HashSet<>();
		for (int id : containerIds)
		{
			set.add(id);
		}
		return set::contains;
	}

	/**
	 * All three containers, all eight seeding combinations, one assertion each.
	 * <p>
	 * The table is the specification. UNSEEDED appears if and only if a counterpart is genuinely
	 * unknown; UNTRACKED appears only for the bank and only once everything observable has been
	 * observed; the inventory is never flagged when its one counterpart is seeded, which is what
	 * keeps monster drops intact.
	 */
	@Test
	public void everyContainerAndEverySeedingCombination()
	{
		String unseeded = LedgerEvent.FLAG_COUNTERPARTY_UNSEEDED;
		String untracked = LedgerEvent.FLAG_COUNTERPARTY_UNTRACKED;

		// containerId, seeded set, expected flag (null = unflagged, counts as a real phantom)
		assertFlag(null, INVENTORY, seeded(INVENTORY, EQUIPMENT, BANK));
		assertFlag(null, INVENTORY, seeded(INVENTORY, EQUIPMENT));
		assertFlag(null, INVENTORY, seeded(EQUIPMENT, BANK));
		assertFlag(null, INVENTORY, seeded(EQUIPMENT));
		assertFlag(unseeded, INVENTORY, seeded(INVENTORY, BANK));
		assertFlag(unseeded, INVENTORY, seeded(INVENTORY));
		assertFlag(unseeded, INVENTORY, seeded(BANK));
		assertFlag(unseeded, INVENTORY, seeded());

		assertFlag(null, EQUIPMENT, seeded(INVENTORY, EQUIPMENT, BANK));
		assertFlag(null, EQUIPMENT, seeded(INVENTORY, EQUIPMENT));
		assertFlag(null, EQUIPMENT, seeded(INVENTORY, BANK));
		assertFlag(null, EQUIPMENT, seeded(INVENTORY));
		assertFlag(unseeded, EQUIPMENT, seeded(EQUIPMENT, BANK));
		assertFlag(unseeded, EQUIPMENT, seeded(EQUIPMENT));
		assertFlag(unseeded, EQUIPMENT, seeded(BANK));
		assertFlag(unseeded, EQUIPMENT, seeded());

		assertFlag(untracked, BANK, seeded(INVENTORY, EQUIPMENT, BANK));
		assertFlag(untracked, BANK, seeded(INVENTORY, EQUIPMENT));
		assertFlag(unseeded, BANK, seeded(INVENTORY, BANK));
		assertFlag(unseeded, BANK, seeded(EQUIPMENT, BANK));
		assertFlag(unseeded, BANK, seeded(INVENTORY));
		assertFlag(unseeded, BANK, seeded(EQUIPMENT));
		assertFlag(unseeded, BANK, seeded(BANK));
		assertFlag(unseeded, BANK, seeded());
	}

	private static void assertFlag(String expected, int containerId,
								   MovementResolver.BaselineStatus baselines)
	{
		String actual = MovementResolver.unverifiableReason(containerId, baselines);
		assertEquals(LedgerContainers.name(containerId) + " with those baselines", expected, actual);

		// The invariant behind the whole model: UNSEEDED is only ever legitimate while a
		// counterpart is genuinely unknown.
		if (LedgerEvent.FLAG_COUNTERPARTY_UNSEEDED.equals(actual))
		{
			assertFalse("UNSEEDED claimed while every counterpart was seeded",
				MovementResolver.allCounterpartsSeeded(containerId, baselines));
		}
		if (actual == null)
		{
			assertTrue("unflagged while a counterpart was unseeded",
				MovementResolver.allCounterpartsSeeded(containerId, baselines));
		}
	}

	// ---- The three fixtures you asked for, through the real pipeline ----

	/**
	 * Everything observable was observed and the movement still did not balance. That is a real
	 * unexplained gain and it must count as one.
	 */
	@Test
	public void bothContainersSeededAndStillOneLeggedIsARealPhantom()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.loggedIn(COINS, 1000);

		f.worldClick("Attack");
		f.containerChanged(INVENTORY, COINS, 1000, ABYSSAL_WHIP, 1);
		List<LedgerEvent> events = f.gameTick();

		assertEquals(1, events.size());
		LedgerEvent gain = events.get(0);
		assertTrue("a real phantom must not be flagged away", gain.getFlags().isEmpty());
		assertEquals(MovementCategory.UNCLASSIFIED_GAIN, gain.getCategory());
		assertEquals("it must still count", 1, SnapshotFixtures.phantomCount(events));
		assertEquals(0, SnapshotFixtures.unverifiedCount(events));
	}

	@Test
	public void bothCarriedContainersSeededAndAOneLeggedLossStillCounts()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.loggedIn(SHARK, 10);
		f.seed(BANK, COINS, 1000);

		f.worldClick("Drop");
		f.containerChanged(INVENTORY, SHARK, 9);
		List<LedgerEvent> events = f.gameTick();

		assertEquals(1, SnapshotFixtures.phantomCount(events));
		assertTrue(events.get(0).getFlags().isEmpty());
	}

	/**
	 * The flag is applied only while the counterpart is actually UNKNOWN.
	 */
	@Test
	public void flagAppliesOnlyWhileTheCounterpartIsUnknown()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.seed(INVENTORY, ABYSSAL_WHIP, 1);
		assertFalse(f.isSeeded(EQUIPMENT));

		f.worldClick("Wield");
		f.containerChanged(INVENTORY);
		List<LedgerEvent> flagged = f.gameTick();

		assertEquals(1, SnapshotFixtures.unverifiedCount(flagged));
		assertEquals(0, SnapshotFixtures.phantomCount(flagged));
		assertTrue(flagged.get(0).hasFlag(LedgerEvent.FLAG_COUNTERPARTY_UNSEEDED));
	}

	/**
	 * And the moment that container is seeded, subsequent movements stop being flagged — so a
	 * transient unknown cannot become a permanent blind spot.
	 */
	@Test
	public void onceTheCounterpartIsSeededMovementsStopBeingFlagged()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.seed(INVENTORY, ABYSSAL_WHIP, 1, SHARK, 5);

		f.worldClick("Wield");
		f.containerChanged(INVENTORY, SHARK, 5);
		assertEquals(1, SnapshotFixtures.unverifiedCount(f.gameTick()));

		// Eager seeding reaches the equipment container.
		f.eagerSeed(EQUIPMENT, ABYSSAL_WHIP, 1);
		assertTrue(f.isSeeded(EQUIPMENT));

		f.worldClick("Eat");
		f.containerChanged(INVENTORY, SHARK, 4);
		List<LedgerEvent> after = f.gameTick();

		assertEquals("the flag must not outlive the unknown", 0,
			SnapshotFixtures.unverifiedCount(after));
		assertEquals(1, SnapshotFixtures.phantomCount(after));
	}

	// ---- The bank rule is structural, not seeding-based, and must stay that way ----

	/**
	 * A bank movement with everything seeded is UNTRACKED, never UNSEEDED. The two flags answer
	 * different questions and must not be confused: one says a tracked container was invisible,
	 * the other says the counterpart was never a tracked container at all.
	 */
	@Test
	public void bankWithEverythingSeededIsUntrackedNotUnseeded()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.loggedIn();
		f.seed(BANK, COINS, 1000);

		f.menuClick("Deposit runes", LedgerContainers.IFACE_BANKSIDE);
		f.containerChanged(BANK, COINS, 1000, 554, 14875);
		List<LedgerEvent> events = f.gameTick();

		assertEquals(1, events.size());
		assertTrue(events.get(0).hasFlag(LedgerEvent.FLAG_COUNTERPARTY_UNTRACKED));
		assertFalse(events.get(0).hasFlag(LedgerEvent.FLAG_COUNTERPARTY_UNSEEDED));
	}

	/**
	 * A bank movement that DOES have its counterpart leg nets to a transfer and carries neither
	 * flag. The flags only ever reach a residual, so a balanced movement can never be flagged.
	 */
	@Test
	public void aBalancedBankMovementIsNeverFlagged()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.loggedIn(RUNE_ARROW, 200);
		f.seed(BANK, RUNE_ARROW, 1000);

		f.menuClick("Deposit-All", IFACE_BANKMAIN);
		f.containerChanged(INVENTORY);
		f.containerChanged(BANK, RUNE_ARROW, 1200);
		List<LedgerEvent> events = f.gameTick();

		assertEquals(2, SnapshotFixtures.withCategory(events, MovementCategory.TRANSFER).size());
		assertEquals(0, SnapshotFixtures.unverifiedCount(events));
		for (LedgerEvent e : events)
		{
			assertTrue(e.getFlags().isEmpty());
		}
	}

	/**
	 * The rule reads container identity and nothing else. Changing the menu option, the target
	 * text and the widget group cannot change the outcome, which is what makes it immune to a
	 * game revision renaming a label or a localised client translating it.
	 */
	@Test
	public void theDecisionIgnoresEveryMenuString()
	{
		String[] options = {"Deposit runes", "Withdraw-All", "", "Bank", "Ranger nyckel", "存款"};
		for (String option : options)
		{
			SnapshotFixtures f = new SnapshotFixtures();
			f.loggedIn();
			f.seed(BANK, COINS, 1000);
			f.menuClick(option, LedgerContainers.IFACE_BANKSIDE);
			f.containerChanged(BANK, COINS, 2000);
			List<LedgerEvent> events = f.gameTick();

			assertEquals("option '" + option + "' changed the outcome", 1,
				SnapshotFixtures.unverifiedCount(events));
			assertTrue(events.get(0).hasFlag(LedgerEvent.FLAG_COUNTERPARTY_UNTRACKED));
		}
	}

	/**
	 * And the mirror: a monster drop is never flagged no matter what the menu said.
	 */
	@Test
	public void aDropIsNeverFlaggedWhateverTheMenuSaid()
	{
		String[] options = {"Attack", "Deposit runes", "Withdraw-All", ""};
		for (String option : options)
		{
			SnapshotFixtures f = new SnapshotFixtures();
			f.loggedIn(COINS, 1000);
			f.menuClick(option, LedgerContainers.IFACE_BANKSIDE);
			f.containerChanged(INVENTORY, COINS, 1000, ABYSSAL_WHIP, 1);
			List<LedgerEvent> events = f.gameTick();

			assertEquals("option '" + option + "' suppressed a drop", 1,
				SnapshotFixtures.phantomCount(events));
			assertEquals(0, SnapshotFixtures.unverifiedCount(events));
		}
	}

	@Test
	public void unflaggedIsTheDefaultForAnyUnknownContainer()
	{
		// A container the spine does not model at all has no counterparts and no transfer-only
		// property, so it can never be flagged.
		assertNull(MovementResolver.unverifiableReason(9999, seeded()));
	}
}
