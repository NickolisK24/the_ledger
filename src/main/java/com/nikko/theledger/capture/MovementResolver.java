package com.nikko.theledger.capture;

import com.nikko.theledger.model.EventType;
import com.nikko.theledger.model.LedgerEvent;
import com.nikko.theledger.model.MovementCategory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Classifies a tick's worth of buffered deltas into ledger events.
 * <p>
 * Holds the small amount of mutable state classification needs — the death window and the
 * per-skill experience baselines — and nothing else. No client types, so every rule here is
 * exercised by fixtures rather than by playing the game.
 * <p>
 * The order of the rules is the whole design:
 * <ol>
 *     <li><b>Net out transfers first.</b> Whatever left one container and arrived in another
 *     within the same tick moved; it was not gained or lost. This single rule covers banking,
 *     equipping, withdrawing as a note and depositing into an open bank, because by the time
 *     deltas reach here notes have already been collapsed onto their unnoted id.</li>
 *     <li><b>Then the death window,</b> so a corpse-emptying wipe is never read as a deposit
 *     or an unexplained loss.</li>
 *     <li><b>Then invisible destinations,</b> inferred from the menu action and always
 *     flagged so the inference stays auditable.</li>
 *     <li><b>Only then unclassified,</b> which in Phase 1 means "real, cause unknown".</li>
 * </ol>
 */
public final class MovementResolver
{
	/**
	 * How long the menu action stays relevant. The client applies a deposit or a Grand
	 * Exchange confirmation a tick or two after the click, so a single-tick context window
	 * would miss its own effect.
	 */
	public static final int DEFAULT_ACTION_CONTEXT_TICKS = 2;
	/**
	 * Ticks after a death during which inventory and equipment losses are DEATH_LOSS.
	 */
	public static final int DEFAULT_DEATH_WINDOW_TICKS = 5;

	/**
	 * How many times a state transition may push the death window back.
	 * <p>
	 * A death causes a respawn region load, so a STATE_RESET always follows it. One extension
	 * covers that; two covers a death that loads twice. Bounded so a pathological run of
	 * transitions cannot hold the window open indefinitely.
	 */
	public static final int MAX_DEATH_WINDOW_EXTENSIONS = 2;

	private static final int NO_DEATH = Integer.MIN_VALUE;

	/**
	 * Which containers currently have a baseline. Supplied by the caller because the resolver
	 * does not own the snapshots.
	 */
	public interface BaselineStatus
	{
		boolean isSeeded(int containerId);
	}

	/**
	 * Nothing has a baseline. Useful for fixtures and as a safe default.
	 */
	public static final BaselineStatus NOTHING_SEEDED = containerId -> false;

	private final String sessionId;
	private final int deathWindowTicks;
	private final int actionContextTicks;

	private final Map<String, Integer> xpBaselines = new HashMap<>();
	private int deathWindowEndTick = NO_DEATH;
	private int deathWindowExtensions;
	private int negativeXpReseeds;

	public MovementResolver(String sessionId)
	{
		this(sessionId, DEFAULT_DEATH_WINDOW_TICKS, DEFAULT_ACTION_CONTEXT_TICKS);
	}

	public MovementResolver(String sessionId, int deathWindowTicks, int actionContextTicks)
	{
		this.sessionId = sessionId;
		this.deathWindowTicks = Math.max(0, deathWindowTicks);
		this.actionContextTicks = Math.max(0, actionContextTicks);
	}

	/**
	 * Resolves one tick.
	 *
	 * @param deltas    everything {@link TickBuffer#drain()} produced for this tick
	 * @param context   the most recent menu interaction, or {@link ActionContext#EMPTY}
	 * @param baselines which containers currently have a baseline. A movement whose plausible
	 *                  counterpart has none cannot be trusted as a gain or a loss, because the
	 *                  other leg could not have appeared even if it happened.
	 * @return the events to log, in a deterministic order: transfer legs first, then losses,
	 * then gains, each group ordered by container id.
	 */
	public List<LedgerEvent> resolveTick(int tick, long ts, List<ContainerDiffer.Delta> deltas,
										 ActionContext context, BaselineStatus baselines)
	{
		return resolveTick(tick, ts, deltas, Collections.emptyMap(), Collections.emptySet(),
			context, baselines);
	}

