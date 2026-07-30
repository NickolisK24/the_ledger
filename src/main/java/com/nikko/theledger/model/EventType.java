package com.nikko.theledger.model;

/**
 * The complete set of event kinds the Phase 1 spine can write.
 * <p>
 * New members must only ever be appended. Readers of older files must keep working, so a
 * value is never renamed or removed once it has been written to disk.
 */
public enum EventType
{
	/**
	 * First line of every session file. Carries the session header fields.
	 */
	SESSION_START,
	/**
	 * Written when the plugin shuts down cleanly or rotates to a new account.
	 * Its absence means the client died without warning.
	 */
	SESSION_END,
	/**
	 * A signed quantity change for one canonical item in one container.
	 */
	ITEM_MOVEMENT,
	/**
	 * A positive experience delta for one skill.
	 */
	XP_GAIN,
	/**
	 * The local player died. Opens the death window.
	 */
	PLAYER_DEATH,
	/**
	 * Every container baseline was invalidated. Nothing before this line can be diffed
	 * against anything after it.
	 */
	STATE_RESET,
	/**
	 * Events were produced but not written, because the write queue was full or the disk
	 * failed. Everything after this line in the session is incomplete.
	 * <p>
	 * A counter in a debug panel is invisible to anything replaying the file later, and a gap
	 * in kill counts silently corrupts every rate and dry-streak figure built on it. The log
	 * has to be self-describing about its own gaps, so the gap is written into the stream
	 * exactly as {@link #STATE_RESET} writes the baseline gap. Exactly one is emitted per
	 * session, on the first drop; the total is recorded on {@link #SESSION_END}.
	 */
	DATA_LOSS
}
