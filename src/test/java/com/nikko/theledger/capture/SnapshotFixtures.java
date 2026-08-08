package com.nikko.theledger.capture;

import com.nikko.theledger.model.EventType;
import com.nikko.theledger.model.LedgerEvent;
import com.nikko.theledger.model.MovementCategory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Hand-built snapshot sequences fed through the real pipeline. No client required.
 * <p>
 * {@link #containerChanged} reproduces exactly what
 * {@code TheLedgerPlugin.onItemContainerChanged} does, and {@link #gameTick} what
 * {@code onGameTick} does. Those two methods and the plugin's subscribers are the only glue
 * in the codebase, and they are kept deliberately identical — if you change one, change the
 * other.
 */
public final class SnapshotFixtures
{
	public static final String SESSION = "fixture-session";

	// Real item ids, so the fixtures read like the game.
	public static final int COINS = 995;
	public static final int SHARK = 385;
	public static final int SHARK_NOTED = 386;
	public static final int RUNE_PLATEBODY = 1127;
	public static final int RUNE_PLATEBODY_NOTED = 1128;
	public static final int DRAGON_BONES = 536;
	public static final int DRAGON_BONES_NOTED = 537;
	public static final int ABYSSAL_WHIP = 4151;
	public static final int SUPER_RESTORE_4 = 3024;
	public static final int SUPER_RESTORE_3 = 3026;
	public static final int RUNE_ARROW = 892;
	/**
	 * Placeholders have their own ids; this fixture only needs one that the canonicalizer
	 * rejects.
	 */
	public static final int SHARK_PLACEHOLDER = 20385;

	private final Map<Integer, ContainerSnapshot> snapshots = new HashMap<>();
	private final TickBuffer buffer = new TickBuffer();
	private final MovementResolver resolver;
	private final FakeCanonicalizer rawCanonicalizer = new FakeCanonicalizer();
	private final LedgerContainers.Canonicalizer canonicalizer;
	private final List<LedgerEvent> all = new ArrayList<>();

	private ActionContext context = ActionContext.EMPTY;
	private int tick = 100;
	private long ts = 1_700_000_000_000L;

	public SnapshotFixtures()
	{
		this(MovementResolver.DEFAULT_DEATH_WINDOW_TICKS);
	}

	public SnapshotFixtures(int deathWindowTicks)
	{
		this.resolver = new MovementResolver(SESSION, deathWindowTicks,
			MovementResolver.DEFAULT_ACTION_CONTEXT_TICKS);
		this.canonicalizer = LedgerContainers.caching(rawCanonicalizer);
		// Note links the fixtures rely on.
		rawCanonicalizer
			.note(SHARK_NOTED, SHARK)
			.note(RUNE_PLATEBODY_NOTED, RUNE_PLATEBODY)
			.note(DRAGON_BONES_NOTED, DRAGON_BONES)
			.placeholder(SHARK_PLACEHOLDER);
	}

	public FakeCanonicalizer rawCanonicalizer()
	{
		return rawCanonicalizer;
	}

	public MovementResolver resolver()
	{
		return resolver;
	}

	public int tick()
	{
		return tick;
	}

	public long ts()
	{
		return ts;
	}

	/**
	 * Which containers currently have a baseline. Mirrors {@code TheLedgerPlugin.hasBaseline}.
	 */
	public MovementResolver.BaselineStatus baselines()
	{
		return containerId ->
		{
			ContainerSnapshot s = snapshots.get(containerId);
			return s != null && s.isKnown();
		};
	}

	public boolean isSeeded(int containerId)
	{
		return baselines().isSeeded(containerId);
	}

	/**
	 * The state a normal logged-in account is in once {@code seedMissingBaselines} has run: the
	 * inventory and equipment are readable immediately, the bank is not readable until its
	 * interface has been opened.
	 * <p>
	 * Fixtures that are not specifically about unseeded containers should start from here. Before
	 * this existed the fixtures seeded only what a scenario happened to touch, which is precisely
	 * the asymmetry that produced one-legged phantoms in a real session.
	 */
	public SnapshotFixtures loggedIn(int... inventoryPairs)
	{
		seed(LedgerContainers.INVENTORY, inventoryPairs);
		seed(LedgerContainers.EQUIPMENT);
		return this;
	}

	/**
	 * Mirrors {@code TheLedgerPlugin.seedMissingBaselines} for one container.
	 */
	public SnapshotFixtures eagerSeed(int containerId, int... rawItemIdQuantityPairs)
	{
		return seed(containerId, rawItemIdQuantityPairs);
	}

	public ContainerSnapshot snapshotOf(int containerId)
	{
		ContainerSnapshot s = snapshots.get(containerId);
		return s == null ? ContainerSnapshot.unknown(containerId) : s;
	}

	public List<LedgerEvent> allEvents()
	{
		return Collections.unmodifiableList(all);
	}

	public SnapshotFixtures advanceTick()
	{
		tick++;
		ts += 600;
		return this;
	}

	/**
	 * One {@code ItemContainerChanged}: the raw pairs are the whole container, not a delta.
	 * <p>
	 * Identical to {@code TheLedgerPlugin.onItemContainerChanged}.
	 */
	public SnapshotFixtures containerChanged(int containerId, int... rawItemIdQuantityPairs)
	{
		if (rawItemIdQuantityPairs.length % 2 != 0)
		{
			throw new IllegalArgumentException("expected item id / quantity pairs");
		}
		int n = rawItemIdQuantityPairs.length / 2;
		int[] ids = new int[n];
		int[] qtys = new int[n];
		for (int i = 0; i < n; i++)
		{
			ids[i] = rawItemIdQuantityPairs[i * 2];
			qtys[i] = rawItemIdQuantityPairs[i * 2 + 1];
		}

		ContainerSnapshot next = ContainerSnapshot.fromRaw(containerId, ids, qtys, canonicalizer);
		buffer.addAll(ContainerDiffer.diff(snapshotOf(containerId), next));
		snapshots.put(containerId, next);
		return this;
	}

	/**
	 * Seeds a baseline without generating events, the way the first observation of a container
	 * does at runtime.
	 */
	public SnapshotFixtures seed(int containerId, int... rawItemIdQuantityPairs)
	{
		containerChanged(containerId, rawItemIdQuantityPairs);
		buffer.clear();
		return this;
	}

	public SnapshotFixtures menuClick(String option, int widgetGroupId)
	{
		context = ActionContext.onInterface(option, widgetGroupId, tick);
		return this;
	}

	/**
	 * A click that is not on any interface.
	 */
	public SnapshotFixtures worldClick(String option)
	{
		context = ActionContext.of(option, "", -1, -1, "LOGGED_IN", tick);
		return this;
	}

	/**
	 * Resolves the tick and advances the clock. Identical to
	 * {@code TheLedgerPlugin.onGameTick}.
	 */
	public List<LedgerEvent> gameTick()
	{
		List<LedgerEvent> events = resolver.resolveTick(tick, ts, buffer.drain(), context, baselines());
		all.addAll(events);
		advanceTick();
		return events;
	}

	/**
	 * Every baseline is invalidated. Nothing before this can be diffed against anything after.
	 */
	public LedgerEvent stateReset(String reason)
	{
		// Mirrors TheLedgerPlugin.invalidateBaselines, including the death exception: a death
		// causes a respawn region load, so reseeding the carried containers there would absorb the
		// entire wipe.
		boolean deathPending = resolver.isInDeathWindow(tick);
		if (!deathPending)
		{
			buffer.clear();
		}
		for (Integer containerId : new ArrayList<>(snapshots.keySet()))
		{
			if (deathPending && LedgerContainers.isCarried(containerId))
			{
				continue;
			}
			snapshots.put(containerId, ContainerSnapshot.unknown(containerId));
		}
		context = ActionContext.EMPTY;
		LedgerEvent event = resolver.recordStateReset(tick, ts, reason);
		all.add(event);
		return event;
	}

	public LedgerEvent death()
	{
		LedgerEvent event = resolver.recordDeath(tick, ts);
		all.add(event);
		return event;
	}

	public LedgerEvent xp(String skill, int totalXp)
	{
		LedgerEvent event = resolver.resolveXp(tick, ts, skill, totalXp);
		if (event != null)
		{
			all.add(event);
		}
		return event;
	}

	// ---- Assertion helpers ----

	public static List<LedgerEvent> withCategory(List<LedgerEvent> events, MovementCategory category)
	{
		List<LedgerEvent> out = new ArrayList<>();
		for (LedgerEvent e : events)
		{
			if (e.getCategory() == category)
			{
				out.add(e);
			}
		}
		return out;
	}

	public static List<LedgerEvent> ofType(List<LedgerEvent> events, EventType type)
	{
		List<LedgerEvent> out = new ArrayList<>();
		for (LedgerEvent e : events)
		{
			if (e.getType() == type)
			{
				out.add(e);
			}
		}
		return out;
	}

	/**
	 * The number of events claiming wealth appeared or disappeared. Every negative fixture
	 * requires this to be zero.
	 */
	public static int phantomCount(List<LedgerEvent> events)
	{
		return withCategory(events, MovementCategory.UNCLASSIFIED_GAIN).size()
			+ withCategory(events, MovementCategory.UNCLASSIFIED_LOSS).size();
	}

	public static String describe(List<LedgerEvent> events)
	{
		StringBuilder sb = new StringBuilder();
		for (LedgerEvent e : events)
		{
			sb.append('\n').append(e.getType())
				.append(' ').append(e.getCategory())
				.append(" container=").append(e.getContainerId())
				.append(" item=").append(e.getItemId())
				.append(" qty=").append(e.getQty())
				.append(" flags=").append(e.getFlags());
		}
		return sb.toString();
	}
}