	/**
	 * Resolves one tick, including any container seen for the first time during it.
	 *
	 * @param firstObservations  containers that went from UNKNOWN to observed this tick. Their
	 *                           contents can corroborate an opposite movement from another
	 *                           container, and can do nothing else — see
	 *                           {@link #reconcileAgainstFirstObservations}.
	 * @param becameKnownThisTick containers whose baseline appeared at any point during this tick.
	 *                           Treated as unseeded for confidence purposes, because a delta
	 *                           captured while its counterpart was UNKNOWN does not become
	 *                           trustworthy just because that counterpart turned up before the
	 *                           tick resolved.
	 */
	public List<LedgerEvent> resolveTick(int tick, long ts, List<ContainerDiffer.Delta> deltas,
										 Map<Integer, ContainerSnapshot> firstObservations,
										 Set<Integer> becameKnownThisTick,
										 ActionContext context, BaselineStatus baselines)
	{
		BaselineStatus supplied = baselines == null ? NOTHING_SEEDED : baselines;
		Set<Integer> late = becameKnownThisTick == null ? Collections.emptySet() : becameKnownThisTick;
		BaselineStatus seeded = containerId -> supplied.isSeeded(containerId) && !late.contains(containerId);
		Map<Integer, ContainerSnapshot> observed =
			firstObservations == null ? Collections.emptyMap() : firstObservations;
		// Consumed as matches are made, so one first-observed item cannot corroborate two losses.
		Map<Integer, Map<Integer, Integer>> available = availableFromFirstObservations(observed);
		List<LedgerEvent> events = new ArrayList<>();
		if (deltas == null || deltas.isEmpty())
		{
			return events;
		}

		ActionContext ctx = freshContext(tick, context);
		boolean inDeathWindow = isInDeathWindow(tick);

		// Group by canonical item id so both legs of a movement are considered together.
		Map<Integer, List<ContainerDiffer.Delta>> byItem = new TreeMap<>();
		for (ContainerDiffer.Delta d : deltas)
		{
			if (d.getDelta() == 0)
			{
				continue;
			}
			byItem.computeIfAbsent(d.getItemId(), k -> new ArrayList<>()).add(d);
		}

		for (Map.Entry<Integer, List<ContainerDiffer.Delta>> entry : byItem.entrySet())
		{
			int itemId = entry.getKey();
			List<ContainerDiffer.Delta> legs = entry.getValue();

			List<ContainerDiffer.Delta> gains = new ArrayList<>();
			List<ContainerDiffer.Delta> losses = new ArrayList<>();
			long totalGained = 0;
			long totalLost = 0;
			for (ContainerDiffer.Delta d : legs)
			{
				if (d.getDelta() > 0)
				{
					gains.add(d);
					totalGained += d.getDelta();
				}
				else
				{
					losses.add(d);
					totalLost += -(long) d.getDelta();
				}
			}

			// The amount that demonstrably only moved. Everything above it is a real change.
			long matched = Math.min(totalGained, totalLost);

			List<LedgerEvent> transferLegs = new ArrayList<>();
			List<int[]> residualLosses = new ArrayList<>();
			List<int[]> residualGains = new ArrayList<>();

			long remaining = matched;
			for (ContainerDiffer.Delta d : losses)
			{
				int magnitude = -d.getDelta();
				int moved = (int) Math.min(remaining, magnitude);
				remaining -= moved;
				if (moved > 0)
				{
					transferLegs.add(movement(tick, ts, MovementCategory.TRANSFER,
						d.getContainerId(), itemId, -moved, ctx, Collections.emptyList()));
				}
				int residual = magnitude - moved;
				if (residual > 0)
				{
					residualLosses.add(new int[]{d.getContainerId(), residual});
				}
			}

			remaining = matched;
			for (ContainerDiffer.Delta d : gains)
			{
				int magnitude = d.getDelta();
				int moved = (int) Math.min(remaining, magnitude);
				remaining -= moved;
				if (moved > 0)
				{
					transferLegs.add(movement(tick, ts, MovementCategory.TRANSFER,
						d.getContainerId(), itemId, moved, ctx, Collections.emptyList()));
				}
				int residual = magnitude - moved;
				if (residual > 0)
				{
					residualGains.add(new int[]{d.getContainerId(), residual});
				}
			}

			events.addAll(transferLegs);

			// Anything still unmatched may yet be corroborated by a container seen for the first
			// time this tick. Only losses: a first observation cannot show that its own container
			// gave anything up, because there was no baseline for it to have given it up from.
			residualLosses = reconcileAgainstFirstObservations(tick, ts, itemId, residualLosses,
				available, ctx, events);

			for (int[] loss : residualLosses)
			{
				events.add(classifyLoss(tick, ts, loss[0], itemId, loss[1], ctx,
					inDeathWindow, seeded));
			}
			for (int[] gain : residualGains)
			{
				events.add(classifyGain(tick, ts, gain[0], itemId, gain[1], ctx, seeded));
			}
		}

		return events;
	}

