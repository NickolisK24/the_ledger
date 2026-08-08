package com.nikko.theledger;

import com.google.inject.Provides;
import com.nikko.theledger.capture.ActionContext;
import com.nikko.theledger.capture.ContainerDiffer;
import com.nikko.theledger.capture.ContainerSnapshot;
import com.nikko.theledger.capture.LedgerContainers;
import com.nikko.theledger.capture.MovementResolver;
import com.nikko.theledger.capture.TickBuffer;
import com.nikko.theledger.debug.LedgerDebugPanel;
import com.nikko.theledger.model.EventType;
import com.nikko.theledger.model.LedgerEvent;
import com.nikko.theledger.model.SessionHeader;
import com.nikko.theledger.store.JsonlEventWriter;
import com.nikko.theledger.store.SessionManager;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuAction;
import net.runelite.api.WorldType;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.StatChanged;
import net.runelite.client.RuneLite;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ClientShutdown;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;

/**
 * Records a durable, local, append-only log of everything that happens to this account.
 * <p>
 * This class is only wiring. Every rule lives in {@code capture} and every byte written lives
 * in {@code store}, neither of which imports anything from RuneLite, so the behaviour that
 * matters is decided by fixtures rather than by playing the game.
 * <p>
 * The two methods that carry real logic are {@link #onItemContainerChanged} and
 * {@link #onGameTick}, and both are mirrored exactly by {@code SnapshotFixtures} in the tests.
 * If you change one, change the other.
 * <p>
 * Nothing here writes to disk. Events go on a queue and the injected single-threaded executor
 * drains it; the one exception is {@link #shutDown()}, which has to finish the file before the
 * client goes away.
 */
@Slf4j
@PluginDescriptor(
	name = "The Ledger",
	description = "Records a local, append-only event log of everything that happens to your account",
	tags = {"ledger", "log", "profit", "loot", "tracker", "history"},
	enabledByDefault = false
)
public class TheLedgerPlugin extends Plugin
{
	/**
	 * Game states after which no container baseline can be trusted. Containers repopulate on all
	 * of them, and a repopulation diffed against a stale baseline is a phantom gain.
	 */
	private static final EnumSet<GameState> INVALIDATING_STATES = EnumSet.of(
		GameState.LOGIN_SCREEN,
		GameState.LOGIN_SCREEN_AUTHENTICATOR,
		GameState.LOGGING_IN,
		GameState.LOADING,
		GameState.HOPPING,
		GameState.CONNECTION_LOST
	);

	/**
	 * Interactions that say nothing about where items went. Kept out of the action context so
	 * they cannot displace a click whose effect has not landed yet.
	 * <p>
	 * Matched on {@link MenuAction} rather than the option label: labels are display strings and
	 * shift between game revisions, whereas these enum constants are stable.
	 */
	private static final EnumSet<MenuAction> NON_INFORMATIVE_ACTIONS = EnumSet.of(
		MenuAction.CANCEL,
		MenuAction.WALK,
		MenuAction.EXAMINE_ITEM,
		MenuAction.EXAMINE_ITEM_GROUND,
		MenuAction.EXAMINE_NPC,
		MenuAction.EXAMINE_OBJECT
	);

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ScheduledExecutorService executor;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private TheLedgerConfig config;

	private final Map<Integer, ContainerSnapshot> snapshots = new HashMap<>();
	private final TickBuffer tickBuffer = new TickBuffer();

	private SessionManager sessionManager;
	private MovementResolver resolver;
	private LedgerContainers.Canonicalizer canonicalizer;
	private LedgerDebugPanel panel;
	private NavigationButton navButton;

	/**
	 * Replaced on session rotation and read by the flush task on the executor.
	 */
	private volatile JsonlEventWriter writer;
	private ScheduledFuture<?> flushTask;

