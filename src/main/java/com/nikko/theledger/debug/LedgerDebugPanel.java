package com.nikko.theledger.debug;

import com.nikko.theledger.capture.LedgerContainers;
import com.nikko.theledger.model.EventType;
import com.nikko.theledger.model.LedgerEvent;
import com.nikko.theledger.model.MovementCategory;
import java.awt.BorderLayout;
import java.awt.Font;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

/**
 * The only window into whether the spine is behaving. Counters, the last fifty events, the
 * session, the queue, and every container id seen.
 * <p>
 * The container list is not decoration. If the container id constants were wrong, the spine
 * would diff nothing, log nothing, and every unit test would still pass — a silent failure no
 * fixture can catch. Seeing 93, 94 and 95 marked as tracked, and seeing the untracked ids the
 * account actually touches, is what rules that out in about thirty seconds.
 * <p>
 * Counters are written from the client thread and read on the EDT, so they are atomic. Nothing
 * here touches client state.
 */
public class LedgerDebugPanel extends PluginPanel
{
	private static final int RECENT_LIMIT = 50;
	private static final Font MONO = new Font(Font.MONOSPACED, Font.PLAIN, 11);

	private final AtomicInteger[] typeCounts = newCounters(EventType.values().length);
	private final AtomicInteger[] categoryCounts = newCounters(MovementCategory.values().length);
	private final Map<Integer, AtomicInteger> containersSeen = new ConcurrentHashMap<>();

	private final AtomicInteger reseeds = new AtomicInteger();
	private final AtomicInteger eagerSeeds = new AtomicInteger();
	private final AtomicInteger suppressed = new AtomicInteger();
	private final AtomicInteger resolvedTicks = new AtomicInteger();

	/**
	 * Guarded by itself.
	 */
	private final Deque<String> recent = new ArrayDeque<>();

	private volatile String sessionId = "none";
	private volatile String fileName = "none";
	private volatile int queueDepth;
	private volatile int written;
	private volatile int dropped;
	private volatile int errors;
	private volatile String lastError;

	private final JTextArea statusArea = area();
	private final JTextArea counterArea = area();
	private final JTextArea containerArea = area();
	private final JTextArea recentArea = area();
	private final Timer refresh;

	public LedgerDebugPanel()
	{
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		add(header("The Ledger"));
		add(section("Session", statusArea));
		add(section("Counters", counterArea));
		add(section("Containers seen", containerArea));
		add(section("Last " + RECENT_LIMIT + " events", recentArea));

		refresh = new Timer(1000, e -> render());
		refresh.setRepeats(true);
		refresh.start();
		render();
	}

	// ---- Called from the client thread ----

	public void record(LedgerEvent event, boolean queued)
	{
		if (event == null)
		{
			return;
		}
		if (event.getType() != null)
		{
			typeCounts[event.getType().ordinal()].incrementAndGet();
		}
		if (event.getCategory() != null)
		{
			categoryCounts[event.getCategory().ordinal()].incrementAndGet();
		}
		synchronized (recent)
		{
			recent.addFirst(summarise(event, queued));
			while (recent.size() > RECENT_LIMIT)
			{
				recent.removeLast();
			}
		}
	}

	/**
	 * Every container id the client reports, tracked or not.
	 */
	public void recordContainerSeen(int containerId)
	{
		containersSeen.computeIfAbsent(containerId, k -> new AtomicInteger()).incrementAndGet();
	}

	/**
	 * A container was observed with no baseline, so its contents were absorbed silently. Real
	 * movements in that tick are lost, which is the accepted price of never inventing one.
	 */
	public void recordSilentReseed()
	{
		reseeds.incrementAndGet();
	}

	/**
	 * A container without a baseline was read directly and seeded, rather than waiting for it to
	 * change. High counts early in a session are expected; a count that keeps climbing means
	 * something is invalidating baselines repeatedly.
	 */
	public void recordEagerSeed()
	{
		eagerSeeds.incrementAndGet();
	}

	/**
	 * An event was produced with no session open, so it was not written.
	 */
	public void recordSuppressed()
	{
		suppressed.incrementAndGet();
	}

	public void recordResolvedTick()
	{
		resolvedTicks.incrementAndGet();
	}

	public void setSession(String sessionId, String fileName)
	{
		this.sessionId = sessionId == null ? "none" : sessionId;
		this.fileName = fileName == null ? "none" : fileName;
	}

	public void setWriterStats(int queueDepth, int written, int dropped, int errors, String lastError)
	{
		this.queueDepth = queueDepth;
		this.written = written;
		this.dropped = dropped;
		this.errors = errors;
		this.lastError = lastError;
	}

	public void reset()
	{
		for (AtomicInteger c : typeCounts)
		{
			c.set(0);
		}
		for (AtomicInteger c : categoryCounts)
		{
			c.set(0);
		}
		containersSeen.clear();
		reseeds.set(0);
		eagerSeeds.set(0);
		suppressed.set(0);
		resolvedTicks.set(0);
		synchronized (recent)
		{
			recent.clear();
		}
		setSession(null, null);
		setWriterStats(0, 0, 0, 0, null);
	}

	/**
	 * Stops the refresh timer. Called from shutDown.
	 */
	public void stop()
	{
		refresh.stop();
	}

	// ---- Rendering, on the EDT ----

