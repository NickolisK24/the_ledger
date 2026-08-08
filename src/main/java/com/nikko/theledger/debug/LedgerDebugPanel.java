package com.nikko.theledger.debug;

import com.nikko.theledger.capture.LedgerContainers;
import com.nikko.theledger.model.EventType;
import com.nikko.theledger.model.LedgerEvent;
import com.nikko.theledger.model.MovementCategory;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import javax.swing.text.DefaultCaret;
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
	/**
	 * How close to the bottom still counts as "at the bottom". A scrollbar rarely lands exactly on
	 * its maximum after a relayout, so an exact comparison would drop out of follow on its own.
	 */
	private static final int STICKY_TOLERANCE_PX = 8;
	private static final Dimension EVENT_LIST_SIZE = new Dimension(PANEL_WIDTH - 16, 220);

	private final AtomicInteger[] typeCounts = newCounters(EventType.values().length);
	private final AtomicInteger[] categoryCounts = newCounters(MovementCategory.values().length);
	private final Map<Integer, AtomicInteger> containersSeen = new ConcurrentHashMap<>();

	private final AtomicInteger reseeds = new AtomicInteger();
	private final AtomicInteger eagerSeeds = new AtomicInteger();
	private final AtomicInteger regionLoadsKept = new AtomicInteger();
	/**
	 * Where suppressed events go now that a counterparty flag keeps a movement out of the phantom
	 * count. Watched exactly like reseeds and xp-bad: both should be flat during ordinary play
	 * once the containers are seeded.
	 */
	private final AtomicInteger unverifiedTotal = new AtomicInteger();
	private final AtomicInteger counterpartyUnseeded = new AtomicInteger();
	private final AtomicInteger counterpartyUntracked = new AtomicInteger();
	private volatile int negativeXpReseeds;
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
	/**
	 * The event list gets a viewport of its own rather than sharing the panel's. Everything else
	 * stays live and visible while it is scrolled, and its position is not at the mercy of the
	 * other three areas being rewritten.
	 */
	private final JScrollPane recentScroll = new JScrollPane(recentArea);
	private final JCheckBox followToggle = new JCheckBox("Follow", true);
	private final JLabel followState = new JLabel();
	private final Timer refresh;

	/**
	 * Bumped whenever the event list changes, so a render that has nothing new to show leaves the
	 * document — and therefore the scroll position — completely alone.
	 */
	private final AtomicInteger revision = new AtomicInteger();
	private int renderedRevision = -1;

	public LedgerDebugPanel()
	{
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		add(header("The Ledger"));
		add(section("Session", statusArea));
		add(section("Counters", counterArea));
		add(section("Containers seen", containerArea));
		add(eventSection());

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
		boolean unseeded = event.hasFlag(LedgerEvent.FLAG_COUNTERPARTY_UNSEEDED);
		boolean untracked = event.hasFlag(LedgerEvent.FLAG_COUNTERPARTY_UNTRACKED);
		if (unseeded)
		{
			counterpartyUnseeded.incrementAndGet();
		}
		if (untracked)
		{
			counterpartyUntracked.incrementAndGet();
		}
		if (unseeded || untracked)
		{
			unverifiedTotal.incrementAndGet();
		}
		synchronized (recent)
		{
			// Newest last. A log reads oldest-to-newest, which is what makes following the bottom
			// the right behaviour rather than an arbitrary one.
			recent.addLast(summarise(event, queued));
			while (recent.size() > RECENT_LIMIT)
			{
				recent.removeFirst();
			}
		}
		revision.incrementAndGet();
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
	 * A region load kept its baselines instead of reseeding. Only counts when the experimental
	 * setting is on; this is the number to compare against reseeds when A/B testing it.
	 */
	public void recordRegionLoadKept()
	{
		regionLoadsKept.incrementAndGet();
	}

	/**
	 * Experience deltas that came out negative, which cannot happen in this game and therefore
	 * means a stale baseline. Must stay at zero.
	 */
	public void setNegativeXpReseeds(int count)
	{
		this.negativeXpReseeds = count;
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
		regionLoadsKept.set(0);
		unverifiedTotal.set(0);
		counterpartyUnseeded.set(0);
		counterpartyUntracked.set(0);
		negativeXpReseeds = 0;
		suppressed.set(0);
		resolvedTicks.set(0);
		synchronized (recent)
		{
			recent.clear();
		}
		revision.incrementAndGet();
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
		status.append("kept-rgn ").append(regionLoadsKept.get()).append('\n');
		status.append("unverif  ").append(unverifiedTotal.get()).append('\n');
		status.append("  unseed ").append(counterpartyUnseeded.get()).append('\n');
		status.append("  untrack").append(' ').append(counterpartyUntracked.get()).append('\n');
		status.append("xp-bad   ").append(negativeXpReseeds).append('\n');
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

		renderEventList();
		updateFollowState();

		revalidate();
		repaint();
	}

	/**
	 * Rewrites the event list without stealing the viewport.
	 * <p>
	 * Sticky follow, the standard log-viewer behaviour: note whether the scrollbar was already at
	 * the bottom, replace the text, and only jump to the bottom if it was. Scroll up and the panel
	 * leaves the viewport exactly where it was put, while every counter above keeps updating.
	 * <p>
	 * EDT only — the Swing timer fires here and nothing else touches these components.
	 */
	private void renderEventList()
	{
		int currentRevision = revision.get();
		if (currentRevision == renderedRevision)
		{
			// Nothing new. Rewriting an identical document would relayout the viewport for no
			// reason, which is its own source of scroll drift.
			return;
		}

		JScrollBar bar = recentScroll.getVerticalScrollBar();
		boolean wasAtBottom = isAtBottom(bar);

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
		renderedRevision = currentRevision;

		if (followToggle.isSelected() && wasAtBottom)
		{
			// After the document change has been laid out, or the maximum is still the old one.
			SwingUtilities.invokeLater(() ->
			{
				bar.setValue(bar.getMaximum() - bar.getVisibleAmount());
				updateFollowState();
			});
		}
	}

	private static boolean isAtBottom(JScrollBar bar)
	{
		return bar.getValue() + bar.getVisibleAmount() >= bar.getMaximum() - STICKY_TOLERANCE_PX;
	}

	/**
	 * Says why the list is not moving, so a deliberately pinned view is never mistaken for a
	 * frozen panel.
	 */
	private void updateFollowState()
	{
		if (!followToggle.isSelected())
		{
			followState.setText("off");
			followState.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		}
		else if (isAtBottom(recentScroll.getVerticalScrollBar()))
		{
			followState.setText("live");
			followState.setForeground(ColorScheme.PROGRESS_COMPLETE_COLOR);
		}
		else
		{
			followState.setText("paused, scrolled up");
			followState.setForeground(ColorScheme.BRAND_ORANGE);
		}
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

	/**
	 * The event list, its own scroll pane, and the follow controls.
	 */
	private JPanel eventSection()
	{
		recentScroll.setPreferredSize(EVENT_LIST_SIZE);
		recentScroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
		recentScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		recentScroll.setBorder(null);
		recentScroll.getViewport().setBackground(ColorScheme.DARKER_GRAY_COLOR);
		// Keep the indicator honest the moment the user drags, not a second later.
		recentScroll.getVerticalScrollBar().addAdjustmentListener(e -> updateFollowState());

		followToggle.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		followToggle.setForeground(ColorScheme.TEXT_COLOR);
		followToggle.setFocusable(false);
		followToggle.setToolTipText("Follow new events. Turns itself off while you scroll up, "
			+ "and resumes when you scroll back to the bottom.");
		followToggle.addActionListener(e ->
		{
			if (followToggle.isSelected())
			{
				JScrollBar bar = recentScroll.getVerticalScrollBar();
				bar.setValue(bar.getMaximum() - bar.getVisibleAmount());
			}
			updateFollowState();
		});

		followState.setFont(MONO);

		JPanel controls = new JPanel(new BorderLayout());
		controls.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		controls.add(followToggle, BorderLayout.WEST);
		controls.add(followState, BorderLayout.EAST);

		JPanel panel = section("Last " + RECENT_LIMIT + " events", recentScroll);
		panel.add(controls, BorderLayout.SOUTH);
		updateFollowState();
		return panel;
	}

	private static JPanel section(String title, JComponent body)
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
		// A DefaultCaret on ALWAYS_UPDATE drags its viewport on every setText. With four areas
		// sharing the panel's scroll pane, that is what made the whole panel snap while reading.
		if (a.getCaret() instanceof DefaultCaret)
		{
			((DefaultCaret) a.getCaret()).setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
		}
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
