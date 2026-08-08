package com.nikko.theledger;

import com.nikko.theledger.capture.MovementResolver;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

/**
 * Phase 1 exposes debug controls and nothing else. There is no user-facing behaviour to
 * configure yet — the plugin's whole job is to write a correct log.
 * <p>
 * Values are clamped where they are read rather than constrained here, so a hand-edited
 * profile cannot put the spine into a nonsensical state.
 */
@ConfigGroup(TheLedgerConfig.GROUP)
public interface TheLedgerConfig extends Config
{
	String GROUP = "theledger";

	@ConfigItem(
		position = 1,
		keyName = "verboseTickLogging",
		name = "Log every resolved tick",
		description = "Writes each tick's classified events to the client log. Noisy; use it to "
			+ "check a specific action rather than leaving it on."
	)
	default boolean verboseTickLogging()
	{
		return false;
	}

	@ConfigItem(
		position = 2,
		keyName = "verboseContainerLogging",
		name = "Log every container change",
		description = "Writes each raw container diff to the client log, before classification. "
			+ "Very noisy: the inventory fires constantly."
	)
	default boolean verboseContainerLogging()
	{
		return false;
	}

	@ConfigItem(
		position = 3,
		keyName = "deathWindowTicks",
		name = "Death window (ticks)",
		description = "How long after a death inventory and equipment losses count as DEATH_LOSS "
			+ "rather than an unexplained loss. Adjustable so the default can be checked against a "
			+ "real death."
	)
	default int deathWindowTicks()
	{
		return MovementResolver.DEFAULT_DEATH_WINDOW_TICKS;
	}

	@ConfigItem(
		position = 4,
		keyName = "actionContextTicks",
		name = "Action context window (ticks)",
		description = "How long a menu click stays relevant to classification. The client applies a "
			+ "deposit or a Grand Exchange confirmation a tick or two after the click."
	)
	default int actionContextTicks()
	{
		return MovementResolver.DEFAULT_ACTION_CONTEXT_TICKS;
	}

	@ConfigItem(
		position = 5,
		keyName = "keepBaselinesAcrossRegionLoad",
		name = "Keep baselines across region loads",
		description = "EXPERIMENTAL, off by default. A LOADING that arrives straight from "
			+ "LOGGED_IN is a region change, and the client does not resend containers for one - "
			+ "so dropping every baseline there absorbs the next real movement for no gain. With "
			+ "this on, only a LOADING that follows a login, hop or connection loss reseeds. Turn "
			+ "it on and teleport around: if any unexplained gain appears, turn it back off."
	)
	default boolean keepBaselinesAcrossRegionLoad()
	{
		return false;
	}

	@ConfigItem(
		position = 6,
		keyName = "flushIntervalSeconds",
		name = "Flush interval (seconds)",
		description = "How often the background writer drains the queue to disk."
	)
	default int flushIntervalSeconds()
	{
		return 2;
	}

	// The write queue capacity is deliberately NOT exposed here. It is a constant on
	// JsonlEventWriter, sized so overflow never happens in normal operation. A user who set it
	// low would generate routine DATA_LOSS markers, and that trains everyone to ignore the one
	// signal in the log that must never become noise.
}
