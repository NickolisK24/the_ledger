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
 * <b>Classification reads structure, never display text.</b> Only {@link #getWidgetGroupId()} —
 * the interface the click landed on — decides anything. {@code menuOption} and {@code menuTarget}
 * are carried for the audit trail and are written into the event's {@code actionContext} field,
 * but nothing branches on them. A "Deposit" prefix used to be a fallback for identifying an
 * invisible destination, and it was wrong in both directions at once: it is a localised,
 * revision-sensitive label, so a translated client silently loses the inference, while a
 * "Deposit" on some surface that is not a bank at all — a minigame hopper, a storage the spine
 * does not model — was promoted to a confident bank transfer it had no evidence for. Where the
 * structure is unknown the movement stays an auditable unclassified one. Unknown must not become
 * a known deposit through prose.
 * <p>
 * The context also outlives the click by design, for a tick or two, because the client applies a
 * deposit or a Grand Exchange confirmation after the click that caused it. That means an
 * unrelated movement can be <i>labelled</i> with a stale context. It is a label and nothing more:
 * see {@link MovementResolver#resolveTick}, which pairs movement legs by canonical item identity
 * and never by context.
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

	/**
	 * The two surfaces where the bank container itself reports the movement.
	 * <p>
	 * {@code BANKMAIN} is the bank window, {@code BANKSIDE} the inventory panel beside it, and an
	 * item click while banking carries whichever of the two it was rendered in. Both legs of the
	 * movement are observable on these surfaces, so nothing has to be inferred and this method
	 * exists to say so rather than to drive a classification.
	 */
	public boolean isBankInterface()
	{
		return widgetGroupId == LedgerContainers.IFACE_BANKMAIN
			|| widgetGroupId == LedgerContainers.IFACE_BANKSIDE;
	}

	/**
	 * The deposit box: the one verified surface that moves items into the bank without the bank
	 * container reporting anything.
	 * <p>
	 * Group identity is the whole test. No component id is consulted, because the group is already
	 * unique to this surface — every widget inside a deposit box, the inventory it renders
	 * included, is packed under group 192, which is what a live session's {@code @192} action
	 * contexts showed for deposited soul runes, law runes and a medallion.
	 */
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
	 * A Grand Exchange interaction: offer placement, offer abort, or a collection.
	 */
	public boolean looksLikeGrandExchange()
	{
		return isGrandExchangeInterface();
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
