package com.nikko.theledger.capture;

import java.util.HashMap;
import java.util.Map;

/**
 * Container and interface ids, plus the item-identity seam.
 * <p>
 * These values are duplicated from {@code net.runelite.api.gameval.InventoryID} and
 * {@code net.runelite.api.gameval.InterfaceID} on purpose. The whole {@code capture}
 * package imports nothing from RuneLite, which is what lets every fixture run without a
 * client. It also makes the spine indifferent to whether the resolved client ships the
 * current {@code gameval.InventoryID} (plain int constants) or the deprecated
 * {@code net.runelite.api.InventoryID} enum.
 * <p>
 * If these ids are ever wrong the spine logs nothing at all and every unit test still
 * passes, so the debug panel lists every container id actually observed at runtime. That
 * is the only thing that catches this class of mistake.
 */
public final class LedgerContainers
{
	// ---- Container ids: net.runelite.api.gameval.InventoryID ----

	/**
	 * INV = 93. The player's backpack.
	 */
	public static final int INVENTORY = 93;
	/**
	 * WORN = 94. Equipped gear. Clears on death alongside the inventory.
	 */
	public static final int EQUIPMENT = 94;
	/**
	 * BANK = 95. Only populated once the bank interface has been opened, which is why an
	 * unseen bank is UNKNOWN rather than empty.
	 */
	public static final int BANK = 95;

	// ---- Interface group ids: net.runelite.api.gameval.InterfaceID ----

	/**
	 * BANKMAIN = 12.
	 */
	public static final int IFACE_BANKMAIN = 12;
	/**
	 * BANKSIDE = 15.
	 */
	public static final int IFACE_BANKSIDE = 15;
	/**
	 * BANK_DEPOSITBOX = 192. Depositing here never updates the bank container.
	 */
	public static final int IFACE_BANK_DEPOSITBOX = 192;
	/**
	 * GE_VIEWONLY = 200.
	 */
	public static final int IFACE_GE_VIEWONLY = 200;
	/**
	 * GE_COLLECT = 402. The collection box.
	 */
	public static final int IFACE_GE_COLLECT = 402;
	/**
	 * GE_OFFERS = 465. Offer setup and confirmation.
	 */
	public static final int IFACE_GE_OFFERS = 465;
	/**
	 * GE_OFFERS_SIDE = 467.
	 */
	public static final int IFACE_GE_OFFERS_SIDE = 467;

	/**
	 * The three containers Phase 1 diffs. Everything else is counted for visibility in the
	 * debug panel but never diffed, because classifying a shop or another player's trade
	 * offer is Phase 2 work.
	 */
	public static boolean isTracked(int containerId)
	{
		return containerId == INVENTORY || containerId == EQUIPMENT || containerId == BANK;
	}

	/**
	 * True for the two containers a death empties.
	 */
	public static boolean isCarried(int containerId)
	{
		return containerId == INVENTORY || containerId == EQUIPMENT;
	}

	public static String name(int containerId)
	{
		switch (containerId)
		{
			case INVENTORY:
				return "INVENTORY";
			case EQUIPMENT:
				return "EQUIPMENT";
			case BANK:
				return "BANK";
			default:
				return "CONTAINER_" + containerId;
		}
	}

	/**
	 * Collapses a raw item id onto the identity the ledger counts.
	 * <p>
	 * Implemented against {@code ItemComposition} in the plugin layer and against a fake in
	 * the fixtures. One method, so the result is cacheable in a single int-to-int map.
	 */
	public interface Canonicalizer
	{
		/**
		 * Returned for items that must not be counted at all.
		 */
		int SKIP = -1;

		/**
		 * @param itemId a raw container item id
		 * @return the canonical id — a noted item resolves to its unnoted variant, an
		 * ordinary item to itself — or {@link #SKIP} for placeholders and empty slots.
		 */
		int resolve(int itemId);
	}

	/**
	 * Memoises a canonicalizer.
	 * <p>
	 * {@code ItemContainerChanged} fires constantly and each firing hands over the entire
	 * container, so an uncached implementation performs a composition lookup per item per
	 * event. With this wrapper each item id costs one lookup per session.
	 * <p>
	 * Not synchronised: it is only ever touched from the client thread.
	 */
	public static Canonicalizer caching(Canonicalizer delegate)
	{
		return new CachingCanonicalizer(delegate);
	}

	private static final class CachingCanonicalizer implements Canonicalizer
	{
		private final Canonicalizer delegate;
		private final Map<Integer, Integer> cache = new HashMap<>();

		private CachingCanonicalizer(Canonicalizer delegate)
		{
			this.delegate = delegate;
		}

		@Override
		public int resolve(int itemId)
		{
			Integer hit = cache.get(itemId);
			if (hit != null)
			{
				return hit;
			}
			int resolved = delegate.resolve(itemId);
			cache.put(itemId, resolved);
			return resolved;
		}
	}

	private LedgerContainers()
	{
	}
}