	/**
	 * Per container, per item, how much a first observation could corroborate.
	 */
	private static Map<Integer, Map<Integer, Integer>> availableFromFirstObservations(
		Map<Integer, ContainerSnapshot> observed)
	{
		Map<Integer, Map<Integer, Integer>> available = new TreeMap<>();
		for (Map.Entry<Integer, ContainerSnapshot> e : observed.entrySet())
		{
			if (e.getValue() != null && e.getValue().isKnown())
			{
				available.put(e.getKey(), new TreeMap<>(e.getValue().getQuantities()));
			}
		}
		return available;
	}

	/**
	 * Matches leftover losses against containers observed for the first time this tick.
	 * <p>
	 * The production case this exists for: equipment is empty at login, so the client never
	 * allocates the container and it stays UNKNOWN. The first six graceful pieces go on, the
	 * inventory reports losing three of them, and equipment reports its very first observation
	 * already holding those three. Treating that observation as nothing but a baseline threw the
	 * corroboration away and left three losses the player never took.
	 * <p>
	 * The rule is deliberately one-directional. A first observation may only <b>corroborate</b> a
	 * loss someone else measured; it may never assert anything by itself. Whatever it holds that
	 * nobody lost is simply baseline — the gear you were already wearing — and produces no event
	 * at all. That is what keeps this from trading a phantom loss for a phantom gain.
	 *
	 * @param residualLosses losses left after ordinary same-tick netting
	 * @param available      remaining corroborating quantity, consumed as matches are made
	 * @param events         reconciled TRANSFER legs are appended here
	 * @return the losses still unmatched
	 */
	private List<int[]> reconcileAgainstFirstObservations(int tick, long ts, int itemId,
														  List<int[]> residualLosses,
														  Map<Integer, Map<Integer, Integer>> available,
														  ActionContext ctx, List<LedgerEvent> events)
	{
		if (residualLosses.isEmpty() || available.isEmpty())
		{
			return residualLosses;
		}

		List<int[]> stillUnmatched = new ArrayList<>(residualLosses.size());
		for (int[] loss : residualLosses)
		{
			int losingContainer = loss[0];
			int remaining = loss[1];

			// Ascending container id, so the pairing is deterministic when more than one container
			// was first observed in the same tick.
			for (Map.Entry<Integer, Map<Integer, Integer>> entry : available.entrySet())
			{
				if (remaining <= 0)
				{
					break;
				}
				int observedContainer = entry.getKey();
				if (observedContainer == losingContainer)
				{
					continue;
				}
				Integer pool = entry.getValue().get(itemId);
				if (pool == null || pool <= 0)
				{
					continue;
				}

				int moved = Math.min(remaining, pool);
				entry.getValue().put(itemId, pool - moved);
				remaining -= moved;

				List<String> provenance =
					Collections.singletonList(LedgerEvent.FLAG_FIRST_OBSERVATION_RECONCILED);
				events.add(movement(tick, ts, MovementCategory.TRANSFER, losingContainer, itemId,
					-moved, ctx, provenance));
				events.add(movement(tick, ts, MovementCategory.TRANSFER, observedContainer, itemId,
					moved, ctx, provenance));
			}

			if (remaining > 0)
			{
				stillUnmatched.add(new int[]{losingContainer, remaining});
			}
		}
		return stillUnmatched;
	}

