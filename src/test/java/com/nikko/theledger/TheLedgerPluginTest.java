package com.nikko.theledger;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/**
 * Launches a development client with the plugin loaded, via the {@code run} Gradle task.
 * <p>
 * Not a unit test. The spine's tests are in {@code capture} and {@code store} and need no
 * client; this exists so a real session can be played and the debug panel's counters compared
 * against a manual tally.
 */
public class TheLedgerPluginTest
{
	@SuppressWarnings("unchecked")
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(TheLedgerPlugin.class);
		RuneLite.main(args);
	}
}