	private ActionContext lastAction = ActionContext.EMPTY;
	/**
	 * Last tick seen, cached because the shutdown path runs off the client thread.
	 */
	private volatile int lastTick;
	/**
	 * The state we came from, which is what separates a region change from a repopulation.
	 */
	private GameState previousGameState = GameState.UNKNOWN;

	@Provides
	TheLedgerConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(TheLedgerConfig.class);
	}

	@Override
	protected void startUp()
	{
		sessionManager = new SessionManager(RuneLite.RUNELITE_DIR);
		canonicalizer = LedgerContainers.caching(this::resolveCanonicalItemId);
		snapshots.clear();
		tickBuffer.clear();
		lastAction = ActionContext.EMPTY;
		previousGameState = GameState.UNKNOWN;

		panel = new LedgerDebugPanel();
		BufferedImage icon = ImageUtil.loadImageResource(TheLedgerPlugin.class, "icon.png");
		navButton = NavigationButton.builder()
			.tooltip("The Ledger")
			.icon(icon)
			.priority(7)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);

		int interval = Math.max(1, config.flushIntervalSeconds());
		flushTask = executor.scheduleWithFixedDelay(this::flush, interval, interval, TimeUnit.SECONDS);

		// Enabling the plugin mid-session must not diff against containers it has never seen.
		clientThread.invokeLater(() ->
		{
			if (client.getGameState() == GameState.LOGGED_IN)
			{
				ensureSession();
				seedMissingBaselines();
			}
		});
	}

	@Override
	protected void shutDown()
	{
		if (flushTask != null)
		{
			flushTask.cancel(false);
			flushTask = null;
		}

		endSession("shutDown");

		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
			navButton = null;
		}
		if (panel != null)
		{
			panel.stop();
			panel = null;
		}

		snapshots.clear();
		tickBuffer.clear();
		resolver = null;
		canonicalizer = null;
		sessionManager = null;
		lastAction = ActionContext.EMPTY;
	}

	// ---- Capture. All of these run on the client thread. ----

	/**
	 * Snapshot, diff, buffer. Nothing is classified here: the other leg of a movement arrives as
	 * a second event in this same tick, and classifying either one alone gets it wrong.
	 * <p>
	 * Mirrored by {@code SnapshotFixtures.containerChanged}.
	 */
	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		int containerId = event.getContainerId();
		if (panel != null)
		{
			// Recorded for every container, tracked or not, so a wrong id constant is visible.
			panel.recordContainerSeen(containerId);
		}
		if (!LedgerContainers.isTracked(containerId))
		{
			return;
		}

		ItemContainer container = event.getItemContainer();
		if (container == null)
		{
			return;
		}

		ContainerSnapshot next = snapshotOf(containerId, container);
		ContainerSnapshot previous = snapshots.get(containerId);
		if (previous == null)
		{
			previous = ContainerSnapshot.unknown(containerId);
		}

		List<ContainerDiffer.Delta> deltas = ContainerDiffer.diff(previous, next);
		if (!previous.isKnown())
		{
			// No baseline, so there is no diff and there never can be one. What the container holds
			// right now is still evidence though: if something else lost exactly these items this
			// tick, the pair is a transfer rather than a loss. Recorded separately so it can
			// corroborate and nothing else.
			tickBuffer.addFirstObservation(next);
			if (panel != null)
			{
				panel.recordSilentReseed();
			}
		}
		tickBuffer.addAll(deltas);
		snapshots.put(containerId, next);

		if (LedgerContainers.isCarried(containerId))
		{
			resolver.noteCarriedReported(containerId);
		}

		if (config.verboseContainerLogging() && !deltas.isEmpty())
		{
			log.debug("container {} deltas {}", LedgerContainers.name(containerId), deltas);
		}
	}

	/**
	 * Resolve the tick that just finished.
	 * <p>
	 * Mirrored by {@code SnapshotFixtures.gameTick}.
	 */
	@Subscribe
	public void onGameTick(GameTick event)
	{
		// Before anything is classified, give every container without a baseline a chance to get
		// one by reading it directly rather than waiting for it to change.
		// Before any early return. This is the last tick the plugin has SEEN, which is what the
		// footer needs; assigning it further down made it the last tick that happened to resolve a
		// movement, so a session ending in lifecycle events wrote a footer whose tick went
		// backwards past the events preceding it.
		lastTick = client.getTickCount();

		seedMissingBaselines();

		if (panel != null && resolver != null)
		{
			panel.setNegativeXpReseeds(resolver.getNegativeXpReseeds());
		}

		if (resolver != null && resolver.isDeathPending())
		{
			resolveDeath(lastTick);
		}

		if (tickBuffer.isEmpty())
		{
			tickBuffer.clear();
			return;
		}
		if (resolver == null)
		{
			tickBuffer.clear();
			if (panel != null)
			{
				panel.recordSuppressed();
			}
			return;
		}

		int tick = lastTick;
		long ts = System.currentTimeMillis();
		List<LedgerEvent> events = resolver.resolveTick(tick, ts, tickBuffer.drain(),
			tickBuffer.getFirstObservations(), tickBuffer.getBecameKnownThisTick(),
			lastAction, this::hasBaseline);
		tickBuffer.clear();

		if (panel != null)
		{
			panel.recordResolvedTick();
		}
		if (config.verboseTickLogging() && !events.isEmpty())
		{
			log.debug("tick {} resolved {} events: {}", tick, events.size(), events);
		}
		for (LedgerEvent resolved : events)
		{
			emit(resolved);
		}
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		// This fires for every left click, including the implicit CANCEL on a click that does
		// nothing. Letting those overwrite the context would discard a deposit or a Grand Exchange
		// confirmation in the tick or two before the client applies it.
		if (NON_INFORMATIVE_ACTIONS.contains(event.getMenuAction()))
		{
			return;
		}

		lastAction = ActionContext.of(
			event.getMenuOption(),
			event.getMenuTarget(),
			event.getParam1(),
			event.getItemId(),
			String.valueOf(client.getGameState()),
			client.getTickCount());
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		if (resolver == null)
		{
			return;
		}
		// getXp() is the lifetime total for the skill, not a delta. The resolver seeds a baseline
		// on the first reading and emits nothing, or a login would log the whole total as a gain.
		LedgerEvent xp = resolver.resolveXp(client.getTickCount(), System.currentTimeMillis(),
			event.getSkill().name(), event.getXp());
		if (xp != null)
		{
			emit(xp);
		}
	}

	@Subscribe
	public void onActorDeath(ActorDeath event)
	{
		if (resolver == null || event.getActor() != client.getLocalPlayer())
		{
			return;
		}
		// The carried state is frozen here and nowhere else. Ordinary snapshot churn afterwards
		// must not be able to redefine what was being carried at the moment of death.
		emit(resolver.recordDeath(lastTick, System.currentTimeMillis(), this::snapshotFor));
	}

	/**
	 * The path a closed window actually takes.
	 * <p>
	 * Closing the client does <b>not</b> call {@link #shutDown()}. RuneLite's window listener
	 * posts {@link ClientShutdown} and then races a ten second timer to {@code System.exit}, so a
	 * plugin that only writes its footer in shutDown never writes one at all — which is exactly
	 * what three consecutive real sessions showed.
	 * <p>
	 * {@code ClientShutdown.waitFor} is the supported way to make that wait deterministic instead
	 * of opportunistic: the work is submitted to the already-injected executor, and the client
	 * blocks on the returned Future up to its own bounded timeout. No shutdown hook, no thread of
	 * our own, nothing that outlives the client, and the client thread is never blocked.
	 */
	@Subscribe
	public void onClientShutdown(ClientShutdown event)
	{
		if (sessionManager == null || !sessionManager.isActive())
		{
			return;
		}
		Future<?> finished = executor.submit(() -> endSession("clientShutdown"));
		event.waitFor(finished);
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState state = event.getGameState();
		GameState previous = previousGameState;
		previousGameState = state;

		if (resolver != null && resolver.isDeathPending())
		{
			if (state == GameState.LOADING)
			{
				// The respawn teleport. This is the transition a death has to survive, and the only
				// one that counts as progress through it.
				resolver.noteRespawnTransition(lastTick);
			}
			else if (INVALIDATING_STATES.contains(state))
			{
				// A logout, a hop, a disconnect. None of these is a respawn, and none of them can
				// be treated as one. The death is abandoned explicitly rather than silently.
				failDeathClosed(state.name());
			}
		}

		if (state == GameState.LOADING && isRegionChange(previous)
			&& config.keepBaselinesAcrossRegionLoad())
		{
			// LOGGED_IN -> LOADING -> LOGGED_IN is a region change. The client does not resend
			// containers for one, so dropping every baseline here absorbs the next real movement
			// and buys nothing. A death still has to survive this transition, so the window is
			// pushed back even though no reset is recorded.
			if (resolver != null)
			{
				resolver.noteRespawnTransition(lastTick);
			}
			if (panel != null)
			{
				panel.recordRegionLoadKept();
			}
			return;
		}

		if (INVALIDATING_STATES.contains(state))
		{
			invalidateBaselines(state.name());
			return;
		}

		if (state == GameState.LOGGED_IN)
		{
			ensureSession();
			// Immediately, not on the next tick. Equipment only fires ItemContainerChanged when it
			// changes, so a session with no gear swap would otherwise leave it UNKNOWN forever and
			// flag every single inventory movement as uncorroborated.
			seedMissingBaselines();
		}
	}

	/**
	 * A LOADING reached directly from LOGGED_IN is a teleport or a region crossing. A LOADING that
	 * follows LOGGING_IN, HOPPING or CONNECTION_LOST is part of a genuine repopulation, and those
	 * states have already invalidated the baselines on their own account.
	 */
	private static boolean isRegionChange(GameState previous)
	{
		return previous == GameState.LOGGED_IN;
	}

	// ---- Session lifecycle ----

	/**
	 * Opens a session, or rotates to a new file when the account has changed.
	 * <p>
	 * A client can switch accounts without restarting. Appending one account's movements to
	 * another's log would corrupt both, so a changed account hash always means a new file.
	 */
	private void ensureSession()
	{
		long accountHash = client.getAccountHash();

		if (sessionManager.isActive())
		{
			if (!sessionManager.needsRotation(accountHash))
			{
				return;
			}
			log.debug("account changed, rotating session file");
			endSession("accountSwitch");
		}

		try
		{
			int tick = client.getTickCount();
			long ts = System.currentTimeMillis();
			SessionHeader header = sessionManager.start(accountHash, worldTypeString(), ts, tick);
			writer = new JsonlEventWriter(sessionManager.getSessionFile(), header,
				JsonlEventWriter.DEFAULT_QUEUE_CAPACITY);
			resolver = new MovementResolver(header.getSessionId(),
				Math.max(0, config.actionContextTicks()));

			if (panel != null)
			{
				panel.setSession(header.getSessionId(), sessionManager.getSessionFile().getName());
			}
			log.debug("ledger session {} -> {}", header.getSessionId(), sessionManager.getSessionFile());
		}
		catch (IOException e)
		{
			log.warn("could not open a ledger session", e);
			writer = null;
			resolver = null;
		}
	}

	private void endSession(String reason)
	{
		if (sessionManager == null || !sessionManager.isActive())
		{
			return;
		}
		if (resolver != null && resolver.isDeathPending())
		{
			// The client is going away with a death unaccounted for. Say so rather than letting the
			// session read as clean.
			failDeathClosed(reason);
		}

		if (writer != null)
		{
			// The footer bypasses the queue, so a full queue cannot eat the one line that
			// distinguishes a clean exit from a crash. Drops during the final drain are not in
			// this figure; the DATA_LOSS marker still records that they happened.
			writer.close(resolver == null ? null : LedgerEvent.builder()
				.schemaVersion(LedgerEvent.SCHEMA_VERSION)
				.sessionId(sessionManager.getSessionId())
				.ts(System.currentTimeMillis())
				.tick(lastTick)
				.type(EventType.SESSION_END)
				.droppedEvents(writer.getDroppedCount())
				.actionContext(reason)
				.build());
			writer = null;
		}
		sessionManager.end();
		resolver = null;
		if (panel != null)
		{
			panel.setSession(null, null);
		}
	}

	/**
	 * Drops baselines and records that it happened.
	 * <p>
	 * Containers repopulate after these transitions, and the first observation of each one only
	 * seeds. Real movements during the transition are therefore missed — the accepted price of
	 * never inventing one. The panel counts silent reseeds so the cost is visible.
	 * <p>
	 * With one exception. Dying triggers a respawn region load, so a transition always follows a
	 * death; reseeding the carried containers there would absorb the entire wipe and DEATH_LOSS
	 * could never fire, no matter how long the window was. While a death is being resolved the
	 * inventory and equipment keep their pre-death baselines, so the wipe is measured against
	 * what was actually being carried.
	 */
	private void invalidateBaselines(String reason)
	{
		int tick = client.getTickCount();
		boolean deathPending = resolver != null && resolver.isDeathPending();

		if (!deathPending)
		{
			tickBuffer.clear();
		}

		for (Integer containerId : new ArrayList<>(snapshots.keySet()))
		{
			if (deathPending && LedgerContainers.isCarried(containerId))
			{
				continue;
			}
			snapshots.put(containerId, ContainerSnapshot.unknown(containerId));
		}
		lastAction = ActionContext.EMPTY;

		if (resolver != null)
		{
			emit(resolver.recordStateReset(tick, System.currentTimeMillis(), reason));
		}
	}

	/**
	 * Asks the resolver whether the death can be settled yet, and logs whatever it concludes.
	 * <p>
	 * Nothing here decides anything: the resolver closes the death on carried-container evidence,
	 * or fails it closed once its safety budget expires. A failure invalidates the carried
	 * baselines through the ordinary mechanism, because continuing to diff against state that
	 * spans an unaccounted death would be worse than reseeding.
	 */
	private void resolveDeath(int tick)
	{
		List<LedgerEvent> events = resolver.tryResolveDeath(tick, System.currentTimeMillis(),
			this::snapshotFor);
		boolean failed = false;
		for (LedgerEvent event : events)
		{
			emit(event);
			failed |= event.hasFlag(LedgerEvent.FLAG_DEATH_RECONCILE_FAILED);
		}
		if (failed)
		{
			invalidateBaselines("deathReconcileFailed");
		}
	}

	private void failDeathClosed(String reason)
	{
		emit(resolver.failDeathClosed(lastTick, System.currentTimeMillis(), reason));
	}

	/**
	 * Reads any tracked container that has no baseline and seeds one from its current contents.
	 * <p>
	 * Waiting for {@code ItemContainerChanged} means a container is only ever seeded when it
	 * changes. Equipment often does not change for a long time, so it stays UNKNOWN while the
	 * inventory and bank are seeded and reporting — and a movement between a seeded container and
	 * an unseeded one produces exactly one leg, which is a phantom. Reading the container
	 * directly removes the asymmetry at its source.
	 * <p>
	 * The bank returns null until its interface has been opened, which is correct: it genuinely
	 * has no contents to know yet, and it stays UNKNOWN.
	 */
	private void seedMissingBaselines()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		for (int containerId : LedgerContainers.TRACKED)
		{
			ContainerSnapshot existing = snapshots.get(containerId);
			if (existing != null && existing.isKnown())
			{
				continue;
			}
			ItemContainer container = client.getItemContainer(containerId);
			if (container == null)
			{
				continue;
			}
			snapshots.put(containerId, snapshotOf(containerId, container));
			// A direct read is pre-existing state, not evidence that anything moved, so it cannot
			// corroborate. It still means this container was UNKNOWN when the tick's deltas were
			// captured, which is what confidence has to be judged against.
			tickBuffer.markBecameKnown(containerId);
			if (panel != null)
			{
				panel.recordEagerSeed();
			}
		}
	}

	private ContainerSnapshot snapshotOf(int containerId, ItemContainer container)
	{
		Item[] items = container.getItems();
		int[] ids = new int[items.length];
		int[] quantities = new int[items.length];
		for (int i = 0; i < items.length; i++)
		{
			Item item = items[i];
			if (item == null)
			{
				continue;
			}
			ids[i] = item.getId();
			quantities[i] = item.getQuantity();
		}
		return ContainerSnapshot.fromRaw(containerId, ids, quantities, canonicalizer);
	}

	/**
	 * Backs {@link MovementResolver.CarriedStateSource}.
	 */
	private ContainerSnapshot snapshotFor(int containerId)
	{
		ContainerSnapshot snapshot = snapshots.get(containerId);
		return snapshot == null ? ContainerSnapshot.unknown(containerId) : snapshot;
	}

	private boolean hasBaseline(int containerId)
	{
		ContainerSnapshot snapshot = snapshots.get(containerId);
		return snapshot != null && snapshot.isKnown();
	}

	// ---- Plumbing ----

	private void emit(LedgerEvent event)
	{
		if (event == null)
		{
			return;
		}
		JsonlEventWriter target = writer;
		if (target == null)
		{
			if (panel != null)
			{
				panel.recordSuppressed();
			}
			return;
		}
		boolean queued = target.enqueue(event);
		if (panel != null)
		{
			panel.record(event, queued);
		}
	}

	/**
	 * Runs on the injected executor, never the client thread.
	 */
	private void flush()
	{
		JsonlEventWriter target = writer;
		if (target == null)
		{
			return;
		}
		target.drain();
		LedgerDebugPanel currentPanel = panel;
		if (currentPanel != null)
		{
			currentPanel.setWriterStats(target.getQueueDepth(), target.getWrittenCount(),
				target.getDroppedCount(), target.getErrorCount(), target.getLastError());
		}
	}

	/**
	 * Backs {@link LedgerContainers.Canonicalizer}. Called on the client thread and memoised by
	 * the caching wrapper, so each item id costs one composition lookup per session.
	 */
	private int resolveCanonicalItemId(int itemId)
	{
		ItemComposition composition = client.getItemDefinition(itemId);
		if (composition == null)
		{
			return itemId;
		}
		// getPlaceholderTemplateId() is 14401 for a placeholder and -1 otherwise. A placeholder is
		// a bank slot reservation, not an item, so it is excluded rather than mapped.
		if (composition.getPlaceholderTemplateId() != -1)
		{
			return LedgerContainers.Canonicalizer.SKIP;
		}
		// getNote() is 799 for a noted item and -1 otherwise; getLinkedNoteId() then gives the
		// unnoted id. Collapsing here is what stops withdraw-as-note looking like two items.
		if (composition.getNote() != -1)
		{
			return composition.getLinkedNoteId();
		}
		// Last, exactly where ItemManager.canonicalize consults its own copy: some items carry a
		// different id when worn than when carried, so without this a graceful hood coming off is
		// one item destroyed and a different one created.
		return WornItemIds.canonical(itemId);
	}

	private String worldTypeString()
	{
		EnumSet<WorldType> types = client.getWorldType();
		if (types == null || types.isEmpty())
		{
			return "STANDARD";
		}
		StringBuilder sb = new StringBuilder();
		for (WorldType type : types)
		{
			if (sb.length() > 0)
			{
				sb.append('|');
			}
			sb.append(type.name());
		}
		return sb.toString();
	}
}