	private LedgerEvent classifyLoss(int tick, long ts, int containerId, int itemId, int magnitude,
									 ActionContext ctx, boolean inDeathWindow, BaselineStatus seeded)
	{
		boolean bankBaselineKnown = seeded.isSeeded(LedgerContainers.BANK);
		// A death empties the inventory and the equipment together. Both are DEATH_LOSS.
		if (inDeathWindow && LedgerContainers.isCarried(containerId))
		{
			return movement(tick, ts, MovementCategory.DEATH_LOSS, containerId, itemId, -magnitude,
				ctx, Collections.singletonList(LedgerEvent.FLAG_DEATH_WINDOW));
		}

		// Destinations that never update a container of their own. Always flagged.
		if (LedgerContainers.isCarried(containerId) && ctx.looksLikeDeposit())
		{
			List<String> flags = new ArrayList<>(2);
			flags.add(LedgerEvent.FLAG_INFERRED_DEPOSIT_BOX);
			if (!bankBaselineKnown)
			{
				flags.add(LedgerEvent.FLAG_UNKNOWN_BANK_BASELINE);
			}
			return movement(tick, ts, MovementCategory.TRANSFER, containerId, itemId, -magnitude,
				ctx, flags);
		}

		if (isGrandExchangeSide(containerId) && ctx.looksLikeGrandExchange())
		{
			return movement(tick, ts, MovementCategory.TRANSFER, containerId, itemId, -magnitude,
				ctx, Collections.singletonList(LedgerEvent.FLAG_INFERRED_GRAND_EXCHANGE));
		}

		// The direction is still a loss. Whether it can be believed is a separate axis, carried
		// in the flags, so a consumer can sum by category and filter by confidence independently.
		String unverifiable = unverifiableReason(containerId, seeded);
		return movement(tick, ts, MovementCategory.UNCLASSIFIED_LOSS, containerId, itemId,
			-magnitude, ctx,
			unverifiable == null ? Collections.emptyList() : Collections.singletonList(unverifiable));
	}

	private LedgerEvent classifyGain(int tick, long ts, int containerId, int itemId, int magnitude,
									 ActionContext ctx, BaselineStatus seeded)
	{
		// Collecting a completed offer, or withdrawing from the collection box: the items come
		// from a holding area the client exposes no container for.
		if (isGrandExchangeSide(containerId) && ctx.looksLikeGrandExchange())
		{
			return movement(tick, ts, MovementCategory.TRANSFER, containerId, itemId, magnitude,
				ctx, Collections.singletonList(LedgerEvent.FLAG_INFERRED_GRAND_EXCHANGE));
		}

		String unverifiable = unverifiableReason(containerId, seeded);
		return movement(tick, ts, MovementCategory.UNCLASSIFIED_GAIN, containerId, itemId,
			magnitude, ctx,
			unverifiable == null ? Collections.emptyList() : Collections.singletonList(unverifiable));
	}

