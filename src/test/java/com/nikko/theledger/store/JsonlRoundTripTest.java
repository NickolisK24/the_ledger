package com.nikko.theledger.store;

import com.nikko.theledger.model.EventType;
import com.nikko.theledger.model.LedgerEvent;
import com.nikko.theledger.model.MovementCategory;
import com.nikko.theledger.model.SessionHeader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Replaying a session file must reproduce the event stream exactly. Everything downstream is
 * built on the log rather than on live state, so a lossy reader would quietly corrupt every
 * later phase.
 */
public class JsonlRoundTripTest
{
	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	private static final String SESSION = "3f2504e0-4f89-41d3-9a0c-0305e82c3301";

	@Test
	public void fullSessionRoundTripIsByteIdentical() throws IOException
	{
		SessionHeader header = header();
		List<LedgerEvent> events = sampleEvents(250);

		File file = write(header, events);

		JsonlEventReader.ReplaySession replay = JsonlEventReader.read(file);

		assertNotNull(replay.getHeader());
		assertEquals(0, replay.getMalformedLines());
		assertEquals(events.size(), replay.getEvents().size());
		assertEquals(events, replay.getEvents());
		assertEquals(header, replay.getHeader());

		// Re-serialise the reconstruction and compare the raw bytes.
		byte[] original = Files.readAllBytes(file.toPath());
		byte[] reconstructed = render(replay.getHeader(), replay.getEvents());
		assertArrayEquals(original, reconstructed);
	}

	@Test
	public void headerIsTheFirstLineAndCarriesTheAccountHashAndWorldType() throws IOException
	{
		SessionHeader header = header();
		File file = write(header, sampleEvents(3));

		List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
		assertEquals(4, lines.size());
		assertTrue(lines.get(0).contains("\"type\":\"SESSION_START\""));
		assertTrue(lines.get(0).contains("\"accountHash\":123456789012345"));
		assertTrue(lines.get(0).contains("\"worldType\":\"MEMBERS\""));
		assertTrue(lines.get(0).contains("\"schemaVersion\":1"));
	}

	/**
	 * The account name is never written. Only the hash, which cannot be reversed into a login.
	 */
	@Test
	public void noAccountNameAnywhereInTheFile() throws IOException
	{
		File file = write(header(), sampleEvents(20));
		String contents = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);

