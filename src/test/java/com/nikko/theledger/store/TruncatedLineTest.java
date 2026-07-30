package com.nikko.theledger.store;

import com.nikko.theledger.model.EventType;
import com.nikko.theledger.model.LedgerEvent;
import com.nikko.theledger.model.MovementCategory;
import com.nikko.theledger.model.SessionHeader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * The client can be killed mid-write, so the last line of a session file may be half-written.
 * Losing that one event is acceptable; losing the session is not.
 */
public class TruncatedLineTest
{
	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	private static final String SESSION = "truncation-session";

	@Test
	public void truncatedFinalLineIsSkippedAndEveryPrecedingEventStillLoads() throws IOException
	{
		File file = writeSession(20);

		// Simulate the process dying mid-line.
		String half = JsonlEventWriter.serialize(event(999)).substring(0, 40);
		Files.write(file.toPath(), (half).getBytes(StandardCharsets.UTF_8),
			StandardOpenOption.APPEND);

		JsonlEventReader.ReplaySession replay = JsonlEventReader.read(file);

		assertNotNull(replay.getHeader());
		assertEquals(20, replay.getEvents().size());
		assertEquals(1, replay.getMalformedLines());
		assertEquals(0, replay.getEvents().get(0).getTick());
		assertEquals(19, replay.getEvents().get(19).getTick());
	}

	@Test
	public void truncationThatHappensToEndInABraceIsStillRejected() throws IOException
	{
		File file = writeSession(5);
		// A line cut off after a nested value: syntactically closed, semantically incomplete.
		Files.write(file.toPath(),
			"{\"schemaVersion\":1,\"sessionId\":\"x\"}\n".getBytes(StandardCharsets.UTF_8),
			StandardOpenOption.APPEND);

		JsonlEventReader.ReplaySession replay = JsonlEventReader.read(file);

		assertEquals(5, replay.getEvents().size());
		assertEquals(1, replay.getMalformedLines());
	}

	@Test
	public void malformedLineInTheMiddleDoesNotStopTheRead() throws IOException
	{
		File file = writeSession(10);
		List<String> lines = new ArrayList<>(Files.readAllLines(file.toPath(), StandardCharsets.UTF_8));
		lines.add(5, "{\"schemaVersion\":1,\"sessionId\":\"x\",\"ts\":1,\"tick\":2,\"type\":\"ITEM");
		Files.write(file.toPath(), String.join("\n", lines).concat("\n").getBytes(StandardCharsets.UTF_8));

		JsonlEventReader.ReplaySession replay = JsonlEventReader.read(file);

		assertEquals(10, replay.getEvents().size());
		assertEquals(1, replay.getMalformedLines());
	}

	@Test
	public void garbageAndBlankLinesAreTolerated() throws IOException
	{
		File file = writeSession(3);
		Files.write(file.toPath(),
			"\n\nnot json at all\n[1,2,3]\n{}\n".getBytes(StandardCharsets.UTF_8),
			StandardOpenOption.APPEND);

		JsonlEventReader.ReplaySession replay = JsonlEventReader.read(file);

		assertEquals(3, replay.getEvents().size());
		// Blank lines are not malformed; the three non-blank junk lines are.
		assertEquals(3, replay.getMalformedLines());
	}

	@Test
	public void aFileWhoseHeaderIsDamagedStillYieldsItsEvents() throws IOException
	{
		File file = writeSession(4);
		List<String> lines = new ArrayList<>(Files.readAllLines(file.toPath(), StandardCharsets.UTF_8));
		lines.set(0, "{\"schemaVersion\":1,\"sessionId\":\"x\",\"ts\":1,\"tick\":0,\"type\":\"SESS");
		Files.write(file.toPath(), String.join("\n", lines).concat("\n").getBytes(StandardCharsets.UTF_8));

		JsonlEventReader.ReplaySession replay = JsonlEventReader.read(file);

		assertNull(replay.getHeader());
		assertEquals(4, replay.getEvents().size());
		assertEquals(1, replay.getMalformedLines());
	}

	/**
	 * An event type this build has never heard of is skipped rather than crashing the read, so a
	 * Phase 2 file can be opened by a Phase 1 reader.
	 */
	@Test
	public void unknownEventTypeFromANewerSchemaIsSkipped() throws IOException
	{
		File file = writeSession(2);
		Files.write(file.toPath(),
			("{\"schemaVersion\":2,\"sessionId\":\"x\",\"ts\":1,\"tick\":9,"
				+ "\"type\":\"SOMETHING_FROM_PHASE_2\",\"flags\":[]}\n").getBytes(StandardCharsets.UTF_8),
			StandardOpenOption.APPEND);

		JsonlEventReader.ReplaySession replay = JsonlEventReader.read(file);

		assertEquals(2, replay.getEvents().size());
		assertEquals(1, replay.getMalformedLines());
	}

	/**
	 * Unknown extra fields from a later schema do not prevent an event from loading.
	 */
	@Test
	public void unknownExtraFieldsAreIgnored() throws IOException
	{
		File file = writeSession(1);
		Files.write(file.toPath(),
			("{\"schemaVersion\":2,\"sessionId\":\"x\",\"ts\":1,\"tick\":9,"
				+ "\"type\":\"ITEM_MOVEMENT\",\"category\":\"TRANSFER\",\"containerId\":93,"
				+ "\"itemId\":995,\"qty\":-1,\"unitPrice\":1234,\"flags\":[]}\n")
				.getBytes(StandardCharsets.UTF_8),
			StandardOpenOption.APPEND);

		JsonlEventReader.ReplaySession replay = JsonlEventReader.read(file);

		assertEquals(2, replay.getEvents().size());
		assertEquals(0, replay.getMalformedLines());
		assertEquals(2, replay.getEvents().get(1).getSchemaVersion());
	}

	private File writeSession(int eventCount) throws IOException
	{
		File file = new File(tmp.getRoot(), "truncated.jsonl");
		SessionHeader header = SessionHeader.builder()
			.schemaVersion(LedgerEvent.SCHEMA_VERSION)
			.sessionId(SESSION)
			.ts(1_700_000_000_000L)
			.tick(0)
			.accountHash(42L)
			.worldType("STANDARD")
			.flags(Collections.emptyList())
			.build();
		try (JsonlEventWriter writer = new JsonlEventWriter(file, header))
		{
			for (int i = 0; i < eventCount; i++)
			{
				writer.enqueue(event(i));
			}
			writer.drain();
		}
		return file;
	}

	private static LedgerEvent event(int tick)
	{
		return LedgerEvent.builder()
			.schemaVersion(LedgerEvent.SCHEMA_VERSION)
			.sessionId(SESSION)
			.ts(1_700_000_000_000L + tick)
			.tick(tick)
			.type(EventType.ITEM_MOVEMENT)
			.category(MovementCategory.UNCLASSIFIED_GAIN)
			.containerId(93)
			.itemId(995)
			.qty(tick + 1)
			.build();
	}
}
