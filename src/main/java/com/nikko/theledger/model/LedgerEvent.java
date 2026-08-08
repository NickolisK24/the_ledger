package com.nikko.theledger.model;

import java.util.Collections;
import java.util.List;
import lombok.Builder;
import lombok.Value;

/**
 * One line of the log. Immutable.
 * <p>
 * Field declaration order is the on-disk JSON key order and is part of the schema contract:
 * the round-trip test asserts byte-level equality, so reordering these fields changes the
 * bytes written for identical data. Nullable fields are omitted from the JSON entirely
 * rather than written as {@code null}; {@code flags} is always written, even when empty.
 * <p>
 * Deliberately a Lombok {@code @Value} class and not a record — the build targets Java 11.
 */
@Value
@Builder
public class LedgerEvent
{
	/**
	 * Bumped whenever the meaning or shape of any field changes. Phase 2 will extend the
	 * schema; readers must keep loading version 1 files.
	 */
	public static final int SCHEMA_VERSION = 1;

	// ---- Audit flags. Every inference the spine makes leaves one of these behind. ----

	/**
	 * An inventory-only decrease attributed to a deposit box, whose destination container
	 * never updated. The items were not lost.
	 */
	public static final String FLAG_INFERRED_DEPOSIT_BOX = "INFERRED_DEPOSIT_BOX";
	/**
	 * An inventory-only movement attributed to the Grand Exchange. Offer placement,
	 * offer collection and collection-box withdrawal all move items with no container
	 * on the other side.
	 */
	public static final String FLAG_INFERRED_GRAND_EXCHANGE = "INFERRED_GRAND_EXCHANGE";
	/**
	 * A transfer was inferred while the bank baseline was still unknown, because the bank
	 * interface had not been opened this session. The other leg could not be confirmed.
	 */
	public static final String FLAG_UNKNOWN_BANK_BASELINE = "UNKNOWN_BANK_BASELINE";
	/**
	 * Resolved inside the death window opened by a PLAYER_DEATH event.
	 */
	public static final String FLAG_DEATH_WINDOW = "DEATH_WINDOW";
	/**
	 * A container this movement could plausibly have exchanged with had no baseline at the time,
	 * so the other leg could not have appeared even if it happened. Accompanies UNVERIFIED.
	 */
	public static final String FLAG_COUNTERPARTY_UNSEEDED = "COUNTERPARTY_UNSEEDED";
	/**
	 * Both legs of this transfer were proven with the help of a container being observed for the
	 * first time, with no prior baseline of its own.
	 * <p>
	 * Provenance, not economics: the category is still TRANSFER and the quantity is still exact.
	 * It records that one side of the match came from a first observation rather than from a
	 * measured change, so anything auditing the log can tell corroborated movement apart from
	 * movement seen from both sides.
	 */
	public static final String FLAG_FIRST_OBSERVATION_RECONCILED = "FIRST_OBSERVATION_RECONCILED";
	/**
	 * A movement in a container that can only change by transfer, with no counterpart leg
	 * anywhere in the tick. The items came from or went to storage the spine does not track — a
	 * rune pouch, a looting bag, a seed vault. Accompanies UNVERIFIED.
	 */
	public static final String FLAG_COUNTERPARTY_UNTRACKED = "COUNTERPARTY_UNTRACKED";
	/**
	 * The death window was still open when a state transition arrived, so the carried containers
	 * kept their baselines instead of being reseeded. Without this the respawn region load
	 * absorbs the entire wipe and no DEATH_LOSS is ever recorded.
	 */
	public static final String FLAG_DEATH_BASELINE_HELD = "DEATH_BASELINE_HELD";
	/**
	 * A death was recorded and could not be reconciled: the lifecycle was interrupted, or no
	 * carried-container evidence arrived before the safety budget expired.
	 * <p>
	 * Accompanies a DATA_LOSS, because that is exactly what it is — the log knows economic events
	 * occurred and cannot say what they were. Anything computing over the session must exclude it
	 * rather than treat the gap as nothing having happened.
	 */
	public static final String FLAG_DEATH_RECONCILE_FAILED = "DEATH_RECONCILE_FAILED";

	int schemaVersion;
	String sessionId;
	long ts;
	int tick;
	EventType type;
	/**
	 * Null for everything that is not an ITEM_MOVEMENT.
	 */
	MovementCategory category;
	/**
	 * Null for events not scoped to a container.
	 */
	Integer containerId;
	/**
	 * Canonical item id: notes collapsed onto the unnoted variant, placeholders excluded.
	 */
	Integer itemId;
	/**
	 * Signed. Positive is an increase in that container.
	 */
	Integer qty;
	String skill;
	Integer xpDelta;
	/**
	 * Total events lost this session. Set on SESSION_END only, so a consumer can detect a
	 * compromised session and exclude it rather than computing over a hole. Null everywhere
	 * else, and therefore absent from every other line.
	 */
	Integer droppedEvents;
	String actionContext;
	@Builder.Default
	List<String> flags = Collections.emptyList();

	public boolean hasFlag(String flag)
	{
		return flags != null && flags.contains(flag);
	}
}