		assertTrue(!contents.contains("username"));
		assertTrue(!contents.contains("displayName"));
		assertTrue(!contents.contains("accountName"));
	}

	@Test
	public void nullFieldsAreOmittedRatherThanWrittenAsNull()
	{
		LedgerEvent xp = LedgerEvent.builder()
			.schemaVersion(LedgerEvent.SCHEMA_VERSION)
			.sessionId(SESSION)
			.ts(1_700_000_000_000L)
			.tick(500)
			.type(EventType.XP_GAIN)
			.skill("SLAYER")
			.xpDelta(412)
			.build();

		String line = JsonlEventWriter.serialize(xp);

		assertTrue(line, !line.contains("null"));
		assertTrue(line, !line.contains("category"));
		assertTrue(line, !line.contains("containerId"));
		// flags is always present so the shape of a line never varies.
		assertTrue(line, line.contains("\"flags\":[]"));
	}

	@Test
	public void keyOrderIsStable()
	{
		LedgerEvent movement = movement(1, MovementCategory.TRANSFER, 93, 995, -1000);
		String line = JsonlEventWriter.serialize(movement);

		assertEquals("{\"schemaVersion\":1,\"sessionId\":\"" + SESSION + "\",\"ts\":1700000000001,"
			+ "\"tick\":1,\"type\":\"ITEM_MOVEMENT\",\"category\":\"TRANSFER\",\"containerId\":93,"
			+ "\"itemId\":995,\"qty\":-1000,\"flags\":[]}", line);
	}

	@Test
	public void flagsSurviveTheRoundTrip() throws IOException
	{
		LedgerEvent flagged = LedgerEvent.builder()
			.schemaVersion(LedgerEvent.SCHEMA_VERSION)
			.sessionId(SESSION)
			.ts(1_700_000_000_000L)
			.tick(7)
			.type(EventType.ITEM_MOVEMENT)
			.category(MovementCategory.TRANSFER)
			.containerId(93)
			.itemId(995)
			.qty(-10_000_000)
			.actionContext("Confirm@465")
			.flags(Arrays.asList(LedgerEvent.FLAG_INFERRED_GRAND_EXCHANGE,
				LedgerEvent.FLAG_UNKNOWN_BANK_BASELINE))
			.build();

		File file = write(header(), Collections.singletonList(flagged));
		JsonlEventReader.ReplaySession replay = JsonlEventReader.read(file);

		assertEquals(1, replay.getEvents().size());
		LedgerEvent back = replay.getEvents().get(0);
		assertEquals(flagged, back);
		assertTrue(back.hasFlag(LedgerEvent.FLAG_INFERRED_GRAND_EXCHANGE));
		assertEquals("Confirm@465", back.getActionContext());
	}

	@Test
	public void reopeningAnExistingFileAppendsWithoutASecondHeader() throws IOException
	{
		SessionHeader header = header();
		File file = new File(tmp.getRoot(), "session.jsonl");

		try (JsonlEventWriter first = new JsonlEventWriter(file, header))
		{
			first.enqueue(movement(1, MovementCategory.TRANSFER, 93, 995, -5));
		}
		try (JsonlEventWriter second = new JsonlEventWriter(file, header))
		{
			second.enqueue(movement(2, MovementCategory.TRANSFER, 95, 995, 5));
		}

		List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
		assertEquals(3, lines.size());
		assertEquals(1, countHeaders(lines));
	}

	@Test
	public void queueOverflowIsCountedRatherThanBlockingOrGrowing()
	{
		File file = new File(tmp.getRoot(), "bounded.jsonl");
		JsonlEventWriter writer = new JsonlEventWriter(file, header(), 16);

		int accepted = 0;
		for (int i = 0; i < 100; i++)
		{
			if (writer.enqueue(movement(i, MovementCategory.TRANSFER, 93, 995, -1)))
			{
				accepted++;
			}
		}

		assertEquals(16, accepted);
		assertEquals(84, writer.getDroppedCount());
		assertEquals(16, writer.getQueueDepth());

		// 16 survivors plus the DATA_LOSS marker that records the other 84.
		assertEquals(17, writer.drain());
		assertEquals(0, writer.getQueueDepth());
		assertEquals(17, writer.getWrittenCount());
		writer.close();
	}

	@Test
	public void enqueueDoesNotTouchTheDiskAtAll()
	{
		File file = new File(tmp.getRoot(), "lazy.jsonl");
		JsonlEventWriter writer = new JsonlEventWriter(file, header());

		writer.enqueue(movement(1, MovementCategory.TRANSFER, 93, 995, -1));

		// The file is only created by drain, which runs on the executor, never the client thread.
		assertTrue("enqueue must not create the file", !file.exists());

		writer.drain();
		assertTrue(file.exists());
		writer.close();
	}

	// ---- helpers ----

	private File write(SessionHeader header, List<LedgerEvent> events) throws IOException
	{
		File file = new File(tmp.getRoot(), "session-" + header.getTs() + ".jsonl");
		try (JsonlEventWriter writer = new JsonlEventWriter(file, header, 8192))
		{
			for (LedgerEvent e : events)
			{
				assertTrue(writer.enqueue(e));
			}
			writer.drain();
		}
		return file;
	}

	private static byte[] render(SessionHeader header, List<LedgerEvent> events)
	{
		StringBuilder sb = new StringBuilder();
		sb.append(JsonlEventWriter.serialize(header)).append('\n');
		for (LedgerEvent e : events)
		{
			sb.append(JsonlEventWriter.serialize(e)).append('\n');
		}
		return sb.toString().getBytes(StandardCharsets.UTF_8);
	}

	private static int countHeaders(List<String> lines)
	{
		int n = 0;
		for (String line : lines)
		{
			if (line.contains("\"type\":\"SESSION_START\""))
			{
				n++;
			}
		}
		return n;
	}

	private static SessionHeader header()
	{
		return SessionHeader.builder()
			.schemaVersion(LedgerEvent.SCHEMA_VERSION)
			.sessionId(SESSION)
			.ts(1_700_000_000_000L)
			.tick(0)
			.accountHash(123_456_789_012_345L)
			.worldType("MEMBERS")
			.flags(Collections.emptyList())
			.build();
	}

	private static LedgerEvent movement(int tick, MovementCategory category, int containerId,
										int itemId, int qty)
	{
		return LedgerEvent.builder()
			.schemaVersion(LedgerEvent.SCHEMA_VERSION)
			.sessionId(SESSION)
			.ts(1_700_000_000_000L + tick)
			.tick(tick)
			.type(EventType.ITEM_MOVEMENT)
			.category(category)
			.containerId(containerId)
			.itemId(itemId)
			.qty(qty)
			.build();
	}

	/**
	 * A stream that exercises every event type, category and optional field.
	 */
	private static List<LedgerEvent> sampleEvents(int count)
	{
		MovementCategory[] categories = {
			MovementCategory.TRANSFER,
			MovementCategory.UNCLASSIFIED_GAIN,
			MovementCategory.UNCLASSIFIED_LOSS,
			MovementCategory.DEATH_LOSS,
		};
		List<LedgerEvent> events = new ArrayList<>(count);
		for (int i = 0; i < count; i++)
		{
			if (i % 17 == 5)
			{
				events.add(LedgerEvent.builder()
					.schemaVersion(LedgerEvent.SCHEMA_VERSION)
					.sessionId(SESSION)
					.ts(1_700_000_000_000L + i)
					.tick(i)
					.type(EventType.XP_GAIN)
					.skill("SLAYER")
					.xpDelta(i + 1)
					.build());
			}
			else if (i % 29 == 11)
			{
				events.add(LedgerEvent.builder()
					.schemaVersion(LedgerEvent.SCHEMA_VERSION)
					.sessionId(SESSION)
					.ts(1_700_000_000_000L + i)
					.tick(i)
					.type(EventType.PLAYER_DEATH)
					.flags(Collections.singletonList(LedgerEvent.FLAG_DEATH_WINDOW))
					.build());
			}
			else if (i % 41 == 3)
			{
				events.add(LedgerEvent.builder()
					.schemaVersion(LedgerEvent.SCHEMA_VERSION)
					.sessionId(SESSION)
					.ts(1_700_000_000_000L + i)
					.tick(i)
					.type(EventType.STATE_RESET)
					.actionContext("HOPPING")
					.build());
			}
			else
			{
				MovementCategory category = categories[i % categories.length];
				LedgerEvent.LedgerEventBuilder b = LedgerEvent.builder()
					.schemaVersion(LedgerEvent.SCHEMA_VERSION)
					.sessionId(SESSION)
					.ts(1_700_000_000_000L + i)
					.tick(i)
					.type(EventType.ITEM_MOVEMENT)
					.category(category)
					.containerId(93 + (i % 3))
					.itemId(995 + i)
					.qty(category == MovementCategory.UNCLASSIFIED_GAIN ? i + 1 : -(i + 1));
				if (i % 4 == 0)
				{
					b.actionContext("Deposit-All:Bank booth@12");
				}
				if (i % 7 == 0)
				{
					b.flags(Collections.singletonList(LedgerEvent.FLAG_INFERRED_DEPOSIT_BOX));
				}
				events.add(b.build());
			}
		}
		return events;
	}
}
