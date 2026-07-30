package com.nikko.theledger.capture;

/**
 * The last menu interaction and game state seen, used to disambiguate movements that would
 * otherwise be indistinguishable from a loss.
 * <p>
 * Some destinations never update a container the client exposes. A deposit box takes items
 * out of the inventory and the bank container does not change. A Grand Exchange offer takes
 * coins or items out of the inventory and there is no container on the other side at all.
 * With no context both look exactly like destroying the items, and a ten million coin buy
 * offer becomes a catastrophic phantom loss.
 * <p>
 * Immutable, and free of client types so the fixtures can construct one directly. The plugin
 * layer builds it from {@code MenuOptionClicked}; {@code widgetGroupId} is the top half of
 * {@code getParam1()}.
 */
public final class ActionContext
{
	public static final ActionContext EMPTY =
		new ActionContext("", "", -1, -1, "", Integer.MIN_VALUE);

	private final String menuOption;
	private final String menuTarget;
	private final int widgetGroupId;
	private final int itemId;
	private final String gameState;
	private final int tick;

	private ActionContext(String menuOption, String menuTarget, int widgetGroupId, int itemId,
						  String gameState, int tick)
	{
		this.menuOption = menuOption == null ? "" : menuOption;
		this.menuTarget = menuTarget == null ? "" : menuTarget;
		this.widgetGroupId = widgetGroupId;
		this.itemId = itemId;
		this.gameState = gameState == null ? "" : gameState;
		this.tick = tick;
	}

	/**
	 * @param param1 the packed widget id from {@code MenuOptionClicked.getParam1()}. The
	 *               interface group is its top 16 bits; a negative value means the click was
	 *               not on an interface.
	 */
	public static ActionContext of(String menuOption, String menuTarget, int param1, int itemId,
								   String gameState, int tick)
	{
		int group = param1 < 0 ? -1 : param1 >>> 16;
		return new ActionContext(menuOption, menuTarget, group, itemId, gameState, tick);
	}

	/**
	 * Fixture convenience: build directly from an interface group id.
	 */
	public static ActionContext onInterface(String menuOption, int widgetGroupId, int tick)
	{
		return new ActionContext(menuOption, "", widgetGroupId, -1, "LOGGED_IN", tick);
	}

	public String getMenuOption()
	{
		return menuOption;
	}

	public String getMenuTarget()
	{
		return menuTarget;
	}

	public int getWidgetGroupId()
	{
		return widgetGroupId;
	}

	public int getItemId()
	{
		return itemId;
	}

	public String getGameState()
	{
		return gameState;
	}

	public int getTick()
	{
		return tick;
	}

	public boolean isEmpty()
	{
		return this == EMPTY || tick == Integer.MIN_VALUE;
	}

	public boolean isBankInterface()
	{
		return widgetGroupId == LedgerContainers.IFACE_BANKMAIN
			|| widgetGroupId == LedgerContainers.IFACE_BANKSIDE;
	}

	public boolean isDepositBoxInterface()
	{
		return widgetGroupId == LedgerContainers.IFACE_BANK_DEPOSITBOX;
	}

	public boolean isGrandExchangeInterface()
	{
		return widgetGroupId == LedgerContainers.IFACE_GE_OFFERS
			|| widgetGroupId == LedgerContainers.IFACE_GE_OFFERS_SIDE
			|| widgetGroupId == LedgerContainers.IFACE_GE_COLLECT
			|| widgetGroupId == LedgerContainers.IFACE_GE_VIEWONLY;
	}

	/**
	 * A deposit whose destination container will not update.
	 * <p>
	 * The deposit box interface is the certain case. A "Deposit" option anywhere that is not
	 * the bank is also treated as one, because with the bank open both legs appear and net
	 * out on their own — so reaching this method at all means the destination is invisible.
	 */
	public boolean looksLikeDeposit()
	{
		if (isDepositBoxInterface())
		{
			return true;
		}
		if (isBankInterface())
		{
			return false;
		}
		return startsWithIgnoreCase(menuOption, "deposit");
	}

	/**
	 * A Grand Exchange interaction: offer placement, offer abort, or a collection.
	 */
	public boolean looksLikeGrandExchange()
	{
		return isGrandExchangeInterface();
	}

	private static boolean startsWithIgnoreCase(String s, String prefix)
	{
		return s.length() >= prefix.length()
			&& s.substring(0, prefix.length()).equalsIgnoreCase(prefix);
	}

	/**
	 * Short, human-readable form written to the event's {@code actionContext} field.
	 */
	public String describe()
	{
		if (isEmpty())
		{
			return null;
		}
		StringBuilder sb = new StringBuilder();
		sb.append(menuOption.isEmpty() ? "?" : menuOption);
		if (!menuTarget.isEmpty())
		{
			sb.append(':').append(menuTarget);
		}
		if (widgetGroupId >= 0)
		{
			sb.append('@').append(widgetGroupId);
		}
		return sb.toString();
	}

	@Override
	public String toString()
	{
		return "ActionContext[" + describe() + " tick=" + tick + "]";
	}
}
