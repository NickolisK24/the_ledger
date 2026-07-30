package com.nikko.theledger.capture;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * An immutable canonical-item-id to quantity view of one container.
 * <p>
 * Two states matter and they are not the same thing:
 * <ul>
 *     <li>{@code known} with an empty map — the container really is empty.</li>
 *     <li>{@code UNKNOWN} — there is no baseline. The container has never been observed, or
 *     its baseline was invalidated by a state change.</li>
 * </ul>
 * Conflating those two is the phantom-gain bug. An unopened bank, a fresh login, a world
 * hop and a reconnect all produce a container that is populated for the first time; if the
 * missing baseline were treated as empty, every item in it would register as a gain. So
 * {@link ContainerDiffer} refuses to diff against UNKNOWN and the first observation only
 * ever seeds.
 * <p>
 * Quantities are stored positive and non-zero — absent means zero. Slot order is discarded,
 * which is what makes bank tab reorganisation produce no deltas at all rather than a
 * cancelling pair.
 */
public final class ContainerSnapshot
{
	private final int containerId;
	private final boolean known;
	private final Map<Integer, Integer> quantities;

	private ContainerSnapshot(int containerId, boolean known, Map<Integer, Integer> quantities)
	{
		this.containerId = containerId;
		this.known = known;
		this.quantities = quantities;
	}

	/**
	 * No baseline. Diffing against this yields nothing.
	 */
	public static ContainerSnapshot unknown(int containerId)
	{
		return new ContainerSnapshot(containerId, false, Collections.emptyMap());
	}

	/**
	 * A known, genuinely empty container.
	 */
	public static ContainerSnapshot empty(int containerId)
	{
		return new ContainerSnapshot(containerId, true, Collections.emptyMap());
	}

	public static ContainerSnapshot of(int containerId, Map<Integer, Integer> quantities)
	{
		Map<Integer, Integer> copy = new TreeMap<>();
		for (Map.Entry<Integer, Integer> e : quantities.entrySet())
		{
			if (e.getValue() != null && e.getValue() > 0)
			{
				copy.put(e.getKey(), e.getValue());
			}
		}
		return new ContainerSnapshot(containerId, true, Collections.unmodifiableMap(copy));
	}

	/**
	 * Fixture convenience: {@code ofPairs(93, 995, 1000, 526, 4)}.
	 */
	public static ContainerSnapshot ofPairs(int containerId, int... itemIdQuantityPairs)
	{
		if (itemIdQuantityPairs.length % 2 != 0)
		{
			throw new IllegalArgumentException("expected item id / quantity pairs");
		}
		Map<Integer, Integer> q = new HashMap<>();
		for (int i = 0; i < itemIdQuantityPairs.length; i += 2)
		{
			q.merge(itemIdQuantityPairs[i], itemIdQuantityPairs[i + 1], Integer::sum);
		}
		return of(containerId, q);
	}

	/**
	 * Builds a snapshot from a raw container listing, collapsing notes onto their unnoted id
	 * and dropping placeholders and empty slots.
	 * <p>
	 * Canonicalising here — before anything is diffed — is what stops withdrawing as a note
	 * from looking like one item destroyed and a different one created.
	 *
	 * @param itemIds    raw ids, parallel to {@code quantities}
	 * @param quantities raw stack sizes
	 */
	public static ContainerSnapshot fromRaw(int containerId, int[] itemIds, int[] quantities,
											LedgerContainers.Canonicalizer canonicalizer)
	{
		if (itemIds.length != quantities.length)
		{
			throw new IllegalArgumentException("itemIds and quantities must be the same length");
		}
		Map<Integer, Integer> merged = new HashMap<>(Math.max(4, itemIds.length));
		for (int i = 0; i < itemIds.length; i++)
		{
			int rawId = itemIds[i];
			int qty = quantities[i];
			if (rawId <= 0 || qty <= 0)
			{
				// Empty slot. Placeholders also report zero quantity, but they are rejected
				// by the canonicalizer below rather than relying on that.
				continue;
			}
			int canonical = canonicalizer.resolve(rawId);
			if (canonical == LedgerContainers.Canonicalizer.SKIP)
			{
				continue;
			}
			merged.merge(canonical, qty, Integer::sum);
		}
		return of(containerId, merged);
	}

	public int getContainerId()
	{
		return containerId;
	}

	/**
	 * False when there is no baseline to diff against.
	 */
	public boolean isKnown()
	{
		return known;
	}

	/**
	 * Canonical id to quantity, ascending by id, unmodifiable.
	 */
	public Map<Integer, Integer> getQuantities()
	{
		return quantities;
	}

	public int quantityOf(int canonicalItemId)
	{
		Integer q = quantities.get(canonicalItemId);
		return q == null ? 0 : q;
	}

	public boolean isEmpty()
	{
		return quantities.isEmpty();
	}

	@Override
	public boolean equals(Object o)
	{
		if (this == o)
		{
			return true;
		}
		if (!(o instanceof ContainerSnapshot))
		{
			return false;
		}
		ContainerSnapshot other = (ContainerSnapshot) o;
		return containerId == other.containerId
			&& known == other.known
			&& quantities.equals(other.quantities);
	}

	@Override
	public int hashCode()
	{
		int h = containerId;
		h = 31 * h + (known ? 1 : 0);
		h = 31 * h + quantities.hashCode();
		return h;
	}

	@Override
	public String toString()
	{
		return LedgerContainers.name(containerId) + (known ? quantities.toString() : "=UNKNOWN");
	}
}