	private void render()
	{
		StringBuilder status = new StringBuilder();
		status.append("session  ").append(sessionId).append('\n');
		status.append("file     ").append(fileName).append('\n');
		status.append("queue    ").append(queueDepth).append('\n');
		status.append("written  ").append(written).append('\n');
		status.append("dropped  ").append(dropped).append('\n');
		status.append("ticks    ").append(resolvedTicks.get()).append('\n');
		status.append("reseeds  ").append(reseeds.get()).append('\n');
		status.append("seeded   ").append(eagerSeeds.get()).append('\n');
		status.append("no-sess  ").append(suppressed.get());
		if (errors > 0)
		{
			status.append('\n').append("errors   ").append(errors);
			if (lastError != null)
			{
				status.append('\n').append(lastError);
			}
		}
		statusArea.setText(status.toString());

		StringBuilder counters = new StringBuilder();
		for (EventType type : EventType.values())
		{
			counters.append(pad(type.name(), 14))
				.append(typeCounts[type.ordinal()].get()).append('\n');
		}
		counters.append('\n');
		for (MovementCategory category : MovementCategory.values())
		{
			int count = categoryCounts[category.ordinal()].get();
			// Phase 2 categories stay at zero; listing them makes the schema visible.
			counters.append(pad(category.name(), 18)).append(count).append('\n');
		}
		counterArea.setText(counters.toString().trim());

		StringBuilder containers = new StringBuilder();
		if (containersSeen.isEmpty())
		{
			containers.append("nothing observed yet");
		}
		else
		{
			for (Map.Entry<Integer, AtomicInteger> e : new TreeMap<>(containersSeen).entrySet())
			{
				int id = e.getKey();
				containers.append(pad(String.valueOf(id), 6))
					.append(pad(LedgerContainers.isTracked(id) ? "tracked" : "-", 9))
					.append(e.getValue().get())
					.append("  ").append(LedgerContainers.name(id))
					.append('\n');
			}
		}
		containerArea.setText(containers.toString().trim());

		StringBuilder recentText = new StringBuilder();
		synchronized (recent)
		{
			if (recent.isEmpty())
			{
				recentText.append("no events yet");
			}
			else
			{
				for (String line : recent)
				{
					recentText.append(line).append('\n');
				}
			}
		}
		recentArea.setText(recentText.toString().trim());

		revalidate();
		repaint();
	}

	private static String summarise(LedgerEvent event, boolean queued)
	{
		StringBuilder sb = new StringBuilder();
		sb.append(event.getTick()).append(' ');
		if (!queued)
		{
			sb.append("[DROPPED] ");
		}
		switch (event.getType())
		{
			case ITEM_MOVEMENT:
				sb.append(shortCategory(event.getCategory()))
					.append(' ').append(event.getContainerId() == null
						? "?" : LedgerContainers.name(event.getContainerId()))
					.append(' ').append(event.getItemId())
					.append(' ').append(event.getQty() != null && event.getQty() > 0 ? "+" : "")
					.append(event.getQty());
				break;
			case XP_GAIN:
				sb.append("XP ").append(event.getSkill()).append(" +").append(event.getXpDelta());
				break;
			default:
				sb.append(event.getType().name());
				if (event.getActionContext() != null)
				{
					sb.append(' ').append(event.getActionContext());
				}
				break;
		}
		if (event.getFlags() != null && !event.getFlags().isEmpty())
		{
			sb.append(' ').append(event.getFlags());
		}
		return sb.toString();
	}

	private static String shortCategory(MovementCategory category)
	{
		if (category == null)
		{
			return "?";
		}
		switch (category)
		{
			case TRANSFER:
				return "XFER";
			case DEATH_LOSS:
				return "DEATH";
			case UNCLASSIFIED_GAIN:
				return "GAIN";
			case UNCLASSIFIED_LOSS:
				return "LOSS";
			case UNVERIFIED:
				return "UNVER";
			default:
				return category.name();
		}
	}

	private static String pad(String s, int width)
	{
		StringBuilder sb = new StringBuilder(s);
		while (sb.length() < width)
		{
			sb.append(' ');
		}
		return sb.toString();
	}

	private static JLabel header(String text)
	{
		JLabel label = new JLabel(text);
		label.setForeground(ColorScheme.BRAND_ORANGE);
		label.setBorder(new EmptyBorder(2, 0, 4, 0));
		return label;
	}

	private static JPanel section(String title, JTextArea body)
	{
		JPanel panel = new JPanel(new BorderLayout());
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setBorder(new EmptyBorder(4, 4, 4, 4));

		JLabel label = new JLabel(title);
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		label.setBorder(new EmptyBorder(0, 0, 3, 0));

		panel.add(label, BorderLayout.NORTH);
		panel.add(body, BorderLayout.CENTER);
		return panel;
	}

	private static JTextArea area()
	{
		JTextArea a = new JTextArea();
		a.setEditable(false);
		a.setLineWrap(true);
		a.setWrapStyleWord(false);
		a.setFont(MONO);
		a.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		a.setForeground(ColorScheme.TEXT_COLOR);
		return a;
	}

	private static AtomicInteger[] newCounters(int size)
	{
		AtomicInteger[] counters = new AtomicInteger[size];
		for (int i = 0; i < size; i++)
		{
			counters[i] = new AtomicInteger();
		}
		return counters;
	}
}
