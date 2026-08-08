package com.nikko.theledger.model;

/**
 * Economic classification of an {@link EventType#ITEM_MOVEMENT}.
 * <p>
 * This is the <b>economic</b> axis only: what kind of value movement this was. How much the
 * spine could actually observe is a separate, orthogonal question answered by the flags on
 * {@link LedgerEvent} — a movement can be directionally a gain while its counterpart was
 * invisible. Keeping the two apart means a consumer can sum by category and filter by
 * confidence independently, instead of every category query having to special-case a member
 * that is not an economic kind.
 * <p>
 * The full enum is declared now so the on-disk schema is stable, but Phase 1 only ever
 * assigns {@link #TRANSFER}, {@link #DEATH_LOSS}, {@link #UNCLASSIFIED_GAIN} and
 * {@link #UNCLASSIFIED_LOSS}. The remaining members are reserved for Phase 2 and are
 * deliberately unreachable in this phase — see the assignment sites in
 * {@code MovementResolver}.
 */
public enum MovementCategory
{
	/**
	 * Moved between containers the player already owns. Zero economic weight.
	 * Banking is neither profit nor loss.
	 */
	TRANSFER,
	/**
	 * Removed from inventory or equipment inside the death window.
	 */
	DEATH_LOSS,
	/**
	 * An increase the spine cannot yet attribute to a cause.
	 */
	UNCLASSIFIED_GAIN,
	/**
	 * A decrease the spine cannot yet attribute to a cause.
	 */
	UNCLASSIFIED_LOSS,

	// ---- Reserved for Phase 2. Declared for schema stability, never assigned in Phase 1. ----

	/**
	 * Reserved for Phase 2. Item acquired as the output of an activity.
	 */
	REVENUE,
	/**
	 * Reserved for Phase 2. Food, potions, ammunition, runes.
	 */
	CONSUMABLE,
	/**
	 * Reserved for Phase 2. Charge consumed from a chargeable item.
	 */
	CHARGE,
	/**
	 * Reserved for Phase 2. Cost of repairing degradable gear.
	 */
	REPAIR,
	/**
	 * Reserved for Phase 2. Coins paid to reclaim items after a death.
	 */
	DEATH_FEE,
	/**
	 * Reserved for Phase 2. Teleport and travel costs.
	 */
	TRANSPORT
}
