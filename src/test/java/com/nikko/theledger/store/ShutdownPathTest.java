package com.nikko.theledger.store;

import com.nikko.theledger.model.EventType;
import com.nikko.theledger.model.LedgerEvent;
import com.nikko.theledger.model.MovementCategory;
import com.nikko.theledger.model.SessionHeader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The shutdown path as the client actually takes it.
 * <p>
 * Three consecutive real sessions produced no SESSION_END, and twelve passing tests had not
 * noticed, because every one of them called {@code close()} directly. Closing the client window
 * does not call {@code Plugin.shutDown()} at all: RuneLite's window listener posts a
 * {@code ClientShutdown} event and then races a ten second timer to {@code System.exit}. Work
 * that is not registered with that event does not happen.
 * <p>
 * So the footer is now written by a task submitted to the injected executor, whose {@code Future}
 * is handed to {@code ClientShutdown.waitFor}. These fixtures exercise that shape — submit,
 * register, wait, then read the file — rather than the shortcut.
 * <p>
 * <b>What this cannot cover:</b> whether RuneLite really posts the event and really honours the
 * wait. That is a property of the client, established by reading its source, and it is verified
 * in a live session by checking that the last line of a session file is a SESSION_END carrying a
 * droppedEvents field.
 */
public class ShutdownPathTest
{
	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	private static final String SESSION = "shutdown-session";

	/**
	 * Faithful stand-in for {@code net.runelite.client.events.ClientShutdown}, transcribed from
	 * its source: consumers register futures, and the client drains them under one shared
	 * deadline before exiting.
	 */
	private static final class ShutdownEvent
	{
		private final Queue<Future<?>> tasks = new ArrayDeque<>();

		void waitFor(Future<?> future)
		{
			tasks.add(future);
		}

		void waitForAllConsumers(Duration totalTimeout)
		{
			long deadline = System.nanoTime() + totalTimeout.toNanos();
			for (Future<?> task; (task = tasks.poll()) != null; )
			{
				long timeout = deadline - System.nanoTime();
				if (timeout < 0)
				{
					return;
				}
				try
				{
					task.get(timeout, TimeUnit.NANOSECONDS);
				}
				catch (Exception ignored)
				{
					// The client logs and carries on.
				}
			}
		}
	}

	@Test
	public void theFooterLandsWhenTheShutdownWaitsForIt() throws IOException
	{
		File file = new File(tmp.getRoot(), "clean.jsonl");
		JsonlEventWriter writer = new JsonlEventWriter(file, header());
		for (int i = 0; i < 40; i++)
		{
			writer.enqueue(movement(i));
		}

		ExecutorService executor = Executors.newSingleThreadScheduledExecutor();
		ShutdownEvent event = new ShutdownEvent();
		event.waitFor(executor.submit(() -> endSession(writer)));
		event.waitForAllConsumers(Duration.ofSeconds(10));
		executor.shutdownNow();

		JsonlEventReader.ReplaySession replay = JsonlEventReader.read(file);

		assertFalse("a clean exit must not read as a crash", replay.isTruncated());
		assertTrue(replay.hasSessionEnd());
		assertNotNull("the drop total is only knowable from the footer", replay.getDroppedEvents());
		assertEquals(Integer.valueOf(0), replay.getDroppedEvents());
		assertEquals(41, replay.getEvents().size());
	}

	/**
	 * The check you can perform on a real file: the LAST line is a SESSION_END carrying
	 * droppedEvents. Nothing queued may be stranded behind it.
	 */
	@Test
	public void theLastLineOfTheFileIsTheFooter() throws IOException
	{
		File file = new File(tmp.getRoot(), "lastline.jsonl");
		JsonlEventWriter writer = new JsonlEventWriter(file, header());
		for (int i = 0; i < 200; i++)
		{
			writer.enqueue(movement(i));
		}

		ExecutorService executor = Executors.newSingleThreadScheduledExecutor();
		ShutdownEvent event = new ShutdownEvent();
		event.waitFor(executor.submit(() -> endSession(writer)));
		event.waitForAllConsumers(Duration.ofSeconds(10));
		executor.shutdownNow();

		List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
		String last = lines.get(lines.size() - 1);

		assertTrue(last, last.contains("\"type\":\"SESSION_END\""));
		assertTrue(last, last.contains("\"droppedEvents\":"));
		assertEquals("header + 200 events + footer", 202, lines.size());
	}

