package com.nikko.theledger.capture;

import com.nikko.theledger.model.LedgerEvent;
import com.nikko.theledger.model.MovementCategory;
import java.util.List;
import org.junit.Test;
import static com.nikko.theledger.capture.LedgerContainers.BANK;
import static com.nikko.theledger.capture.LedgerContainers.EQUIPMENT;
import static com.nikko.theledger.capture.LedgerContainers.IFACE_BANKMAIN;
import static com.nikko.theledger.capture.LedgerContainers.IFACE_BANKSIDE;
import static com.nikko.theledger.capture.LedgerContainers.IFACE_BANK_DEPOSITBOX;
import static com.nikko.theledger.capture.LedgerContainers.IFACE_GE_COLLECT;
import static com.nikko.theledger.capture.LedgerContainers.INVENTORY;
import static com.nikko.theledger.capture.SnapshotFixtures.ABYSSAL_WHIP;
import static com.nikko.theledger.capture.SnapshotFixtures.RUNE_ARROW;
import static com.nikko.theledger.capture.SnapshotFixtures.RUNE_PLATEBODY;
import static com.nikko.theledger.capture.SnapshotFixtures.SHARK;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Telling a deposit box from a bank, structurally.
 * <p>
 * The distinction is not cosmetic. At a bank both legs of the movement are observable — the
 * inventory loses and the bank gains — and they net into a plain {@code TRANSFER} with nothing
 * inferred. A deposit box takes the same items and the bank container never updates, so the
 * destination has to be inferred, and an uninferred deposit box logs a player's entire inventory
 * as destroyed.
 * <p>
 * That inference used to have a display-string fallback: a menu option beginning "Deposit",
 * anywhere that was not a bank, was treated as an invisible deposit. It was wrong in both
 * directions at once. A localised client renames the option and the inference is silently lost;
 * a "Deposit" on a surface that is not a bank at all is promoted to a confident bank transfer on
 * no evidence, which fabricates bank contents that were never there. It is gone. The interface
 * group is now the entire test.
 * <p>
 * The group ids are verified against upstream {@code net.runelite.api.gameval.InterfaceID}:
 * {@code BANKMAIN = 12}, {@code BANKSIDE = 15}, {@code BANK_DEPOSITBOX = 192}. A live session
 * independently showed {@code @192} on deposited soul runes, law runes and a medallion.
 */
public class DepositClassificationTest
{
	private static final int LAW = 563;

	/**
	 * A real group id that is not a bank, not a deposit box and not the Grand Exchange:
	 * {@code SEED_VAULT_DEPOSIT = 630}. Chosen deliberately over an invented number because it is
	 * a genuine surface whose menu option reads "Deposit" and whose destination the spine does not
	 * model — exactly the case the old fallback got wrong.
	 */
	private static final int IFACE_SEED_VAULT_DEPOSIT = 630;

	// ---- The verified deposit box ----

	@Test
	public void theDepositBoxIsAnInferredTransferAndSaysSo()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.loggedIn(SHARK, 20, LAW, 500);
		assertFalse("the bank was never opened, so it has no baseline", f.isSeeded(BANK));

		f.menuClick("Deposit inventory", IFACE_BANK_DEPOSITBOX);
		f.containerChanged(INVENTORY);
		List<LedgerEvent> events = f.gameTick();

