package com.nikko.theledger.model;

import java.util.Collections;
import java.util.List;
import lombok.Builder;
import lombok.Value;

/**
 * The first line of every session file, serialised as a SESSION_START line carrying two
 * fields ordinary events do not have: {@code accountHash} and {@code worldType}.
 * <p>
 * The account hash is the only account identifier ever written. The account name, the
 * display name and the login are never recorded, because these files may be shared or
 * published later.
 * <p>
 * Field declaration order is the on-disk JSON key order — see {@link LedgerEvent}.
 */
@Value
@Builder
public class SessionHeader
{
	int schemaVersion;
	String sessionId;
	long ts;
	int tick;
	/**
	 * {@code client.getAccountHash()}. -1 when not logged in to a Jagex account.
	 */
	long accountHash;
	/**
	 * Pipe-joined {@code WorldType} names, or "STANDARD" when the set is empty.
	 */
	String worldType;
	@Builder.Default
	List<String> flags = Collections.emptyList();
}
