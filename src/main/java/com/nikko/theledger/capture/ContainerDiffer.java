package com.nikko.theledger.capture;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Turns a pair of snapshots into signed per-item deltas.
 * <p>
 * {@code ItemContainerChanged} hands over the entire container rather than a change, so the
 * only way to learn what actually moved is to diff against what was there before.
 * <p>
 * Stateless and total: it never looks at menu actions, game state or the death window.
 * Classification is {@link MovementResolver}'s job and happens a tick later.
 */
public final class ContainerDiffer
{
	/**
	 * A signed quantity change for one canonical item in one container.
	 */
	public static final class Delta
	{
		private final int containerId;
		private final int itemId;
		private final int delta;

		public Delta(int containerId, int itemId, int delta)
		{
			this.containerId = containerId;
			this.itemId = itemId;
			this.delta = delta;
		}

		public int getContainerId()
		{
			return containerId;
		}

		public int getItemId()
		{
			return itemId;
		}

		/**
		 * Never zero. Positive is an increase in this container.
		 */
		public int getDelta()
		{
			return delta;
		}

		@Override
		public boolean equals(Object o)
		{
			if (this == o)
			{
				return true;
			}
			if (!(o instanceof Delta))
			{
				return false;
			}
			Delta other = (Delta) o;
			return containerId == other.containerId && itemId == other.itemId && delta == other.delta;
		}

		@Override
		public int hashCode()
		{
			return (containerId * 31 + itemId) * 31 + delta;
		}

		@Override
		public String toString()
		{
			return LedgerContainers.name(containerId) + " " + itemId + " " + (delta > 0 ? "+" : "") + delta;
		}
	}

	/**
	 * @return the deltas from {@code before} to {@code after}, ascending by item id, with
	 * zero-change items omitted. Empty whenever either side has no baseline — a container
	 * being observed for the first time seeds silently and produces no events, which is what
	 * keeps logins, hops, loads and reconnects free of phantom gains.
	 */
	public static List<Delta> diff(ContainerSnapshot before, ContainerSnapshot after)
	{
		if (before == null || after == null || !before.isKnown() || !after.isKnown())
		{
			return new ArrayList<>();
		}
		if (before.getContainerId() != after.getContainerId())
		{
			throw new IllegalArgumentException("cannot diff different containers: "
				+ before.getContainerId() + " vs " + after.getContainerId());
		}

		Map<Integer, Integer> b = before.getQuantities();
		Map<Integer, Integer> a = after.getQuantities();

		// Ascending item id, so output ordering is stable for the fixtures.
		TreeSet<Integer> ids = new TreeSet<>(b.keySet());
		ids.addAll(a.keySet());

		List<Delta> deltas = new ArrayList<>();
		for (int id : ids)
		{
			int change = after.quantityOf(id) - before.quantityOf(id);
			if (change != 0)
			{
				deltas.add(new Delta(after.getContainerId(), id, change));
			}
		}
		return deltas;
	}

	private ContainerDiffer()
	{
	}
}
