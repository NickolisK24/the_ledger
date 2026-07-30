package com.nikko.theledger.store;

import com.nikko.theledger.model.EventType;
import com.nikko.theledger.model.LedgerEvent;
import com.nikko.theledger.model.MovementCategory;
import com.nikko.theledger.model.SessionHeader;
import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Dropping under backpressure is acceptable. Dropping silently is not.
 * <p>
 * A counter in a debug panel is invisible to anything replaying the file later, and a gap in
 * kill counts silently deflates every rate and dry-streak figure computed from it. So the gap
 * goes into the stream itself, exactly as STATE_RESET records the other kind of gap.
 */
public class DataLossTest
{
	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	private static final String SESSION = "data-loss-session";

	@Test
	public void overflowEmitsExactlyOneMarkerRegardlessOfHowManyEventsAreDropped()
		throws IOException
	{
		File file = new File(tmp.getRoot(), "overflow.jsonl");
		JsonlEventWriter writer = new JsonlEventWriter(file, header(), 16);

		for (int i = 0; i < 5000; i++)
		{
			writer.enqueue(movement(i));
		}
		assertEquals(4984, writer.getDroppedCount());

		writer.drain();
		writer.close();

		JsonlEventReader.ReplaySession replay = JsonlEventReader.read(file);
		List<LedgerEvent> markers = ofType(replay.getEvents(), EventType.DATA_LOSS);

		assertEquals("one marker per session, not one per drop", 1, markers.size());
		assertTrue(replay.isCompromised());
	}

	@Test
	public void markerRecordsWhereTheLossBeganAndSitsAheadOfTheRecoveredBatch()
		throws IOException
	{
		File file = new File(tmp.getRoot(), "position.jsonl");
		JsonlEventWriter writer = new JsonlEventWriter(file, header(), 4);

		// Ticks 0-3 fit; tick 4 is the first casualty.
		for (int i = 0; i < 10; i++)
		{
			writer.enqueue(movement(i));
		}
		writer.drain();

		// Room again: this one is written after the marker.
		writer.enqueue(movement(99));
		writer.drain();
		writer.close();

		JsonlEventReader.ReplaySession replay = JsonlEventReader.read(file);
		List<LedgerEvent> events = replay.getEvents();

		LedgerEvent marker = ofType(events, EventType.DATA_LOSS).get(0);
		assertEquals("marker is stamped with the tick the loss started on", 4, marker.getTick());
		assertNull(marker.getCategory());

		// Order in the file: the four that fit, then the marker, then the later event.
		assertEquals(6, events.size());
		assertEquals(EventType.DATA_LOSS, events.get(4).getType());
		assertEquals(99, events.get(5).getTick());
	}

	/**
	 * The marker cannot go on the queue, because a full queue is exactly the condition that
	 * creates it. It is held outside and written ahead of the next batch.
	 */
	@Test
	public void markerIsWrittenEvenWhenTheQueueNeverDrainsAnythingElse() throws IOException
	{
		File file = new File(tmp.getRoot(), "marker-only.jsonl");
		JsonlEventWriter writer = new JsonlEventWriter(file, header(), 1);

		writer.enqueue(movement(1));
		writer.drain();                 // queue emptied
		assertEquals(0, writer.getDroppedCount());

		// Fill it and overflow, then drain twice: the second drain has an empty queue and must
		// still not be the reason the marker goes missing.
		writer.enqueue(movement(2));
		writer.enqueue(movement(3));
		assertEquals(1, writer.getDroppedCount());
		writer.drain();
		writer.drain();
		writer.close();

		JsonlEventReader.ReplaySession replay = JsonlEventReader.read(file);
		assertEquals(1, ofType(replay.getEvents(), EventType.DATA_LOSS).size());
	}

	@Test
	public void dropTotalSurvivesTheRoundTripOnSessionEnd() throws IOException
	{
		File file = new File(tmp.getRoot(), "total.jsonl");
		JsonlEventWriter writer = new JsonlEventWriter(file, header(), 8);

		for (int i = 0; i < 100; i++)
		{
			writer.enqueue(movement(i));
		}
		writer.drain();

		int dropped = writer.getDroppedCount();
		assertEquals(92, dropped);

		writer.enqueue(LedgerEvent.builder()
			.schemaVersion(LedgerEvent.SCHEMA_VERSION)
			.sessionId(SESSION)
			.ts(1_700_000_001_000L)
			.tick(500)
			.type(EventType.SESSION_END)
			.droppedEvents(dropped)
			.actionContext("shutDown")
			.build());
		writer.close();

		JsonlEventReader.ReplaySession replay = JsonlEventReader.read(file);

		assertTrue(replay.hasSessionEnd());
		assertEquals(Integer.valueOf(92), replay.getDroppedEvents());
		assertTrue(replay.isCompromised());
	}