	/**
	 * The whole queue is drained by the shutdown task, not just whatever a timer happened to have
	 * flushed. Nothing enqueued in the final seconds is lost.
	 */
	@Test
	public void everythingQueuedAtShutdownIsWritten() throws IOException
	{
		File file = new File(tmp.getRoot(), "drain.jsonl");
		JsonlEventWriter writer = new JsonlEventWriter(file, header());
		writer.enqueue(movement(0));
		writer.drain();                       // the periodic flush got this far
		for (int i = 1; i <= 500; i++)        // and then 500 more arrived
		{
			writer.enqueue(movement(i));
		}
		assertEquals(500, writer.getQueueDepth());

		ExecutorService executor = Executors.newSingleThreadScheduledExecutor();
		ShutdownEvent event = new ShutdownEvent();
		event.waitFor(executor.submit(() -> endSession(writer)));
		event.waitForAllConsumers(Duration.ofSeconds(10));
		executor.shutdownNow();

		JsonlEventReader.ReplaySession replay = JsonlEventReader.read(file);
		assertEquals(502, replay.getEvents().size());
		assertTrue(replay.hasSessionEnd());
		assertEquals(0, writer.getQueueDepth());
	}

	/**
	 * The drop total reaches the footer, which is the only place it can be recorded.
	 */
	@Test
	public void aCompromisedSessionStillReportsItsTotalOnTheFooter() throws IOException
	{
		File file = new File(tmp.getRoot(), "dropped.jsonl");
		JsonlEventWriter writer = new JsonlEventWriter(file, header(), 8);
		for (int i = 0; i < 100; i++)
		{
			writer.enqueue(movement(i));
		}

		ExecutorService executor = Executors.newSingleThreadScheduledExecutor();
		ShutdownEvent event = new ShutdownEvent();
		event.waitFor(executor.submit(() -> endSession(writer)));
		event.waitForAllConsumers(Duration.ofSeconds(10));
		executor.shutdownNow();

		JsonlEventReader.ReplaySession replay = JsonlEventReader.read(file);
		assertFalse(replay.isTruncated());
		assertTrue(replay.isCompromised());
		assertEquals(Integer.valueOf(92), replay.getDroppedEvents());
	}

	/**
	 * The failure mode, stated rather than hidden: if the work is never registered with the
	 * shutdown event, the process exits without it and the file reads as a crash. This is exactly
	 * what three real sessions produced, and it is what the fix prevents.
	 */
	@Test
	public void workThatIsNotRegisteredWithTheShutdownIsLost() throws IOException
	{
		File file = new File(tmp.getRoot(), "unregistered.jsonl");
		JsonlEventWriter writer = new JsonlEventWriter(file, header());
		for (int i = 0; i < 40; i++)
		{
			writer.enqueue(movement(i));
		}
		writer.drain();

		// No submit, no waitFor: the JVM simply goes away.
		JsonlEventReader.ReplaySession replay = JsonlEventReader.read(file);

		assertTrue(replay.isTruncated());
		assertNull(replay.getDroppedEvents());
		assertFalse(replay.hasSessionEnd());
	}

	/**
	 * Mirrors {@code TheLedgerPlugin.endSession}: append the footer, then drain and close.
	 */
	private static void endSession(JsonlEventWriter writer)
	{
		writer.close(LedgerEvent.builder()
			.schemaVersion(LedgerEvent.SCHEMA_VERSION)
			.sessionId(SESSION)
			.ts(1_700_000_099_000L)
			.tick(999)
			.type(EventType.SESSION_END)
			.droppedEvents(writer.getDroppedCount())
			.actionContext("clientShutdown")
			.build());
	}

	private static SessionHeader header()
	{
		return SessionHeader.builder()
			.schemaVersion(LedgerEvent.SCHEMA_VERSION)
			.sessionId(SESSION)
			.ts(1_700_000_000_000L)
			.tick(0)
			.accountHash(7L)
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
			.category(MovementCategory.UNCLASSIFIED_LOSS)
			.containerId(93)
			.itemId(554)
			.qty(-2)
			.build();
	}
}
