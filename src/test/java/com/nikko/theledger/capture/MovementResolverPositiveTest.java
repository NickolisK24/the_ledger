package com.nikko.theledger.capture;

import com.nikko.theledger.model.EventType;
import com.nikko.theledger.model.LedgerEvent;
import com.nikko.theledger.model.MovementCategory;
import java.util.List;
import org.junit.Test;
import static com.nikko.theledger.capture.LedgerContainers.INVENTORY;
import static com.nikko.theledger.capture.SnapshotFixtures.ABYSSAL_WHIP;
import static com.nikko.theledger.capture.SnapshotFixtures.COINS;
import static com.nikko.theledger.capture.SnapshotFixtures.RUNE_ARROW;
import static com.nikko.theledger.capture.SnapshotFixtures.SHARK;
import static com.nikko.theledger.capture.SnapshotFixtures.SUPER_RESTORE_3;
import static com.nikko.theledger.capture.SnapshotFixtures.SUPER_RESTORE_4;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Things the spine must notice. The negative fixtures are the other half of this file's job;
 * a spine that logs nothing would pass those and fail these.
 */
public class MovementResolverPositiveTest
{
	@Test
	public void killDropAppearingInInventoryIsOneUnclassifiedGain()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.loggedIn(COINS, 1000);

		f.worldClick("Attack");
		f.containerChanged(INVENTORY, COINS, 1000, ABYSSAL_WHIP, 1);
		List<LedgerEvent> events = f.gameTick();

		assertEquals(SnapshotFixtures.describe(events), 1, events.size());
		LedgerEvent gain = events.get(0);
		assertEquals(EventType.ITEM_MOVEMENT, gain.getType());
		assertEquals(MovementCategory.UNCLASSIFIED_GAIN, gain.getCategory());
		assertEquals(Integer.valueOf(ABYSSAL_WHIP), gain.getItemId());
		assertEquals(Integer.valueOf(1), gain.getQty());
		assertEquals(Integer.valueOf(INVENTORY), gain.getContainerId());
		assertTrue(gain.getFlags().isEmpty());
	}

	/**
	 * A consumable that leaves nothing behind — food, a rune, an arrow that did not drop.
	 */
	@Test
	public void consumableUsedUpIsOneUnclassifiedLoss()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.loggedIn(SHARK, 4, RUNE_ARROW, 500);

		f.worldClick("Eat");
		f.containerChanged(INVENTORY, SHARK, 3, RUNE_ARROW, 500);
		List<LedgerEvent> events = f.gameTick();

		assertEquals(SnapshotFixtures.describe(events), 1, events.size());
		LedgerEvent loss = events.get(0);
		assertEquals(MovementCategory.UNCLASSIFIED_LOSS, loss.getCategory());
		assertEquals(Integer.valueOf(SHARK), loss.getItemId());
		assertEquals(Integer.valueOf(-1), loss.getQty());
	}

	/**
	 * A dose consumed replaces the item with a lower-dose item, so the spine sees one id leave
	 * and a different id arrive. Phase 1 reports both movements literally and attributes
	 * neither; recognising the dose ladder is what MovementCategory.CONSUMABLE is reserved for.
	 * Locked in here so Phase 2 changing it is a deliberate act.
	 */
	@Test
	public void potionDoseConsumedIsALossOfTheFourDoseAndAGainOfTheThreeDose()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.loggedIn(SUPER_RESTORE_4, 1);

		f.worldClick("Drink");
		f.containerChanged(INVENTORY, SUPER_RESTORE_3, 1);
		List<LedgerEvent> events = f.gameTick();

		assertEquals(SnapshotFixtures.describe(events), 2, events.size());
		List<LedgerEvent> losses = SnapshotFixtures.withCategory(events, MovementCategory.UNCLASSIFIED_LOSS);
		List<LedgerEvent> gains = SnapshotFixtures.withCategory(events, MovementCategory.UNCLASSIFIED_GAIN);
		assertEquals(1, losses.size());
		assertEquals(1, gains.size());
		assertEquals(Integer.valueOf(SUPER_RESTORE_4), losses.get(0).getItemId());
		assertEquals(Integer.valueOf(SUPER_RESTORE_3), gains.get(0).getItemId());
	}

	@Test
	public void xpGainCarriesSkillAndDelta()
	{
		SnapshotFixtures f = new SnapshotFixtures();

		// First reading only seeds: getXp() is a lifetime total, not a change.
		assertNull(f.xp("SLAYER", 4_200_000));

		f.advanceTick();
		LedgerEvent event = f.xp("SLAYER", 4_200_412);

		assertNotNull(event);
		assertEquals(EventType.XP_GAIN, event.getType());
		assertEquals("SLAYER", event.getSkill());
		assertEquals(Integer.valueOf(412), event.getXpDelta());
		assertNull(event.getCategory());
		assertNull(event.getItemId());
	}

	@Test
	public void firstXpReadingNeverLogsTheLifetimeTotal()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		assertNull(f.xp("ATTACK", 13_034_431));
		assertNull(f.xp("MAGIC", 200_000_000));
		assertTrue(f.allEvents().isEmpty());
	}

	@Test
	public void repeatedIdenticalXpReadingProducesNothing()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.xp("MINING", 1000);
		assertNull(f.xp("MINING", 1000));
	}

	@Test
	public void xpBaselinesAreDroppedByStateResetSoALoginDoesNotLogTheTotal()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.xp("FISHING", 500_000);
		f.xp("FISHING", 500_100);

		f.stateReset("LOGIN_SCREEN");

		// Logging back in, the first reading is a total again and must seed rather than log.
		assertNull(f.xp("FISHING", 500_100));
	}

	@Test
	public void twoRealMovementsInOneTickAreBothReported()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.loggedIn(COINS, 1000, SHARK, 2);

		f.worldClick("Attack");
		f.containerChanged(INVENTORY, COINS, 1500, SHARK, 1);
		List<LedgerEvent> events = f.gameTick();

		assertEquals(SnapshotFixtures.describe(events), 2, events.size());
		assertEquals(1, SnapshotFixtures.withCategory(events, MovementCategory.UNCLASSIFIED_GAIN).size());
		assertEquals(1, SnapshotFixtures.withCategory(events, MovementCategory.UNCLASSIFIED_LOSS).size());
	}
}
