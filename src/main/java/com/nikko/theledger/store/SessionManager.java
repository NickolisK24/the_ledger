package com.nikko.theledger.store;

import com.nikko.theledger.model.LedgerEvent;
import com.nikko.theledger.model.SessionHeader;
import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.UUID;

/**
 * Owns session identity, the header, and the file each session writes to.
 * <p>
 * Takes its base directory as a constructor parameter rather than reaching for RuneLite's:
 * the plugin passes {@code RuneLite.RUNELITE_DIR} and the tests pass a temporary directory,
 * which keeps this class free of client types and keeps its own test honest.
 */
public final class SessionManager
{
	/**
	 * Subdirectory of the RuneLite data directory that holds every session file.
	 */
	public static final String DIR_NAME = "the_ledger";

	private static final DateTimeFormatter FILE_STAMP =
		DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

	private final File baseDir;

	private SessionHeader header;
	private File sessionFile;

	public SessionManager(File baseDir)
	{
		this.baseDir = baseDir;
	}

	public File getDirectory()
	{
		return new File(baseDir, DIR_NAME);
	}

	/**
	 * Opens a new session. Creates the directory if needed but does not create the file — the
	 * writer opens it lazily off the client thread.
	 *
	 * @param accountHash {@code client.getAccountHash()}. The only account identifier recorded.
	 * @param worldType   pipe-joined world type names.
	 */
	public SessionHeader start(long accountHash, String worldType, long ts, int tick) throws IOException
	{
		File dir = getDirectory();
		if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory())
		{
			throw new IOException("could not create " + dir);
		}

		String sessionId = UUID.randomUUID().toString();
		String stamp = FILE_STAMP.format(Instant.ofEpochMilli(ts));
		sessionFile = new File(dir, "session-" + stamp + "-" + sessionId.substring(0, 8) + ".jsonl");

		header = SessionHeader.builder()
			.schemaVersion(LedgerEvent.SCHEMA_VERSION)
			.sessionId(sessionId)
			.ts(ts)
			.tick(tick)
			.accountHash(accountHash)
			.worldType(worldType)
			.flags(Collections.emptyList())
			.build();
		return header;
	}

	public void end()
	{
		header = null;
		sessionFile = null;
	}

	public boolean isActive()
	{
		return header != null;
	}

	public SessionHeader getHeader()
	{
		return header;
	}

	public File getSessionFile()
	{
		return sessionFile;
	}

	public String getSessionId()
	{
		return header == null ? null : header.getSessionId();
	}

	/**
	 * True when the active session belongs to a different account than the one now logged in.
	 * <p>
	 * A client can switch accounts without restarting, and appending one account's movements to
	 * another's log would corrupt both. A changed account hash means: end the session, open a
	 * new file.
	 */
	public boolean needsRotation(long accountHash)
	{
		return header != null && header.getAccountHash() != accountHash;
	}
}
