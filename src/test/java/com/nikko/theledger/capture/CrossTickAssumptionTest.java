package com.nikko.theledger.capture;

import com.nikko.theledger.model.LedgerEvent;
import com.nikko.theledger.model.MovementCategory;
import java.util.List;
import org.junit.Test;
import static com.nikko.theledger.capture.LedgerContainers.BANK;
import static com.nikko.theledger.capture.LedgerContainers.IFACE_BANKMAIN;
import static com.nikko.theledger.capture.LedgerContainers.INVENTORY;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Transfer netting is per tick, and this is where that assumption is written down.
 * <p>
 * A live session contained what looked like a mismatched withdrawal: a movement of item 13393
 * (Xeric's talisman) and a movement of item 22400 (Drakan's medallion), both carrying an action
 * context reading like {@code Withdraw-1:Drakan's medallion}. The two items are unrelated, there
 * is no canonicalisation between them and there must never be one, so the shared label raised the
 * question of whether the resolver had paired them — and behind that, whether the two legs of one
 * real transfer can land on different ticks.
 * <p>
 * The first half of that question is answered here rather than by a session file, because it is a
 * property of the code: <b>legs are paired by canonical item identity and by nothing else.</b>
 * {@code resolveTick} groups deltas into {@code byItem} on the canonical id and nets gains against
 * losses only inside one group. The action context is passed to {@code classifyLoss} and
 * {@code classifyGain} and written onto the event as a label; no branch anywhere pairs on it. Two
 * different identities therefore cannot be netted together whatever their context says, and the
 * shared label is exactly what it looks like — a context that outlived its click by a tick or two,
 * as it is designed to, stamped onto the next movement to come along.
 * <p>
 * The second half — whether one transfer's legs can straddle two ticks — is <b>not</b> answered
 * here, because no evidence exists either way and inventing a cross-tick matching window on
 * suspicion would trade a hypothetical missing pair for a real class of wrongly-netted movements.
 * What this class does instead is state the current contract explicitly, so that a future change
 * to it has to be deliberate, and pin the production signature a genuine straddle would leave.
 */
public class CrossTickAssumptionTest
{
	/**
	 * Xeric's talisman.
	 */
	private static final int TALISMAN = 13393;
	/**
	 * Drakan's medallion. Unrelated to the above in every way.
	 */
	private static final int MEDALLION = 22400;

	// ---- Identity is the only pairing key ----

	/**
	 * The anomaly's exact shape: two unrelated items moving in opposite directions in one tick,
	 * both labelled by the same click. They must stay two independent movements. Netting them
	 * would silently convert a real loss and a real gain into a transfer that never happened.
	 */
	@Test
	public void twoDifferentIdentitiesAreNeverNettedEvenUnderOneContext()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.loggedIn();
		f.seed(BANK, TALISMAN, 1, MEDALLION, 1);

		f.menuClick("Withdraw-1", IFACE_BANKMAIN);
		f.containerChanged(BANK, MEDALLION, 1);          // the talisman left the bank
		f.containerChanged(INVENTORY, MEDALLION, 1);     // a medallion arrived in the inventory
		List<LedgerEvent> events = f.gameTick();

		assertEquals(SnapshotFixtures.describe(events), 0,
			SnapshotFixtures.withCategory(events, MovementCategory.TRANSFER).size());
		assertEquals(1, SnapshotFixtures.withCategory(events, MovementCategory.UNCLASSIFIED_LOSS).size());
		assertEquals(1, SnapshotFixtures.withCategory(events, MovementCategory.UNCLASSIFIED_GAIN).size());
		for (LedgerEvent e : events)
		{
			assertTrue("only one identity per event",
				e.getItemId() == TALISMAN || e.getItemId() == MEDALLION);
		}
	}

	/**
	 * And the control: the same two containers, the same tick, the same click — but one identity.
	 * That nets, and it is the only thing that does.
	 */
	@Test
	public void oneIdentityAcrossTwoContainersInOneTickNetsExactlyOnce()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.loggedIn();
		f.seed(BANK, MEDALLION, 1);

		f.menuClick("Withdraw-1", IFACE_BANKMAIN);
		f.containerChanged(BANK);
		f.containerChanged(INVENTORY, MEDALLION, 1);
		List<LedgerEvent> events = f.gameTick();

		assertEquals(2, SnapshotFixtures.withCategory(events, MovementCategory.TRANSFER).size());
		assertEquals(0, SnapshotFixtures.phantomCount(events));
	}

	/**
	 * A context deliberately outlives its click, because the client applies a deposit or a Grand
	 * Exchange confirmation a tick or two afterwards. So an unrelated movement can be labelled by
	 * a click that had nothing to do with it. The label is written to the event for the audit
	 * trail and changes no classification: this movement is an unexplained gain with or without it.
	 */
	@Test
	public void aStaleContextLabelsAMovementWithoutExplainingIt()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.loggedIn();

		f.menuClick("Withdraw-1", IFACE_BANKMAIN);
		f.gameTick();                                    // the click's own tick, nothing moved
		f.containerChanged(INVENTORY, TALISMAN, 1);      // something unrelated arrives
		List<LedgerEvent> events = f.gameTick();

		assertEquals(1, events.size());
		LedgerEvent e = events.get(0);
		assertEquals(MovementCategory.UNCLASSIFIED_GAIN, e.getCategory());
		assertEquals(Integer.valueOf(TALISMAN), e.getItemId());
		assertNotNull("the stale label is still recorded, for the audit trail", e.getActionContext());
		assertTrue(e.getActionContext().contains("Withdraw-1"));
	}

	/**
	 * The context expires. Past {@code actionContextTicks} it stops being attached at all, so it
	 * cannot accumulate into a label that outlives any relationship to what happened.
	 */
	@Test
	public void anExpiredContextIsNotEvenALabel()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.loggedIn();

		f.menuClick("Withdraw-1", IFACE_BANKMAIN);
		for (int i = 0; i <= MovementResolver.DEFAULT_ACTION_CONTEXT_TICKS; i++)
		{
			f.gameTick();
		}
		f.containerChanged(INVENTORY, TALISMAN, 1);
		List<LedgerEvent> events = f.gameTick();

		assertEquals(1, events.size());
		assertEquals(null, events.get(0).getActionContext());
	}

	// ---- The per-tick contract, stated ----

	/**
	 * What the spine does today when one identity's two legs land on adjacent ticks: it does not
	 * pair them. Each tick is resolved on its own evidence and neither can see the other.
	 * <p>
	 * This test asserts the current behaviour deliberately. It is not a claim that straddling
	 * happens — no session has ever demonstrated it, and every ordinary bank, equipment and
	 * deposit operation observed so far has resolved both legs inside one tick. It exists so that
	 * the assumption is written down and pinned: if evidence ever justifies a cross-tick
	 * reconciliation window, this test is what has to be consciously rewritten, rather than the
	 * behaviour quietly changing underneath a suite that never mentioned it.
	 * <p>
	 * It also records the exact production signature to look for, which is the same thing this
	 * asserts: a bank loss flagged {@code COUNTERPARTY_UNTRACKED} on tick N, and an
	 * <b>unflagged</b> inventory gain of the identical canonical id and quantity on tick N+1.
	 * That pair is fully visible in the replayed log with no extra instrumentation.
	 */
	@Test
	public void legsOnAdjacentTicksAreNotPairedAndLeaveADetectableSignature()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.loggedIn();
		f.seed(BANK, MEDALLION, 1);

		f.menuClick("Withdraw-1", IFACE_BANKMAIN);
		f.containerChanged(BANK);
		List<LedgerEvent> first = f.gameTick();

		f.containerChanged(INVENTORY, MEDALLION, 1);
		List<LedgerEvent> second = f.gameTick();

		assertEquals(1, first.size());
		LedgerEvent loss = first.get(0);
		assertEquals(MovementCategory.UNCLASSIFIED_LOSS, loss.getCategory());
		assertEquals(Integer.valueOf(BANK), loss.getContainerId());
		assertTrue(loss.hasFlag(LedgerEvent.FLAG_COUNTERPARTY_UNTRACKED));

		assertEquals(1, second.size());
		LedgerEvent gain = second.get(0);
		assertEquals(MovementCategory.UNCLASSIFIED_GAIN, gain.getCategory());
		assertEquals(Integer.valueOf(INVENTORY), gain.getContainerId());
		assertTrue("an unflagged gain is the spine asserting wealth appeared",
			gain.getFlags().isEmpty());

		assertEquals("same identity", loss.getItemId(), gain.getItemId());
		assertEquals("equal and opposite", -loss.getQty(), (int) gain.getQty());
		assertEquals("on adjacent ticks", 1, gain.getTick() - loss.getTick());
	}

	/**
	 * The reason a cross-tick window is not free. A drop and a consumption of the same item on
	 * adjacent ticks look identical to a straddled transfer from the outside, and any window wide
	 * enough to catch the second would swallow the first. Both of these are real economic events
	 * and both must survive.
	 */
	@Test
	public void anOrdinaryGainAndLossOnAdjacentTicksAreBothRealEvents()
	{
		SnapshotFixtures f = new SnapshotFixtures();
		f.loggedIn();

		f.worldClick("Take");
		f.containerChanged(INVENTORY, MEDALLION, 1);
		List<LedgerEvent> picked = f.gameTick();

		f.worldClick("Drop");
		f.containerChanged(INVENTORY);
		List<LedgerEvent> dropped = f.gameTick();

		assertEquals(1, picked.size());
		assertEquals(MovementCategory.UNCLASSIFIED_GAIN, picked.get(0).getCategory());
		assertEquals(1, dropped.size());
		assertEquals(MovementCategory.UNCLASSIFIED_LOSS, dropped.get(0).getCategory());
		assertFalse("neither may be netted away by the other",
			picked.get(0).getCategory() == MovementCategory.TRANSFER);
	}
}
