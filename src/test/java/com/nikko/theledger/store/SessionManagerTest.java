package com.nikko.theledger.store;

import com.nikko.theledger.model.LedgerEvent;
import com.nikko.theledger.model.SessionHeader;
import java.io.File;
import java.io.IOException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SessionManagerTest
{
	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	private static final long ACCOUNT_A = 111_222_333_444L;
	private static final long ACCOUNT_B = 555_666_777_888L;
	private static final long TS = 1_700_000_000_000L;

	@Test
	public void startCreatesTheLedgerSubdirectoryUnderTheGivenBaseDir() throws IOException
	{
		SessionManager manager = new SessionManager(tmp.getRoot());
		manager.start(ACCOUNT_A, "MEMBERS", TS, 0);

		File dir = new File(tmp.getRoot(), SessionManager.DIR_NAME);
		assertTrue(dir.isDirectory());
		assertEquals(dir, manager.getDirectory());
		assertEquals(dir, manager.getSessionFile().getParentFile());
	}

	@Test
	public void headerCarriesTheSchemaVersionSessionIdAccountHashAndWorldType() throws IOException
	{
		SessionManager manager = new SessionManager(tmp.getRoot());
		SessionHeader header = manager.start(ACCOUNT_A, "MEMBERS|SEASONAL", TS, 1234);

		assertEquals(LedgerEvent.SCHEMA_VERSION, header.getSchemaVersion());
		assertEquals(ACCOUNT_A, header.getAccountHash());
		assertEquals("MEMBERS|SEASONAL", header.getWorldType());
		assertEquals(TS, header.getTs());
		assertEquals(1234, header.getTick());
		assertEquals(36, header.getSessionId().length());
		assertTrue(header.getFlags().isEmpty());
	}

	/**
	 * The file is not created by start — the writer opens it lazily off the client thread.
	 */
	@Test
	public void startDoesNotCreateTheFile() throws IOException
	{
		SessionManager manager = new SessionManager(tmp.getRoot());
		manager.start(ACCOUNT_A, "MEMBERS", TS, 0);
		assertFalse(manager.getSessionFile().exists());
	}

	@Test
	public void fileNameIsTimestampedAndCarriesPartOfTheSessionId() throws IOException
	{
		SessionManager manager = new SessionManager(tmp.getRoot());
		SessionHeader header = manager.start(ACCOUNT_A, "MEMBERS", TS, 0);

		String name = manager.getSessionFile().getName();
		assertTrue(name, name.startsWith("session-"));
		assertTrue(name, name.endsWith(".jsonl"));
		assertTrue(name, name.contains(header.getSessionId().substring(0, 8)));
	}

	@Test
	public void everySessionGetsItsOwnIdAndItsOwnFile() throws IOException
	{
		SessionManager manager = new SessionManager(tmp.getRoot());
		SessionHeader first = manager.start(ACCOUNT_A, "MEMBERS", TS, 0);
		File firstFile = manager.getSessionFile();
		manager.end();
		SessionHeader second = manager.start(ACCOUNT_A, "MEMBERS", TS, 0);

		assertNotEquals(first.getSessionId(), second.getSessionId());
		assertNotEquals(firstFile, manager.getSessionFile());
	}

	/**
	 * A client can switch accounts without restarting. Appending one account's movements to
	 * another's log would corrupt both.
	 */
	@Test
	public void accountSwitchRequiresRotation() throws IOException
	{
		SessionManager manager = new SessionManager(tmp.getRoot());
		manager.start(ACCOUNT_A, "MEMBERS", TS, 0);

		assertFalse(manager.needsRotation(ACCOUNT_A));
		assertTrue(manager.needsRotation(ACCOUNT_B));
	}

	@Test
	public void rotationOpensAFreshFileForTheNewAccount() throws IOException
	{
		SessionManager manager = new SessionManager(tmp.getRoot());
		SessionHeader first = manager.start(ACCOUNT_A, "MEMBERS", TS, 0);
		File firstFile = manager.getSessionFile();

		manager.end();
		SessionHeader second = manager.start(ACCOUNT_B, "MEMBERS", TS + 60_000, 500);

		assertNotEquals(firstFile, manager.getSessionFile());
		assertNotEquals(first.getSessionId(), second.getSessionId());
		assertEquals(ACCOUNT_B, second.getAccountHash());
	}

	@Test
	public void notActiveBeforeStartOrAfterEnd() throws IOException
	{
		SessionManager manager = new SessionManager(tmp.getRoot());
		assertFalse(manager.isActive());
		assertNull(manager.getSessionId());
		assertFalse(manager.needsRotation(ACCOUNT_A));

		manager.start(ACCOUNT_A, "MEMBERS", TS, 0);
		assertTrue(manager.isActive());

		manager.end();
		assertFalse(manager.isActive());
		assertNull(manager.getHeader());
		assertNull(manager.getSessionFile());
	}

	@Test
	public void logoutWithoutAKnownAccountHashStillOpensASession() throws IOException
	{
		SessionManager manager = new SessionManager(tmp.getRoot());
		SessionHeader header = manager.start(-1L, "STANDARD", TS, 0);
		assertEquals(-1L, header.getAccountHash());
		assertTrue(manager.isActive());
	}
}
