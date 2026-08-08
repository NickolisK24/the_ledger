package com.nikko.theledger;

import com.nikko.theledger.capture.ContainerDiffer;
import com.nikko.theledger.capture.ContainerSnapshot;
import com.nikko.theledger.capture.LedgerContainers;
import java.util.List;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The worn-id compatibility table.
 * <p>
 * These items carry a different id when worn than when they sit in the inventory. Without the
 * mapping, taking a piece of graceful off logs as one item destroyed in the equipment and a
 * different item created in the inventory — two real-looking events for a movement that did not
 * change anything, and one transfer netting cannot match because the ids differ.
 * <p>
 * The table mirrors {@code ItemManager.WORN_ITEMS}. It must be reviewed whenever RuneLite changes
 * that table upstream, and {@link #tableIsTheExpectedSize} is the tripwire for a bad merge
 * silently truncating it.
 */
public class WornItemIdsTest
{
	/**
	 * Ordinary graceful, which is the common case by a wide margin.
	 */
	@Test
	public void ordinaryGracefulCollapsesToItsInventoryIdentity()
	{
		assertEquals(ItemID.GRACEFUL_HOOD, WornItemIds.canonical(ItemID.GRACEFUL_HOOD_WORN));
		assertEquals(ItemID.GRACEFUL_CAPE, WornItemIds.canonical(ItemID.GRACEFUL_CAPE_WORN));
		assertEquals(ItemID.GRACEFUL_TOP, WornItemIds.canonical(ItemID.GRACEFUL_TOP_WORN));
		assertEquals(ItemID.GRACEFUL_LEGS, WornItemIds.canonical(ItemID.GRACEFUL_LEGS_WORN));
		assertEquals(ItemID.GRACEFUL_GLOVES, WornItemIds.canonical(ItemID.GRACEFUL_GLOVES_WORN));
		assertEquals(ItemID.GRACEFUL_BOOTS, WornItemIds.canonical(ItemID.GRACEFUL_BOOTS_WORN));
	}

	/**
	 * A recolour. Every house has its own pair of ids, so covering only the default set would
	 * leave most graceful in the game unmapped.
	 */
	@Test
	public void aRecolouredGracefulCollapsesToo()
	{
		assertEquals(ItemID.ZEAH_GRACEFUL_HOOD_ARCEUUS,
			WornItemIds.canonical(ItemID.ZEAH_GRACEFUL_HOOD_ARCEUUS_WORN));
		assertEquals(ItemID.ZEAH_GRACEFUL_TOP_HOSIDIUS,
			WornItemIds.canonical(ItemID.ZEAH_GRACEFUL_TOP_HOSIDIUS_WORN));
	}

	@Test
	public void bootsOfLightnessCollapse()
	{
		assertEquals(ItemID.IKOV_BOOTSOFLIGHTNESS,
			WornItemIds.canonical(ItemID.IKOV_BOOTSOFLIGHTNESSWORN));
	}

	@Test
	public void penanceGlovesCollapse()
	{
		assertEquals(ItemID.BARBASSAULT_PENANCE_GLOVES,
			WornItemIds.canonical(ItemID.BARBASSAULT_PENANCE_GLOVES_WORN));
	}

	/**
	 * The mapping is one-way. An inventory id is already canonical and must pass through
	 * untouched, or the table would start rewriting ids that were correct.
	 */
	@Test
	public void inventoryIdsPassThroughUnchanged()
	{
		assertEquals(ItemID.GRACEFUL_HOOD, WornItemIds.canonical(ItemID.GRACEFUL_HOOD));
		assertFalse(WornItemIds.isWornVariant(ItemID.GRACEFUL_HOOD));
		assertTrue(WornItemIds.isWornVariant(ItemID.GRACEFUL_HOOD_WORN));
	}

	@Test
	public void unrelatedItemsAreUntouched()
	{
		assertEquals(995, WornItemIds.canonical(995));
		assertEquals(385, WornItemIds.canonical(385));
		assertEquals(-1, WornItemIds.canonical(-1));
		assertEquals(0, WornItemIds.canonical(0));
	}

	/**
	 * Tripwire. If a merge truncates the table this fails rather than quietly logging phantoms
	 * again for whatever fell out.
	 */
	@Test
	public void tableIsTheExpectedSize()
	{
		assertEquals("table no longer matches the 87 entries mirrored from ItemManager.WORN_ITEMS",
			87, WornItemIds.size());
	}

	// ---- The behaviour the table exists for ----

	/**
	 * The end-to-end shape: unequipping graceful must produce a matched pair on one item id, not
	 * a destroyed item and a created one. Both snapshots are built through the same canonicalizer
	 * the plugin uses, so this is the real collapse rather than a restatement of the map.
	 */
	@Test
	public void unequippingGracefulNetsToASingleIdentity()
	{
		LedgerContainers.Canonicalizer canonicalizer = LedgerContainers.caching(
			itemId -> WornItemIds.canonical(itemId));

		ContainerSnapshot equipmentBefore = ContainerSnapshot.fromRaw(LedgerContainers.EQUIPMENT,
			new int[]{ItemID.GRACEFUL_HOOD_WORN}, new int[]{1}, canonicalizer);
		ContainerSnapshot equipmentAfter = ContainerSnapshot.empty(LedgerContainers.EQUIPMENT);

		ContainerSnapshot inventoryBefore = ContainerSnapshot.empty(LedgerContainers.INVENTORY);
		ContainerSnapshot inventoryAfter = ContainerSnapshot.fromRaw(LedgerContainers.INVENTORY,
			new int[]{ItemID.GRACEFUL_HOOD}, new int[]{1}, canonicalizer);

		List<ContainerDiffer.Delta> off = ContainerDiffer.diff(equipmentBefore, equipmentAfter);
		List<ContainerDiffer.Delta> on = ContainerDiffer.diff(inventoryBefore, inventoryAfter);

		assertEquals(1, off.size());
		assertEquals(1, on.size());
		assertEquals("both legs must be the same item, or netting cannot match them",
			off.get(0).getItemId(), on.get(0).getItemId());
		assertEquals(ItemID.GRACEFUL_HOOD, off.get(0).getItemId());
		assertEquals(-1, off.get(0).getDelta());
		assertEquals(1, on.get(0).getDelta());
	}

	/**
	 * And the same for a recolour, since those are the ids most likely to be missed.
	 */
	@Test
	public void equippingARecolourNetsToASingleIdentity()
	{
		LedgerContainers.Canonicalizer canonicalizer = LedgerContainers.caching(
			itemId -> WornItemIds.canonical(itemId));

		ContainerSnapshot inventoryBefore = ContainerSnapshot.fromRaw(LedgerContainers.INVENTORY,
			new int[]{ItemID.ZEAH_GRACEFUL_LEGS_LOVAKENGJ}, new int[]{1}, canonicalizer);
		ContainerSnapshot inventoryAfter = ContainerSnapshot.empty(LedgerContainers.INVENTORY);

		ContainerSnapshot equipmentBefore = ContainerSnapshot.empty(LedgerContainers.EQUIPMENT);
		ContainerSnapshot equipmentAfter = ContainerSnapshot.fromRaw(LedgerContainers.EQUIPMENT,
			new int[]{ItemID.ZEAH_GRACEFUL_LEGS_LOVAKENGJ_WORN}, new int[]{1}, canonicalizer);

		List<ContainerDiffer.Delta> out = ContainerDiffer.diff(inventoryBefore, inventoryAfter);
		List<ContainerDiffer.Delta> in = ContainerDiffer.diff(equipmentBefore, equipmentAfter);

		assertEquals(out.get(0).getItemId(), in.get(0).getItemId());
		assertEquals(ItemID.ZEAH_GRACEFUL_LEGS_LOVAKENGJ, in.get(0).getItemId());
	}

	/**
	 * The seam and its cache are untouched by this: the table is consulted through the same
	 * one-method Canonicalizer, so it is memoised like everything else.
	 */
	@Test
	public void theTableIsConsultedThroughTheExistingCachedSeam()
	{
		int[] calls = {0};
		LedgerContainers.Canonicalizer cached = LedgerContainers.caching(itemId ->
		{
			calls[0]++;
			return WornItemIds.canonical(itemId);
		});

		for (int i = 0; i < 25; i++)
		{
			assertEquals(ItemID.GRACEFUL_HOOD, cached.resolve(ItemID.GRACEFUL_HOOD_WORN));
		}
		assertEquals("one lookup per distinct id, cache unchanged", 1, calls[0]);
	}
}