	/**
	 * Why this one-legged movement cannot be trusted as wealth appearing or disappearing, or null
	 * if it can. Returned as a flag rather than a category: confidence is orthogonal to what kind
	 * of economic movement this was.
	 * <p>
	 * <b>The decision is made on container identity alone.</b> It reads no menu option, no target
	 * text and no widget id — nothing that a game revision or a localised client could change
	 * underneath it. A rune pouch deposit and a monster drop are both one-legged gains with no
	 * observable counterpart, and they are told apart by <i>where they land</i>: a drop arrives in
	 * the inventory, and a pouch deposit changes the bank. The bank cannot receive a drop.
	 * <p>
	 * Two distinct reasons, with deliberately different preconditions:
	 * <ol>
	 *     <li>{@code COUNTERPARTY_UNSEEDED} — a container this one could have exchanged with has
	 *     no baseline, so it could not report its side even though it moved. <b>Only ever returned
	 *     while a counterpart is actually UNKNOWN.</b> The moment it is seeded, movements stop
	 *     being flagged for this reason, so the flag can never quietly absorb a genuine phantom.</li>
	 *     <li>{@code COUNTERPARTY_UNTRACKED} — the movement is in a container that can only change
	 *     by transfer, so a missing counterpart proves the other side was storage the spine does
	 *     not track: a rune pouch, a looting bag, a seed vault. This does not depend on seeding,
	 *     because the counterpart is not a tracked container in the first place.</li>
	 * </ol>
	 * The default is deliberately <b>unflagged</b>. A movement in a container that gains items
	 * from the world — the inventory above all — is never flagged by either rule, so a real drop
	 * is never suppressed. Over-counting a phantom is recoverable; discarding revenue is not.
	 */
	static String unverifiableReason(int containerId, BaselineStatus seeded)
	{
		for (int counterpart : LedgerContainers.counterpartsOf(containerId))
		{
			if (!seeded.isSeeded(counterpart))
			{
				return LedgerEvent.FLAG_COUNTERPARTY_UNSEEDED;
			}
		}
		if (LedgerContainers.changesOnlyByTransfer(containerId))
		{
			return LedgerEvent.FLAG_COUNTERPARTY_UNTRACKED;
		}
		// Everything observable was observed and it still did not balance. That is a real
		// unexplained movement and it must stay unflagged so it counts as one.
		assert allCounterpartsSeeded(containerId, seeded)
			: "unflagged movement in " + containerId + " with an unseeded counterpart";
		return null;
	}

	/**
	 * Visible for the flag-discipline fixtures, and asserted above so the dev client (-ea) fails
	 * loudly if the two rules ever drift apart.
	 */
	static boolean allCounterpartsSeeded(int containerId, BaselineStatus seeded)
	{
		for (int counterpart : LedgerContainers.counterpartsOf(containerId))
		{
			if (!seeded.isSeeded(counterpart))
			{
				return false;
			}
		}
		return true;
	}

	/**
	 * The Grand Exchange can move items to and from the inventory or, when collecting to bank,
	 * the bank.
	 */
	private static boolean isGrandExchangeSide(int containerId)
	{
		return containerId == LedgerContainers.INVENTORY || containerId == LedgerContainers.BANK;
	}