		assertEquals(SnapshotFixtures.describe(events), 2,
			SnapshotFixtures.withCategory(events, MovementCategory.TRANSFER).size());
		assertEquals("nothing was destroyed", 0, SnapshotFixtures.phantomCount(events));
		for (LedgerEvent e : events)
		{
			assertTrue(e.hasFlag(LedgerEvent.FLAG_INFERRED_DEPOSIT_BOX));
			assertTrue("the bank side of this cannot be checked",
				e.hasFlag(LedgerEvent.FLAG_UNKNOWN_BANK_BASELINE));
		}
	}

	/**
	 * The same deposit box with the bank already seeded. The inference is still needed — the bank
	 * container does not update at a deposit box even when its baseline is known — but the
	 * baseline caveat is not.
	 */
	@Test
	public void aSeededBankDropsTheBaselineCaveatAndKeepsTheInference()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.loggedIn(SHARK, 20);
		f.seed(BANK, LAW, 1000);

		f.menuClick("Deposit inventory", IFACE_BANK_DEPOSITBOX);
		f.containerChanged(INVENTORY);
		List<LedgerEvent> events = f.gameTick();

		assertEquals(1, events.size());
		assertEquals(MovementCategory.TRANSFER, events.get(0).getCategory());
		assertTrue(events.get(0).hasFlag(LedgerEvent.FLAG_INFERRED_DEPOSIT_BOX));
		assertFalse(events.get(0).hasFlag(LedgerEvent.FLAG_UNKNOWN_BANK_BASELINE));
	}

	@Test
	public void depositBoxWornItemsIsInferredTheSameWay()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.loggedIn();
		f.containerChanged(EQUIPMENT, RUNE_PLATEBODY, 1, ABYSSAL_WHIP, 1);
		f.gameTick();

		f.menuClick("Deposit worn items", IFACE_BANK_DEPOSITBOX);
		f.containerChanged(EQUIPMENT);
		List<LedgerEvent> events = f.gameTick();

		assertEquals(2, SnapshotFixtures.withCategory(events, MovementCategory.TRANSFER).size());
		assertEquals(0, SnapshotFixtures.phantomCount(events));
		for (LedgerEvent e : events)
		{
			assertTrue(e.hasFlag(LedgerEvent.FLAG_INFERRED_DEPOSIT_BOX));
		}
	}

	/**
	 * The one that would have broken on a localised client. There is no menu text at all here and
	 * the classification is unchanged, because it never read any.
	 */
	@Test
	public void emptyMenuTextOnTheDepositBoxStillInfers()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.loggedIn(SHARK, 20);

		f.menuClick("", IFACE_BANK_DEPOSITBOX);
		f.containerChanged(INVENTORY);
		List<LedgerEvent> events = f.gameTick();

		assertEquals(1, SnapshotFixtures.withCategory(events, MovementCategory.TRANSFER).size());
		assertTrue(events.get(0).hasFlag(LedgerEvent.FLAG_INFERRED_DEPOSIT_BOX));
	}

	/**
	 * Six labels including an empty one, a wrong one and a non-English one. On the deposit box
	 * every single one infers; the group decides and the text is inert.
	 */
	@Test
	public void noLabelChangesTheDepositBoxOutcome()
	{
		String[] options = {"Deposit inventory", "deposit-all", "DEPOSIT", "", "Withdraw-All", "存款"};
		for (String option : options)
		{
			SnapshotFixtures f = new SnapshotFixtures();
			f.loggedIn(SHARK, 20);

			f.menuClick(option, IFACE_BANK_DEPOSITBOX);
			f.containerChanged(INVENTORY);
			List<LedgerEvent> events = f.gameTick();

			assertEquals("option '" + option + "' changed the outcome", 1, events.size());
			assertEquals(MovementCategory.TRANSFER, events.get(0).getCategory());
			assertTrue(events.get(0).hasFlag(LedgerEvent.FLAG_INFERRED_DEPOSIT_BOX));
		}
	}

	// ---- The string fallback is gone ----

	/**
	 * The defect the cleanup exists for. A real surface, a real "Deposit" option, and a
	 * destination the spine cannot see or model. The old fallback called this a bank transfer and
	 * invented bank contents to match. It is now what it actually is: items left the inventory
	 * and the ledger cannot say where they went.
	 */
	@Test
	public void anUnmodelledSurfaceLabelledDepositIsNotADepositBox()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.loggedIn(SHARK, 20);

		f.menuClick("Deposit-All", IFACE_SEED_VAULT_DEPOSIT);
		f.containerChanged(INVENTORY);
		List<LedgerEvent> events = f.gameTick();

		assertEquals(1, events.size());
		assertEquals("an honest unknown, not an invented transfer",
			MovementCategory.UNCLASSIFIED_LOSS, events.get(0).getCategory());
		assertFalse(events.get(0).hasFlag(LedgerEvent.FLAG_INFERRED_DEPOSIT_BOX));
	}

	/**
	 * And the same click with no interface under it at all — a world object, a game entity. This
	 * is the shape a motherlode hopper or a minigame deposit takes, and none of them put items in
	 * a bank.
	 */
	@Test
	public void aWorldClickLabelledDepositIsNotADepositBox()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.loggedIn(SHARK, 20);

		f.worldClick("Deposit");
		f.containerChanged(INVENTORY);
		List<LedgerEvent> events = f.gameTick();

		assertEquals(1, events.size());
		assertEquals(MovementCategory.UNCLASSIFIED_LOSS, events.get(0).getCategory());
		assertFalse(events.get(0).hasFlag(LedgerEvent.FLAG_INFERRED_DEPOSIT_BOX));
	}

	/**
	 * Casing and language were the whole weakness of the removed heuristic. None of these reach it
	 * any more, on any surface that is not the deposit box.
	 */
	@Test
	public void noLabelCanCreateADepositAnywhereElse()
	{
		String[] options = {"Deposit", "deposit", "DEPOSIT-ALL", "Deposit-1", "存款", "Innskudd"};
		for (String option : options)
		{
			SnapshotFixtures f = new SnapshotFixtures();
			f.loggedIn(SHARK, 20);

			f.menuClick(option, IFACE_SEED_VAULT_DEPOSIT);
			f.containerChanged(INVENTORY);
			List<LedgerEvent> events = f.gameTick();

			assertEquals("option '" + option + "' invented a deposit", 1, events.size());
			assertFalse("option '" + option + "' invented a deposit",
				events.get(0).hasFlag(LedgerEvent.FLAG_INFERRED_DEPOSIT_BOX));
		}
	}

	// ---- The ordinary bank needs no inference at all ----

	@Test
	public void theBankSurfacesAreStructurallyDistinctFromTheDepositBox()
	{
		ActionContext main = ActionContext.onInterface("Deposit-All", IFACE_BANKMAIN, 0);
		ActionContext side = ActionContext.onInterface("Deposit-All", IFACE_BANKSIDE, 0);
		ActionContext box = ActionContext.onInterface("Deposit-All", IFACE_BANK_DEPOSITBOX, 0);

		assertTrue(main.isBankInterface());
		assertTrue(side.isBankInterface());
		assertFalse(main.isDepositBoxInterface());
		assertFalse(side.isDepositBoxInterface());

		assertTrue(box.isDepositBoxInterface());
		assertFalse(box.isBankInterface());
	}

	@Test
	public void anOrdinaryBankDepositNetsWithoutInference()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.loggedIn(RUNE_ARROW, 200);
		f.seed(BANK, RUNE_ARROW, 1000);

		f.menuClick("Deposit-All", IFACE_BANKMAIN);
		f.containerChanged(INVENTORY);
		f.containerChanged(BANK, RUNE_ARROW, 1200);
		List<LedgerEvent> events = f.gameTick();

		assertEquals(2, SnapshotFixtures.withCategory(events, MovementCategory.TRANSFER).size());
		assertEquals(0, SnapshotFixtures.phantomCount(events));
		for (LedgerEvent e : events)
		{
			assertTrue("an observed pair needs no inference", e.getFlags().isEmpty());
		}
	}

	@Test
	public void anOrdinaryBankWithdrawalNetsWithoutInference()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.loggedIn();
		f.seed(BANK, LAW, 1000);

		f.menuClick("Withdraw-All", IFACE_BANKMAIN);
		f.containerChanged(BANK, LAW, 400);
		f.containerChanged(INVENTORY, LAW, 600);
		List<LedgerEvent> events = f.gameTick();

		assertEquals(2, SnapshotFixtures.withCategory(events, MovementCategory.TRANSFER).size());
		assertEquals(0, SnapshotFixtures.phantomCount(events));
		for (LedgerEvent e : events)
		{
			assertTrue(e.getFlags().isEmpty());
		}
	}

	/**
	 * The bank-side panel, which is the group an inventory item click carries while the bank is
	 * open. Both legs are observable here too.
	 */
	@Test
	public void aBankSideDepositNetsWithoutInference()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.loggedIn(SHARK, 20);
		f.seed(BANK, SHARK, 5);

		f.menuClick("Deposit-All", IFACE_BANKSIDE);
		f.containerChanged(INVENTORY);
		f.containerChanged(BANK, SHARK, 25);
		List<LedgerEvent> events = f.gameTick();

		assertEquals(2, SnapshotFixtures.withCategory(events, MovementCategory.TRANSFER).size());
		assertEquals(0, SnapshotFixtures.phantomCount(events));
		for (LedgerEvent e : events)
		{
			assertTrue(e.getFlags().isEmpty());
		}
	}

	/**
	 * "Deposit worn items" at a real bank: the equipment loses and the bank gains, both observed,
	 * so it nets like any other transfer and the deposit-box inference never fires.
	 */
	@Test
	public void depositWornItemsAtARealBankNetsWithoutInference()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.loggedIn();
		f.containerChanged(EQUIPMENT, RUNE_PLATEBODY, 1);
		f.gameTick();
		f.seed(BANK, LAW, 10);

		f.menuClick("Deposit worn items", IFACE_BANKMAIN);
		f.containerChanged(EQUIPMENT);
		f.containerChanged(BANK, LAW, 10, RUNE_PLATEBODY, 1);
		List<LedgerEvent> events = f.gameTick();

		assertEquals(2, SnapshotFixtures.withCategory(events, MovementCategory.TRANSFER).size());
		assertEquals(0, SnapshotFixtures.phantomCount(events));
		for (LedgerEvent e : events)
		{
			assertFalse(e.hasFlag(LedgerEvent.FLAG_INFERRED_DEPOSIT_BOX));
		}
	}

	// ---- Neighbouring rules are undisturbed ----

	@Test
	public void theGrandExchangeIsUnaffected()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.loggedIn();

		f.menuClick("Collect", IFACE_GE_COLLECT);
		f.containerChanged(INVENTORY, LAW, 5000);
		List<LedgerEvent> events = f.gameTick();

		assertEquals(1, events.size());
		assertEquals(MovementCategory.TRANSFER, events.get(0).getCategory());
		assertTrue(events.get(0).hasFlag(LedgerEvent.FLAG_INFERRED_GRAND_EXCHANGE));
		assertFalse(events.get(0).hasFlag(LedgerEvent.FLAG_INFERRED_DEPOSIT_BOX));
	}

	/**
	 * Death ownership runs before anything is classified, so a deposit-box context left over from
	 * a moment ago cannot reach the wipe and relabel it. The frozen baseline stays the authority.
	 */
	@Test
	public void deathOwnershipStillWinsOverADepositContext()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.seed(INVENTORY, LAW, 10, SHARK, 4);
		f.seed(EQUIPMENT, RUNE_PLATEBODY, 1);

		f.menuClick("Deposit inventory", IFACE_BANK_DEPOSITBOX);
		f.death();
		f.respawnLoad();
		f.containerChanged(INVENTORY);
		f.containerChanged(EQUIPMENT);
		List<LedgerEvent> events = f.gameTick();

		assertEquals(3, SnapshotFixtures.withCategory(events, MovementCategory.DEATH_LOSS).size());
		assertEquals("a death is not a deposit", 0,
			SnapshotFixtures.withCategory(events, MovementCategory.TRANSFER).size());
		assertEquals(0, SnapshotFixtures.phantomCount(events));
	}

	/**
	 * First-observation reconciliation happens before residual losses are classified, so an
	 * equipment container appearing for the first time still corroborates the inventory's loss and
	 * the deposit-box rule is never reached.
	 */
	@Test
	public void firstObservationReconciliationStillWins()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.seed(INVENTORY, RUNE_PLATEBODY, 1, ABYSSAL_WHIP, 1);
		assertFalse("equipment is unallocated, as it is for a naked account",
			f.isSeeded(EQUIPMENT));

		f.menuClick("Deposit inventory", IFACE_BANK_DEPOSITBOX);
		f.containerChanged(INVENTORY);
		f.containerChanged(EQUIPMENT, RUNE_PLATEBODY, 1, ABYSSAL_WHIP, 1);
		List<LedgerEvent> events = f.gameTick();

		assertEquals(4, SnapshotFixtures.withCategory(events, MovementCategory.TRANSFER).size());
		assertEquals(0, SnapshotFixtures.phantomCount(events));
		for (LedgerEvent e : events)
		{
			assertTrue(e.hasFlag(LedgerEvent.FLAG_FIRST_OBSERVATION_RECONCILED));
			assertFalse(e.hasFlag(LedgerEvent.FLAG_INFERRED_DEPOSIT_BOX));
		}
	}
}
