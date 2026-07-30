package com.nikko.theledger.store;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nikko.theledger.model.EventType;
import com.nikko.theledger.model.LedgerEvent;
import com.nikko.theledger.model.MovementCategory;
import com.nikko.theledger.model.SessionHeader;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Replays a session file back into the event stream.
 * <p>
 * Built now rather than later because the tests depend on it: the only way to show the spine
 * is correct is to write a session, read it back and check the reconstruction.
 * <p>
 * The client can die without warning mid-write, so the last line of a file may be truncated.
 * A malformed line is skipped and counted, never fatal — one lost final event must not cost
 * the whole session.
 */
public final class JsonlEventReader
{
	/**
	 * Everything a file contained: its header, its events, and how many lines were unreadable.
	 */
	public static final class ReplaySession
	{
		private final SessionHeader header;
		private final List<LedgerEvent> events;
		private final int malformedLines;

		ReplaySession(SessionHeader header, List<LedgerEvent> events, int malformedLines)
		{
			this.header = header;
			this.events = Collections.unmodifiableList(events);
			this.malformedLines = malformedLines;
		}

		/**
		 * Null if the first line was missing or unreadable.
		 */
		public SessionHeader getHeader()
		{
			return header;
		}

		/**
		 * Every event after the header line, in file order.
		 */
		public List<LedgerEvent> getEvents()
		{
			return events;
		}

		public int getMalformedLines()
		{
			return malformedLines;
		}
	}

	public static ReplaySession read(File file) throws IOException
	{
		SessionHeader header = null;
		List<LedgerEvent> events = new ArrayList<>();
		int malformed = 0;

		try (BufferedReader in = new BufferedReader(new InputStreamReader(
			new FileInputStream(file), StandardCharsets.UTF_8)))
		{
			String line;
			boolean first = true;
			while ((line = in.readLine()) != null)
			{
				if (line.trim().isEmpty())
				{
					continue;
				}
				if (first)
				{
					first = false;
					SessionHeader parsed = parseHeader(line);
					if (parsed != null)
					{
						header = parsed;
						continue;
					}
					// Not a header. Fall through and try it as an ordinary event so a file with
					// a damaged first line still yields whatever else it holds.
				}
				LedgerEvent event = parseEvent(line);
				if (event == null)
				{
					malformed++;
					continue;
				}
				events.add(event);
			}
		}

		return new ReplaySession(header, events, malformed);
	}

	/**
	 * @return the header, or null if this line is not a readable SESSION_START header.
	 */
	public static SessionHeader parseHeader(String line)
	{
		JsonObject o = parseObject(line);
		if (o == null || !hasRequiredFields(o))
		{
			return null;
		}
		if (!EventType.SESSION_START.name().equals(asString(o, "type")))
		{
			return null;
		}
		if (!o.has("accountHash") || !o.has("worldType"))
		{
			return null;
		}
		try
		{
			return SessionHeader.builder()
				.schemaVersion(o.get("schemaVersion").getAsInt())
				.sessionId(o.get("sessionId").getAsString())
				.ts(o.get("ts").getAsLong())
				.tick(o.get("tick").getAsInt())
				.accountHash(o.get("accountHash").getAsLong())
				.worldType(o.get("worldType").getAsString())
				.flags(readFlags(o))
				.build();
		}
		catch (RuntimeException e)
		{
			return null;
		}
	}

	/**
	 * @return the event, or null if the line is unreadable or incomplete.
	 */
	public static LedgerEvent parseEvent(String line)
	{
		JsonObject o = parseObject(line);
		if (o == null || !hasRequiredFields(o))
		{
			return null;
		}
		try
		{
			LedgerEvent.LedgerEventBuilder b = LedgerEvent.builder()
				.schemaVersion(o.get("schemaVersion").getAsInt())
				.sessionId(o.get("sessionId").getAsString())
				.ts(o.get("ts").getAsLong())
				.tick(o.get("tick").getAsInt())
				.type(EventType.valueOf(o.get("type").getAsString()))
				.flags(readFlags(o));

			String category = asString(o, "category");
			if (category != null)
			{
				b.category(MovementCategory.valueOf(category));
			}
			if (o.has("containerId"))
			{
				b.containerId(o.get("containerId").getAsInt());
			}
			if (o.has("itemId"))
			{
				b.itemId(o.get("itemId").getAsInt());
			}
			if (o.has("qty"))
			{
				b.qty(o.get("qty").getAsInt());
			}
			String skill = asString(o, "skill");
			if (skill != null)
			{
				b.skill(skill);
			}
			if (o.has("xpDelta"))
			{
				b.xpDelta(o.get("xpDelta").getAsInt());
			}
			String actionContext = asString(o, "actionContext");
			if (actionContext != null)
			{
				b.actionContext(actionContext);
			}
			return b.build();
		}
		catch (RuntimeException e)
		{
			// Unknown enum member from a newer schema, or a field of the wrong type.
			return null;
		}
	}

	private static JsonObject parseObject(String line)
	{
		String trimmed = line.trim();
		// Gson's parser is lenient, so a truncated object can parse as far as it got. Requiring
		// the closing brace is what actually catches a half-written final line.
		if (!trimmed.startsWith("{") || !trimmed.endsWith("}"))
		{
			return null;
		}
		try
		{
			JsonElement parsed = JsonParser.parseString(trimmed);
			return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
		}
		catch (RuntimeException e)
		{
			return null;
		}
	}

	private static boolean hasRequiredFields(JsonObject o)
	{
		return o.has("schemaVersion") && o.has("sessionId") && o.has("ts")
			&& o.has("tick") && o.has("type");
	}

	private static String asString(JsonObject o, String name)
	{
		JsonElement e = o.get(name);
		return e == null || e.isJsonNull() ? null : e.getAsString();
	}

	private static List<String> readFlags(JsonObject o)
	{
		JsonElement e = o.get("flags");
		if (e == null || !e.isJsonArray())
		{
			return Collections.emptyList();
		}
		List<String> flags = new ArrayList<>(e.getAsJsonArray().size());
		for (JsonElement f : e.getAsJsonArray())
		{
			flags.add(f.getAsString());
		}
		return flags.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(flags);
	}

	private JsonlEventReader()
	{
	}
}
