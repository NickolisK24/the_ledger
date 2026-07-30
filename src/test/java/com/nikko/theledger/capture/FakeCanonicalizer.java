package com.nikko.theledger.capture;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Stands in for the {@code ItemComposition} lookup so the fixtures need no client.
 * <p>
 * Mirrors the real rules: a noted id resolves to its unnoted variant, a placeholder is
 * skipped entirely, anything else resolves to itself. Also counts lookups per item id, which
 * is how the caching wrapper is tested.
 */
public final class FakeCanonicalizer implements LedgerContainers.Canonicalizer
{
	private final Map<Integer, Integer> noteLinks = new HashMap<>();
	private final Set<Integer> placeholders = new HashSet<>();
	private final Map<Integer, Integer> calls = new HashMap<>();

	/**
	 * Declares {@code notedId} as the noted form of {@code unnotedId}.
	 */
	public FakeCanonicalizer note(int notedId, int unnotedId)
	{
		noteLinks.put(notedId, unnotedId);
		return this;
	}

	public FakeCanonicalizer placeholder(int placeholderId)
	{
		placeholders.add(placeholderId);
		return this;
	}

	@Override
	public int resolve(int itemId)
	{
		calls.merge(itemId, 1, Integer::sum);
		if (placeholders.contains(itemId))
		{
			return SKIP;
		}
		Integer unnoted = noteLinks.get(itemId);
		return unnoted == null ? itemId : unnoted;
	}

	public int callsFor(int itemId)
	{
		Integer n = calls.get(itemId);
		return n == null ? 0 : n;
	}

	public int totalCalls()
	{
		int total = 0;
		for (int n : calls.values())
		{
			total += n;
		}
		return total;
	}
}
