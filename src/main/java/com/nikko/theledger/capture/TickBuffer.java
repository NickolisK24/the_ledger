package com.nikko.theledger.capture;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

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

	/**
	 * Containers that went from UNKNOWN to observed during this tick, keyed by container id.
	 * <p>
	 * A first observation is not a delta and must never become one. There was no baseline to
	 * measure it against, so its contents prove nothing on their own — the items may have been
	 * sitting there since login. What it can do is corroborate: if another container lost exactly
	 * those items in the same tick, the pair is a transfer rather than a loss. Held separately
	 * from {@link #pending} precisely so it cannot be mistaken for evidence of a change.
	 */
	private final Map<Integer, ContainerSnapshot> firstObservations = new TreeMap<>();

	/**
	 * Containers whose baseline appeared at any point during this tick, whether through a first
	 * observation or through a direct read.
	 * <p>
	 * Kept because a delta captured while its counterpart was UNKNOWN must not become
	 * retroactively trustworthy just because that counterpart became known before the tick
	 * resolved. Confidence is a property of what was observable at capture time.
	 */
	private final Set<Integer> becameKnownThisTick = new TreeSet<>();

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

	/**
	 * Records that a container was observed for the first time, with no baseline to diff against.
	 */
	public void addFirstObservation(ContainerSnapshot observed)
	{
		if (observed == null || !observed.isKnown())
		{
			return;
		}
		firstObservations.put(observed.getContainerId(), observed);
		becameKnownThisTick.add(observed.getContainerId());
	}

	/**
	 * Records that a container gained a baseline this tick by being read directly rather than by
	 * reporting a change. Not reconcilable — a direct read is pre-existing state, not evidence
	 * that anything moved — but it still means the container was UNKNOWN when this tick's deltas
	 * were captured.
	 */
	public void markBecameKnown(int containerId)
	{
		becameKnownThisTick.add(containerId);
	}

	/**
	 * First observations from this tick, by container id, ascending.
	 */
	public Map<Integer, ContainerSnapshot> getFirstObservations()
	{
		return Collections.unmodifiableMap(firstObservations);
	}

	public Set<Integer> getBecameKnownThisTick()
	{
		return Collections.unmodifiableSet(becameKnownThisTick);
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
		firstObservations.clear();
		becameKnownThisTick.clear();
	}
}