	private LedgerEvent movement(int tick, long ts, MovementCategory category, int containerId,
								 int itemId, int qty, ActionContext ctx, List<String> flags)
	{
		return LedgerEvent.builder()
			.schemaVersion(LedgerEvent.SCHEMA_VERSION)
			.sessionId(sessionId)
			.ts(ts)
			.tick(tick)
			.type(EventType.ITEM_MOVEMENT)
			.category(category)
			.containerId(containerId)
			.itemId(itemId)
			.qty(qty)
			.actionContext(ctx.describe())
			.flags(flags.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(flags)))
			.build();
	}

	/**
	 * Converts a cumulative experience total into a delta.
	 * <p>
	 * {@code StatChanged.getXp()} is the lifetime total for the skill, not a change. The first
	 * reading has nothing to subtract from, and treating it as a delta would log the account's
	 * entire experience as a single gain. That first reading seeds the baseline and emits nothing.
	 * <p>
	 * Baselines deliberately survive a STATE_RESET. A lifetime total does not change when a region
	 * loads or when the same account logs back in, so clearing them there threw away the next
	 * gain in every skill, 27 times in one observed session. They live and die with the resolver,
	 * which is replaced when the session rotates to a different account — the only event that can
	 * genuinely invalidate them.
	 * <p>
	 * Experience cannot decrease in this game. A negative delta is therefore proof of a stale or
	 * wrong baseline and never a real event: the baseline is silently reseeded, nothing is
	 * emitted, and the occurrence is counted so it is visible rather than invisible.
	 *
	 * @return the XP_GAIN event, or null when this reading seeded a baseline, reseeded a bad one,
	 * or the total did not move.
	 */
	public LedgerEvent resolveXp(int tick, long ts, String skillName, int totalXp)
	{
		Integer baseline = xpBaselines.put(skillName, totalXp);
		if (baseline == null)
		{
			return null;
		}
		int delta = totalXp - baseline;
		if (delta < 0)
		{
			// The map already holds the new total, so the baseline is repaired by this call.
			negativeXpReseeds++;
			return null;
		}
		if (delta == 0)
		{
			return null;
		}
		return LedgerEvent.builder()
			.schemaVersion(LedgerEvent.SCHEMA_VERSION)
			.sessionId(sessionId)
			.ts(ts)
			.tick(tick)
			.type(EventType.XP_GAIN)
			.skill(skillName)
			.xpDelta(delta)
			.build();
	}

	/**
	 * Records a death and opens the death window.
	 */
	public LedgerEvent recordDeath(int tick, long ts)
	{
		deathWindowEndTick = tick + deathWindowTicks;
		deathWindowExtensions = 0;
		return LedgerEvent.builder()
			.schemaVersion(LedgerEvent.SCHEMA_VERSION)
			.sessionId(sessionId)
			.ts(ts)
			.tick(tick)
			.type(EventType.PLAYER_DEATH)
			.flags(Collections.singletonList(LedgerEvent.FLAG_DEATH_WINDOW))
			.build();
	}

	/**
	 * Records that baselines were invalidated, and drops the state that only makes sense relative
	 * to those baselines.
	 * <p>
	 * A death does not end here. Dying triggers a respawn region load, so a state transition
	 * always follows a death — which means closing the window on a reset guarantees the window is
	 * shut before the wipe is ever observed, and DEATH_LOSS can never fire. The respawn load is
	 * part of the death sequence, not the end of it, so an open window is pushed back instead.
	 */
	public LedgerEvent recordStateReset(int tick, long ts, String reason)
	{
		boolean heldForDeath = noteTransition(tick);

		return LedgerEvent.builder()
			.schemaVersion(LedgerEvent.SCHEMA_VERSION)
			.sessionId(sessionId)
			.ts(ts)
			.tick(tick)
			.type(EventType.STATE_RESET)
			.actionContext(reason)
			.flags(heldForDeath
				? Collections.singletonList(LedgerEvent.FLAG_DEATH_BASELINE_HELD)
				: Collections.emptyList())
			.build();
	}

	/**
	 * Tells the resolver a state transition happened, without treating it as a reset.
	 * <p>
	 * Used both by {@link #recordStateReset} and by a region load that keeps its baselines: a
	 * death still has to survive either one, and the respawn load is the transition it has to
	 * survive.
	 *
	 * @return true if an open death window was pushed back rather than closed.
	 */
	public boolean noteTransition(int tick)
	{
		if (isInDeathWindow(tick) && deathWindowExtensions < MAX_DEATH_WINDOW_EXTENSIONS)
		{
			deathWindowEndTick = tick + deathWindowTicks;
			deathWindowExtensions++;
			return true;
		}
		deathWindowEndTick = NO_DEATH;
		deathWindowExtensions = 0;
		return false;
	}

	/**
	 * Deltas computed against a baseline that turned out to be wrong. Should stay at zero; a
	 * non-zero value means something is handing out stale experience totals.
	 */
	public int getNegativeXpReseeds()
	{
		return negativeXpReseeds;
	}

	/**
	 * True while a death is still being resolved. The caller uses this to decide whether to keep
	 * the carried containers' baselines across a state transition.
	 */
	public boolean isInDeathWindow(int tick)
	{
		return deathWindowEndTick != NO_DEATH && tick <= deathWindowEndTick;
	}

	/**
	 * Visible for the debug panel.
	 */
	public int getTrackedSkillCount()
	{
		return xpBaselines.size();
	}

	private ActionContext freshContext(int tick, ActionContext context)
	{
		if (context == null || context.isEmpty())
		{
			return ActionContext.EMPTY;
		}
		int age = tick - context.getTick();
		if (age < 0 || age > actionContextTicks)
		{
			return ActionContext.EMPTY;
		}
		return context;
	}

	/**
	 * Fixture convenience.
	 */
	public List<LedgerEvent> resolveTick(int tick, long ts, ActionContext context,
										 BaselineStatus baselines, ContainerDiffer.Delta... deltas)
	{
		return resolveTick(tick, ts, Arrays.asList(deltas), context, baselines);
	}
}
