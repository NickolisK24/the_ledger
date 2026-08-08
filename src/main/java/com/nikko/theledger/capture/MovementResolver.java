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
import java.util.TreeSet;

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
 *     <li><b>Then invisible destinations,</b> identified by the interface the click landed on
 *     and always flagged so the inference stays auditable. Structural evidence only: no rule
 *     here reads a menu option, a target name or any other display string.</li>
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
	 * Emergency guard on death reconciliation, in ticks from the death itself.
	 * <p>
	 * This is <b>not</b> "the point after which the death is probably over". Death ends on
	 * evidence, not on a clock. This is only the point at which reconciliation has demonstrably
	 * failed to produce evidence and must fail closed rather than quietly resume clean accounting.
	 * Generous on purpose: a live death took seven ticks to reach its respawn transition, and the
	 * previous five-tick model expired before it.
	 */
	public static final int DEATH_RECONCILE_BUDGET_TICKS = 25;

	private static final int NO_DEATH = Integer.MIN_VALUE;

	/**
	 * Where a death has got to.
	 * <p>
	 * A death is a lifecycle sequence with a variable-duration middle, not a fixed interval. The
	 * old model started a five-tick window at {@code ActorDeath}; a live death reached its respawn
	 * transition at tick 269 having died at 262, so the window had already expired, the carried
	 * baselines were reseeded, the wipe was absorbed and the recovered items later surfaced as
	 * unflagged gains. Widening the number would have moved the cliff, not removed it.
	 */
	public enum DeathPhase
	{
		IDLE,
		/**
		 * The player died and the respawn lifecycle has not arrived yet.
		 */
		AWAITING_RESPAWN,
		/**
		 * Respawn happened. Waiting for both carried containers to report so the wipe can be
		 * measured against what was actually being carried.
		 */
		AWAITING_WIPE
	}

	/**
	 * Read access to the carried containers, supplied by the caller because the resolver does not
	 * own the snapshots. Client-free by construction.
	 */
	public interface CarriedStateSource
	{
		ContainerSnapshot snapshotOf(int containerId);
	}

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
	private final int actionContextTicks;

	private final Map<String, Integer> xpBaselines = new HashMap<>();
	private int negativeXpReseeds;

	private DeathPhase deathPhase = DeathPhase.IDLE;
	private int deathTick = NO_DEATH;
	/**
	 * What was being carried at the instant of death, frozen. Ordinary snapshot churn afterwards
	 * must not be able to redefine it, or the wipe gets measured against the wrong thing.
	 */
	private Map<Integer, Map<Integer, Integer>> deathBaseline = Collections.emptyMap();
	private final Set<Integer> carriedReportedSinceRespawn = new TreeSet<>();

	public MovementResolver(String sessionId)
	{
		this(sessionId, DEFAULT_ACTION_CONTEXT_TICKS);
	}

	/**
	 * @param deathWindowTicks retained for call compatibility and deliberately ignored. Death ends
	 *                         on lifecycle evidence, not after a fixed number of ticks; see
	 *                         {@link DeathPhase}.
	 */
	@Deprecated
	public MovementResolver(String sessionId, int deathWindowTicks, int actionContextTicks)
	{
		this(sessionId, actionContextTicks);
	}

	public MovementResolver(String sessionId, int actionContextTicks)
	{
		this.sessionId = sessionId;
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
			Collections.emptySet(), context, baselines);
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
	 * @param deathOwnedContainers carried containers whose changes were captured while a death was
	 *                           unresolved. Their deltas are dropped here entirely, in both
	 *                           directions, because the death reconciler is the authority on what
	 *                           a death did to the carried state and classifying them as well
	 *                           would count the same movement twice.
	 */
	public List<LedgerEvent> resolveTick(int tick, long ts, List<ContainerDiffer.Delta> deltas,
										 Map<Integer, ContainerSnapshot> firstObservations,
										 Set<Integer> becameKnownThisTick,
										 Set<Integer> deathOwnedContainers,
										 ActionContext context, BaselineStatus baselines)
	{
		BaselineStatus supplied = baselines == null ? NOTHING_SEEDED : baselines;
		Set<Integer> late = becameKnownThisTick == null ? Collections.emptySet() : becameKnownThisTick;
		BaselineStatus seeded = containerId -> supplied.isSeeded(containerId) && !late.contains(containerId);
		Map<Integer, ContainerSnapshot> observed =
			firstObservations == null ? Collections.emptyMap() : firstObservations;
		// Consumed as matches are made, so one first-observed item cannot corroborate two losses.
		Map<Integer, Map<Integer, Integer>> available = availableFromFirstObservations(observed);
		Set<Integer> deathOwned =
			deathOwnedContainers == null ? Collections.emptySet() : deathOwnedContainers;
		List<LedgerEvent> events = new ArrayList<>();
		if (deltas == null || deltas.isEmpty())
		{
			return events;
		}

		ActionContext ctx = freshContext(tick, context);

		// Group by canonical item id so both legs of a movement are considered together.
		Map<Integer, List<ContainerDiffer.Delta>> byItem = new TreeMap<>();
		for (ContainerDiffer.Delta d : deltas)
		{
			if (d.getDelta() == 0)
			{
				continue;
			}
			// Dropped before anything looks at them, so a death-owned change cannot become a
			// residual, a netted transfer leg, or anything else. Ownership was decided when the
			// change was captured and does not lapse because the death closed earlier in this same
			// tick - which is exactly how a live death got counted twice.
			if (deathOwned.contains(d.getContainerId()))
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
				events.add(classifyLoss(tick, ts, loss[0], itemId, loss[1], ctx, seeded));
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
									 ActionContext ctx, BaselineStatus seeded)
	{
		boolean bankBaselineKnown = seeded.isSeeded(LedgerContainers.BANK);
		// Destinations that never update a container of their own. Always flagged, and identified
		// by the interface the click landed on rather than by what the menu said - a deposit box is
		// group 192 whatever language the client renders it in.
		if (LedgerContainers.isCarried(containerId) && ctx.isDepositBoxInterface())
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
	 * Records a death and freezes what was being carried at that instant.
	 *
	 * @param carried read access to the carried containers as they stand right now
	 */
	public LedgerEvent recordDeath(int tick, long ts, CarriedStateSource carried)
	{
		deathPhase = DeathPhase.AWAITING_RESPAWN;
		deathTick = tick;
		carriedReportedSinceRespawn.clear();
		deathBaseline = freezeCarried(carried);
		return LedgerEvent.builder()
			.schemaVersion(LedgerEvent.SCHEMA_VERSION)
			.sessionId(sessionId)
			.ts(ts)
			.tick(tick)
			.type(EventType.PLAYER_DEATH)
			.flags(Collections.singletonList(LedgerEvent.FLAG_DEATH_WINDOW))
			.build();
	}

	private static Map<Integer, Map<Integer, Integer>> freezeCarried(CarriedStateSource carried)
	{
		Map<Integer, Map<Integer, Integer>> frozen = new TreeMap<>();
		if (carried == null)
		{
			return frozen;
		}
		for (int containerId : LedgerContainers.TRACKED)
		{
			if (!LedgerContainers.isCarried(containerId))
			{
				continue;
			}
			ContainerSnapshot snapshot = carried.snapshotOf(containerId);
			if (snapshot != null && snapshot.isKnown())
			{
				frozen.put(containerId, new TreeMap<>(snapshot.getQuantities()));
			}
		}
		return frozen;
	}

	public DeathPhase getDeathPhase()
	{
		return deathPhase;
	}

	/**
	 * True while a death is still being resolved. The caller uses this to hold the carried
	 * containers' baselines across the respawn transition.
	 */
	public boolean isDeathPending()
	{
		return deathPhase != DeathPhase.IDLE;
	}

	/**
	 * The respawn lifecycle arrived. Only a region load qualifies: a death teleports the player,
	 * and that is the transition the death has to survive.
	 *
	 * @return true if this was consumed as the respawn step.
	 */
	public boolean noteRespawnTransition(int tick)
	{
		if (deathPhase == DeathPhase.AWAITING_RESPAWN)
		{
			deathPhase = DeathPhase.AWAITING_WIPE;
			carriedReportedSinceRespawn.clear();
			return true;
		}
		// A second load during the same death is part of the same sequence and changes nothing.
		return deathPhase == DeathPhase.AWAITING_WIPE;
	}

	/**
	 * A carried container reported after the respawn. Evidence, not a decision.
	 */
	public void noteCarriedReported(int containerId)
	{
		if (deathPhase == DeathPhase.AWAITING_WIPE && LedgerContainers.isCarried(containerId))
		{
			carriedReportedSinceRespawn.add(containerId);
		}
	}

	/**
	 * Reconciles the death if there is now enough evidence, and otherwise fails closed if the
	 * safety budget has run out.
	 * <p>
	 * Loss is measured on the <b>combined</b> carried state rather than per container, because an
	 * item that was worn and is now in the inventory was not lost. Only what disappeared from
	 * inventory and equipment together counts, and the quantity is what actually went, not what
	 * happened to be carried.
	 *
	 * @return the events to log: DEATH_LOSS lines on success, a DATA_LOSS gap marker on failure,
	 * empty while still waiting.
	 */
	public List<LedgerEvent> tryResolveDeath(int tick, long ts, CarriedStateSource carried)
	{
		if (deathPhase == DeathPhase.IDLE)
		{
			return Collections.emptyList();
		}

		if (deathPhase == DeathPhase.AWAITING_WIPE && hasSufficientWipeEvidence(carried))
		{
			List<LedgerEvent> losses = reconcileDeath(tick, ts, carried);
			closeDeath();
			return losses;
		}

		if (tick - deathTick > DEATH_RECONCILE_BUDGET_TICKS)
		{
			return Collections.singletonList(failDeathClosed(tick, ts,
				deathPhase == DeathPhase.AWAITING_RESPAWN
					? "no respawn transition within budget"
					: "no carried container evidence within budget"));
		}

		return Collections.emptyList();
	}

	/**
	 * Both carried containers must have reported since the respawn AND currently hold a baseline.
	 * A container that is UNKNOWN again after the transition is not evidence of a wipe — it is
	 * absence of evidence, and manufacturing a wipe from it is exactly the mistake first
	 * observations exist to prevent.
	 */
	private boolean hasSufficientWipeEvidence(CarriedStateSource carried)
	{
		if (carried == null)
		{
			return false;
		}
		for (int containerId : LedgerContainers.TRACKED)
		{
			if (!LedgerContainers.isCarried(containerId))
			{
				continue;
			}
			if (!carriedReportedSinceRespawn.contains(containerId))
			{
				return false;
			}
			ContainerSnapshot now = carried.snapshotOf(containerId);
			if (now == null || !now.isKnown())
			{
				return false;
			}
		}
		return true;
	}

	private List<LedgerEvent> reconcileDeath(int tick, long ts, CarriedStateSource carried)
	{
		Map<Integer, Integer> before = combined(deathBaseline);
		Map<Integer, Integer> after = new TreeMap<>();
		for (Map.Entry<Integer, Map<Integer, Integer>> e : freezeCarried(carried).entrySet())
		{
			for (Map.Entry<Integer, Integer> q : e.getValue().entrySet())
			{
				after.merge(q.getKey(), q.getValue(), Integer::sum);
			}
		}

		List<LedgerEvent> events = new ArrayList<>();
		for (Map.Entry<Integer, Integer> entry : before.entrySet())
		{
			int itemId = entry.getKey();
			int lost = entry.getValue() - after.getOrDefault(itemId, 0);
			if (lost <= 0)
			{
				// Still carried, wherever it ended up. Moving between the carried containers is
				// not a loss.
				continue;
			}

			// Attribute what disappeared to where it was at the moment of death, ascending by
			// container id so the split is deterministic.
			int remaining = lost;
			for (Map.Entry<Integer, Map<Integer, Integer>> container : deathBaseline.entrySet())
			{
				if (remaining <= 0)
				{
					break;
				}
				int held = container.getValue().getOrDefault(itemId, 0);
				int portion = Math.min(remaining, held);
				if (portion <= 0)
				{
					continue;
				}
				remaining -= portion;
				events.add(LedgerEvent.builder()
					.schemaVersion(LedgerEvent.SCHEMA_VERSION)
					.sessionId(sessionId)
					.ts(ts)
					.tick(tick)
					.type(EventType.ITEM_MOVEMENT)
					.category(MovementCategory.DEATH_LOSS)
					.containerId(container.getKey())
					.itemId(itemId)
					.qty(-portion)
					.flags(Collections.singletonList(LedgerEvent.FLAG_DEATH_WINDOW))
					.build());
			}
		}
		return events;
	}

	private static Map<Integer, Integer> combined(Map<Integer, Map<Integer, Integer>> carried)
	{
		Map<Integer, Integer> total = new TreeMap<>();
		for (Map<Integer, Integer> container : carried.values())
		{
			for (Map.Entry<Integer, Integer> q : container.entrySet())
			{
				total.merge(q.getKey(), q.getValue(), Integer::sum);
			}
		}
		return total;
	}

	/**
	 * The lifecycle was interrupted, or no evidence arrived in time.
	 * <p>
	 * Reported as DATA_LOSS because that is precisely what it is: the log knows economic events
	 * occurred and cannot say what they were. Resuming clean accounting as though nothing happened
	 * would be the one outcome worse than admitting the hole.
	 */
	public LedgerEvent failDeathClosed(int tick, long ts, String reason)
	{
		closeDeath();
		return LedgerEvent.builder()
			.schemaVersion(LedgerEvent.SCHEMA_VERSION)
			.sessionId(sessionId)
			.ts(ts)
			.tick(tick)
			.type(EventType.DATA_LOSS)
			.actionContext("death reconciliation failed: " + reason)
			.flags(Collections.singletonList(LedgerEvent.FLAG_DEATH_RECONCILE_FAILED))
			.build();
	}

	private void closeDeath()
	{
		deathPhase = DeathPhase.IDLE;
		deathTick = NO_DEATH;
		deathBaseline = Collections.emptyMap();
		carriedReportedSinceRespawn.clear();
	}

	/**
	 * Records that baselines were invalidated.
	 * <p>
	 * Dying triggers a respawn region load, so a state transition always follows a death. The
	 * transition does not end the death — {@link #noteRespawnTransition} advances it and the
	 * carried baselines are held — so the reset simply records that it happened, flagged when it
	 * landed inside an unresolved death.
	 */
	public LedgerEvent recordStateReset(int tick, long ts, String reason)
	{
		boolean heldForDeath = isDeathPending();

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
	 * Deltas computed against a baseline that turned out to be wrong. Should stay at zero; a
	 * non-zero value means something is handing out stale experience totals.
	 */
	public int getNegativeXpReseeds()
	{
		return negativeXpReseeds;
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
