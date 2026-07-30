package com.nikko.theledger.store;

import com.google.gson.stream.JsonWriter;
import com.nikko.theledger.model.EventType;
import com.nikko.theledger.model.LedgerEvent;
import com.nikko.theledger.model.SessionHeader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Append-only JSONL writer.
 * <p>
 * {@code ItemContainerChanged} fires far too often to touch the disk inline, and a slow disk
 * must never stutter the client thread. So {@link #enqueue} only puts the event on a bounded
 * queue and returns; {@link #drain} does the writing and is meant to be scheduled on the
 * injected single-threaded executor. The file itself is opened lazily on the first drain, so
 * even creating it happens off the client thread.
 * <p>
 * The queue is bounded rather than unbounded on purpose: if the disk stalls, dropping events
 * and counting them is honest, whereas growing without limit eventually takes the client down.
 * The drop counter is surfaced in the debug panel.
 * <p>
 * Serialisation is written by hand through Gson's streaming {@link JsonWriter}. That is
 * deliberate on two counts: it uses no reflection, and it makes the bytes a direct function of
 * field order, which is what allows the round-trip test to assert byte-level equality.
 */
public final class JsonlEventWriter implements Closeable
{
	public static final int DEFAULT_QUEUE_CAPACITY = 4096;

	private final File file;
	private final SessionHeader header;
	private final BlockingQueue<LedgerEvent> queue;

	private final AtomicInteger written = new AtomicInteger();
	private final AtomicInteger dropped = new AtomicInteger();
	private final AtomicInteger errors = new AtomicInteger();
	private volatile String lastError;

	/**
	 * Guards the file handle. {@link #drain} runs on the executor and {@link #close} runs on
	 * the client thread at shutdown, so they can genuinely collide.
	 */
	private final Object writeLock = new Object();
	private BufferedWriter out;
	private boolean closed;

	public JsonlEventWriter(File file, SessionHeader header)
	{
		this(file, header, DEFAULT_QUEUE_CAPACITY);
	}

	public JsonlEventWriter(File file, SessionHeader header, int queueCapacity)
	{
		this.file = file;
		this.header = header;
		this.queue = new ArrayBlockingQueue<>(Math.max(16, queueCapacity));
	}

	/**
	 * Never blocks and never throws.
	 *
	 * @return false if the queue was full and the event was dropped.
	 */
	public boolean enqueue(LedgerEvent event)
	{
		if (closed || event == null)
		{
			return false;
		}
		if (queue.offer(event))
		{
			return true;
		}
		dropped.incrementAndGet();
		return false;
	}

	/**
	 * Writes and flushes everything queued. Safe to call from any thread; intended for the
	 * injected {@code ScheduledExecutorService}.
	 *
	 * @return the number of lines written.
	 */
	public int drain()
	{
		List<LedgerEvent> batch = new ArrayList<>(queue.size());
		queue.drainTo(batch);
		if (batch.isEmpty())
		{
			return 0;
		}

		synchronized (writeLock)
		{
			if (closed)
			{
				dropped.addAndGet(batch.size());
				return 0;
			}
			try
			{
				ensureOpen();
				for (LedgerEvent event : batch)
				{
					out.write(serialize(event));
					out.write('\n');
				}
				out.flush();
				written.addAndGet(batch.size());
				return batch.size();
			}
			catch (IOException e)
			{
				errors.incrementAndGet();
				lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
				dropped.addAndGet(batch.size());
				return 0;
			}
		}
	}

	/**
	 * Drains what is left, flushes and closes.
	 * <p>
	 * Called from {@code shutDown()} on the client thread. This is the one place the writer
	 * touches the disk from that thread, and it has to: the client can die immediately
	 * afterwards. The work is bounded by the queue depth.
	 */
	@Override
	public void close()
	{
		drain();
		synchronized (writeLock)
		{
			if (closed)
			{
				return;
			}
			closed = true;
			if (out != null)
			{
				try
				{
					out.flush();
					out.close();
				}
				catch (IOException e)
				{
					errors.incrementAndGet();
					lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
				}
				out = null;
			}
		}
	}

	private void ensureOpen() throws IOException
	{
		if (out != null)
		{
			return;
		}
		File parent = file.getParentFile();
		if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory())
		{
			throw new IOException("could not create " + parent);
		}
		boolean fresh = !file.exists() || file.length() == 0;
		out = new BufferedWriter(new OutputStreamWriter(
			new FileOutputStream(file, true), StandardCharsets.UTF_8));
		if (fresh)
		{
			// Every file starts with its header, so a reader never has to guess which session
			// or which schema version it is looking at.
			out.write(serialize(header));
			out.write('\n');
			out.flush();
		}
	}

	public File getFile()
	{
		return file;
	}

	public int getQueueDepth()
	{
		return queue.size();
	}

	public int getWrittenCount()
	{
		return written.get();
	}

	public int getDroppedCount()
	{
		return dropped.get();
	}

	public int getErrorCount()
	{
		return errors.get();
	}

	public String getLastError()
	{
		return lastError;
	}

	// ---- Serialisation. Key order below is the schema contract. ----

	public static String serialize(SessionHeader header)
	{
		StringWriter sw = new StringWriter(160);
		try (JsonWriter w = new JsonWriter(sw))
		{
			w.beginObject();
			w.name("schemaVersion").value(header.getSchemaVersion());
			w.name("sessionId").value(header.getSessionId());
			w.name("ts").value(header.getTs());
			w.name("tick").value(header.getTick());
			w.name("type").value(EventType.SESSION_START.name());
			w.name("accountHash").value(header.getAccountHash());
			w.name("worldType").value(header.getWorldType());
			writeFlags(w, header.getFlags());
			w.endObject();
		}
		catch (IOException e)
		{
			// A StringWriter cannot fail.
			throw new IllegalStateException(e);
		}
		return sw.toString();
	}

	public static String serialize(LedgerEvent event)
	{
		StringWriter sw = new StringWriter(192);
		try (JsonWriter w = new JsonWriter(sw))
		{
			w.beginObject();
			w.name("schemaVersion").value(event.getSchemaVersion());
			w.name("sessionId").value(event.getSessionId());
			w.name("ts").value(event.getTs());
			w.name("tick").value(event.getTick());
			w.name("type").value(event.getType() == null ? null : event.getType().name());
			// Absent rather than null: null-valued keys would be dead weight on every line.
			if (event.getCategory() != null)
			{
				w.name("category").value(event.getCategory().name());
			}
			if (event.getContainerId() != null)
			{
				w.name("containerId").value(event.getContainerId());
			}
			if (event.getItemId() != null)
			{
				w.name("itemId").value(event.getItemId());
			}
			if (event.getQty() != null)
			{
				w.name("qty").value(event.getQty());
			}
			if (event.getSkill() != null)
			{
				w.name("skill").value(event.getSkill());
			}
			if (event.getXpDelta() != null)
			{
				w.name("xpDelta").value(event.getXpDelta());
			}
			if (event.getActionContext() != null)
			{
				w.name("actionContext").value(event.getActionContext());
			}
			writeFlags(w, event.getFlags());
			w.endObject();
		}
		catch (IOException e)
		{
			throw new IllegalStateException(e);
		}
		return sw.toString();
	}

	private static void writeFlags(JsonWriter w, List<String> flags) throws IOException
	{
		// Always written, even when empty, so the shape of a line never varies.
		w.name("flags").beginArray();
		if (flags != null)
		{
			for (String flag : flags)
			{
				w.value(flag);
			}
		}
		w.endArray();
	}
}