	/**
	 * A clean session must not look compromised, or the signal is worthless.
	 */
	@Test
	public void cleanSessionReportsNoLoss() throws IOException
	{
		File file = new File(tmp.getRoot(), "clean.jsonl");
		try (JsonlEventWriter writer = new JsonlEventWriter(file, header(), 4096))
		{
			for (int i = 0; i < 200; i++)
			{
				assertTrue(writer.enqueue(movement(i)));
			}
			writer.drain();
			writer.enqueue(LedgerEvent.builder()
				.schemaVersion(LedgerEvent.SCHEMA_VERSION)
				.sessionId(SESSION)
				.ts(1_700_000_001_000L)
				.tick(500)
				.type(EventType.SESSION_END)
				.droppedEvents(0)
				.actionContext("shutDown")
				.build());
		}

		JsonlEventReader.ReplaySession replay = JsonlEventReader.read(file);

		assertEquals(0, replay.getMalformedLines());
		assertTrue(replay.hasSessionEnd());
		assertEquals(Integer.valueOf(0), replay.getDroppedEvents());
		assertFalse(replay.isCompromised());
		assertTrue(ofType(replay.getEvents(), EventType.DATA_LOSS).isEmpty());
	}

	/**
	 * A file with no SESSION_END was cut off by a crash, so the true drop total is unknowable.
	 * Reporting null rather than zero keeps a consumer from trusting a figure that was never
	 * written.
	 */
	@Test
	public void crashedSessionReportsAnUnknownDropTotal() throws IOException
	{
		File file = new File(tmp.getRoot(), "crashed.jsonl");
		JsonlEventWriter writer = new JsonlEventWriter(file, header(), 4096);
		writer.enqueue(movement(1));
		writer.drain();
		// No close, no SESSION_END: the client died.

		JsonlEventReader.ReplaySession replay = JsonlEventReader.read(file);

		assertFalse(replay.hasSessionEnd());
		assertNull(replay.getDroppedEvents());
		assertFalse(replay.isCompromised());
	}

	@Test
	public void droppedEventsFieldIsAbsentFromEveryOtherLine()
	{
		String line = JsonlEventWriter.serialize(movement(1));
		assertFalse(line, line.contains("droppedEvents"));

		LedgerEvent end = LedgerEvent.builder()
			.schemaVersion(LedgerEvent.SCHEMA_VERSION)
			.sessionId(SESSION)
			.ts(1_700_000_000_000L)
			.tick(9)
			.type(EventType.SESSION_END)
			.droppedEvents(7)
			.build();
		assertEquals("{\"schemaVersion\":1,\"sessionId\":\"" + SESSION + "\",\"ts\":1700000000000,"
			+ "\"tick\":9,\"type\":\"SESSION_END\",\"droppedEvents\":7,\"flags\":[]}",
			JsonlEventWriter.serialize(end));
	}

	/**
	 * A drop total written by a session must survive re-serialisation unchanged, or replaying a
	 * file would quietly launder away the evidence that it has holes.
	 */
	@Test
	public void sessionEndRoundTripsByteIdentically() throws IOException
	{
		File file = new File(tmp.getRoot(), "bytes.jsonl");
		LedgerEvent end = LedgerEvent.builder()
			.schemaVersion(LedgerEvent.SCHEMA_VERSION)
			.sessionId(SESSION)
			.ts(1_700_000_001_000L)
			.tick(500)
			.type(EventType.SESSION_END)
			.droppedEvents(4984)
			.actionContext("shutDown")
			.build();

		try (JsonlEventWriter writer = new JsonlEventWriter(file, header()))
		{
			writer.enqueue(end);
		}

		JsonlEventReader.ReplaySession replay = JsonlEventReader.read(file);
		LedgerEvent back = replay.getEvents().get(0);

		assertEquals(end, back);
		assertEquals(JsonlEventWriter.serialize(end), JsonlEventWriter.serialize(back));
		assertNotNull(back.getDroppedEvents());
	}

	private static List<LedgerEvent> ofType(List<LedgerEvent> events, EventType type)
	{
		List<LedgerEvent> out = new java.util.ArrayList<>();
		for (LedgerEvent e : events)
		{
			if (e.getType() == type)
			{
				out.add(e);
			}
		}
		return out;
	}

	private static SessionHeader header()
	{
		return SessionHeader.builder()
			.schemaVersion(LedgerEvent.SCHEMA_VERSION)
			.sessionId(SESSION)
			.ts(1_700_000_000_000L)
			.tick(0)
			.accountHash(99L)
			.worldType("MEMBERS")
			.flags(Collections.emptyList())
			.build();
	}

	private static LedgerEvent movement(int tick)
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
			.qty(1)
			.build();
	}
}
