package com.nikko.theledger.capture;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Accumulates deltas across every container touched within a single client tick.
 * <p>
 * Nothing can be classified from one container in isolation. Moving an item between two of
 * the player's own containers fires two separate {@code ItemContainerChanged} events in the
 * same tick, and seen alone the first is a loss and the second is a gain. Buffering the
 * whole tick and resolving on the following {@code GameTick} is the only way to see both
 * legs together.
 * <p>
 * Not thread safe: only ever touched from the client thread.
 */
public final class TickBuffer
{
	/**
	 * (containerId, itemId) to running delta. Sorted, so {@link #drain()} is deterministic.
	 */
	private final Map<Long, Integer> pending = new TreeMap<>();

	private static long key(int containerId, int itemId)
	{
		return ((long) containerId << 32) | (itemId & 0xffffffffL);
	}

	private static int containerOf(long key)
	{
		return (int) (key >> 32);
	}

	private static int itemOf(long key)
	{
		return (int) key;
	}

	public void add(int containerId, int itemId, int delta)
	{
		if (delta == 0)
		{
			return;
		}
		pending.merge(key(containerId, itemId), delta, Integer::sum);
	}

	public void addAll(List<ContainerDiffer.Delta> deltas)
	{
		for (ContainerDiffer.Delta d : deltas)
		{
			add(d.getContainerId(), d.getItemId(), d.getDelta());
		}
	}

	public boolean isEmpty()
	{
		for (Integer v : pending.values())
		{
			if (v != null && v != 0)
			{
				return false;
			}
		}
		return true;
	}

	public int size()
	{
		return pending.size();
	}

	/**
	 * Removes and returns everything buffered, ordered by container id then item id.
	 * <p>
	 * Entries that summed to zero within the tick are dropped: a container that ended the
	 * tick holding exactly what it started with did not move anything, regardless of how
	 * many events it fired in between.
	 */
	public List<ContainerDiffer.Delta> drain()
	{
		List<ContainerDiffer.Delta> out = new ArrayList<>(pending.size());
		for (Map.Entry<Long, Integer> e : pending.entrySet())
		{
			int delta = e.getValue() == null ? 0 : e.getValue();
			if (delta != 0)
			{
				out.add(new ContainerDiffer.Delta(containerOf(e.getKey()), itemOf(e.getKey()), delta));
			}
		}
		pending.clear();
		return out;
	}

	public void clear()
	{
		pending.clear();
	}
}
