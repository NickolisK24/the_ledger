# The Ledger

A RuneLite plugin that records a durable, local, append-only event log of everything that
happens to one account.

This repository currently contains **Phase 1: the event spine**. There is no pricing, no
GP/hr, no overlay, and no UI beyond a debug panel. The single thing Phase 1 has to get right
is this:

> A logged play session can be replayed from disk and reconstructs the account's item
> movements accurately, with zero phantom gains or losses.

Everything later — cost accounting, time-motion attribution, statistics, collection log
ranking — is computed from this log. If the log is wrong, all of it is wrong.

Nothing leaves the machine. The plugin makes no network calls of any kind.

---

## What it writes

One JSON object per line, under `~/.runelite/the_ledger/`, one file per session:

```
session-20260730-024500-3f2504e0.jsonl
```

Every file begins with a `SESSION_START` header line, then an append-only stream of events:

```json
{"schemaVersion":1,"sessionId":"3f2504e0-...","ts":1700000000000,"tick":0,"type":"SESSION_START","accountHash":123456789012345,"worldType":"MEMBERS","flags":[]}
{"schemaVersion":1,"sessionId":"3f2504e0-...","ts":1700000012600,"tick":21,"type":"ITEM_MOVEMENT","category":"TRANSFER","containerId":93,"itemId":536,"qty":-1000,"actionContext":"Withdraw-X:Bank booth@12","flags":[]}
{"schemaVersion":1,"sessionId":"3f2504e0-...","ts":1700000012600,"tick":21,"type":"ITEM_MOVEMENT","category":"TRANSFER","containerId":95,"itemId":536,"qty":1000,"actionContext":"Withdraw-X:Bank booth@12","flags":[]}
{"schemaVersion":1,"sessionId":"3f2504e0-...","ts":1700000013200,"tick":22,"type":"XP_GAIN","skill":"PRAYER","xpDelta":252,"flags":[]}
```

Fields, in the order they always appear:

| Field | Meaning |
| --- | --- |
| `schemaVersion` | Bumped when the meaning or shape of a field changes. Version 1 files stay readable. |
| `sessionId` | UUID for this session. |
| `ts` | Epoch milliseconds. |
| `tick` | Client tick the event was resolved on. |
| `type` | `SESSION_START`, `SESSION_END`, `ITEM_MOVEMENT`, `XP_GAIN`, `PLAYER_DEATH`, `STATE_RESET`, `DATA_LOSS`. |
| `category` | Economic classification of a movement. Absent on everything else. |
| `containerId` | 93 inventory, 94 equipment, 95 bank. Absent when not container-scoped. |
| `itemId` | Canonical item id: notes collapsed, placeholders excluded. |
| `qty` | Signed. Positive is an increase in that container. |
| `skill` / `xpDelta` | Set on `XP_GAIN` only. |
| `droppedEvents` | Set on `SESSION_END` only. Total events the writer could not persist. |
| `actionContext` | Short description of the menu interaction that explains the movement. |
| `flags` | Audit flags. Always present, possibly empty. |

Nullable fields are omitted rather than written as `null`.

### Categories

Phase 1 assigns four:

- **`TRANSFER`** — moved between the player's own containers. **Zero economic weight.** Banking
  is not profit and it is not loss.
- **`DEATH_LOSS`** — removed from inventory or equipment inside the death window.
- **`UNCLASSIFIED_GAIN`** / **`UNCLASSIFIED_LOSS`** — a real change whose cause the spine cannot
  yet attribute.

`REVENUE`, `CONSUMABLE`, `CHARGE`, `REPAIR`, `DEATH_FEE` and `TRANSPORT` are declared so the
schema is stable, but Phase 1 never assigns them. There are no price fields.

### Audit flags

Every inference the spine makes leaves a flag behind, so a movement that was reasoned about
rather than observed can always be found again:

- `INFERRED_DEPOSIT_BOX` — an inventory or equipment decrease attributed to a deposit box,
  whose destination container never updated.
- `INFERRED_GRAND_EXCHANGE` — an offer placement, abort or collection. The Grand Exchange has
  no container on the far side at all.
- `UNKNOWN_BANK_BASELINE` — the transfer was inferred while the bank interface had not been
  opened this session, so the other leg could not be confirmed.
- `DEATH_WINDOW` — resolved inside the window opened by a death.

### The log is self-describing about its own gaps

Two things can put a hole in a session, and both are written into the stream rather than left
for a consumer to infer:

- **`STATE_RESET`** — every container baseline was invalidated. Nothing before that line can be
  diffed against anything after it.
- **`DATA_LOSS`** — events were produced but not written, because the write queue filled or the
  disk failed. Exactly one marker is emitted per session, on the first drop, stamped with the
  tick the loss began on and placed immediately after the last event that *was* recorded. The
  running total lands on `SESSION_END` as `droppedEvents`.

A third failure needs naming and is deliberately *not* one of the above: the client can simply
be killed. Nothing is written badly, but everything still queued is gone and no `SESSION_END`
ever lands.

`JsonlEventReader.ReplaySession` reports these as two distinct predicates, because they are
different failures with different remediation:

| | `isCompromised()` | `isTruncated()` |
| --- | --- | --- |
| **What is wrong** | Holes *inside* the body | A *tail* is missing |
| **Position** | Unknown | Known: the end of the file |
| **Size** | Unknown | Unknown |
| **Cause** | Queue overflow, disk failure, damage mid-file | No `SESSION_END`: alt-F4, sleep, connection drop, crash |
| **Body trustworthy** | No | Yes |
| **A consumer must** | **Exclude the whole session** | **Discard only the final window — never the session** |

**Do not collapse these.** Crashing out of this game is routine, so treating a missing
`SESSION_END` as corruption would discard a large share of perfectly good data. Equally, a hole
of unknown position inside the body cannot be worked around: a missing kill is
indistinguishable from a kill that did not happen, so a hole does not add noise — it biases
every figure downward, and nothing in the file says by how much.

Note that a half-written final line is *truncation*, not corruption. `getMalformedLines()`
counts every unreadable line; `getMalformedBodyLines()` counts only those with readable lines
after them, and it is the body count that feeds `isCompromised()`.

`getDroppedEvents()` returns null rather than zero when there is no `SESSION_END`, so an unknown
total is never mistaken for a clean one.

Dropping is backpressure of last resort. The write queue holds 65,536 events, roughly twenty
minutes of sustained heavy activity with the writer completely stalled, so it should never be
reached in normal operation. **Its capacity is a constant and is deliberately not exposed as a
config item** — a user who set it low would generate routine `DATA_LOSS` markers, and that
trains everyone to ignore the one signal in the log that must never become noise.

### Privacy

The only account identifier ever written is `accountHash`. The account name, display name and
login are never recorded, because these files may be shared or published later.

---

## How the diffing works

`ItemContainerChanged` hands over the **entire container**, not a change, and it fires
constantly. Recovering what actually moved is the whole problem, and it is where profit
trackers go wrong. Five rules do the work:

1. **An absent baseline is `UNKNOWN`, not empty.** A container observed for the first time only
   seeds; it produces no events. This is what makes logging in, hopping worlds, loading a
   region, reconnecting, and opening the bank for the first time free of phantom gains.
2. **Item identity is canonicalized before anything is diffed.** Noted items collapse onto
   their unnoted id and placeholders are dropped entirely, so withdrawing as a note is one item
   moving rather than one destroyed and a different one created.
3. **Deltas are buffered for the whole tick** and resolved on the next `GameTick`.
   Cross-container movement fires two separate events in the same tick, and in isolation the
   first is a loss and the second is a gain.
4. **Transfers net out symmetrically across every container touched in the tick.** Banking,
   equipping, unequipping and withdrawing as notes all reduce to this one rule. Both legs are
   still emitted, so the movement stays auditable — they just carry no economic weight.
5. **Destinations that never update a container are inferred from the menu action** and always
   flagged. The deposit box and the Grand Exchange are both invisible otherwise; unhandled, a
   ten million coin buy offer logs as a catastrophic loss.

Deaths are handled explicitly: `ActorDeath` for the local player opens a short window during
which inventory *and equipment* losses are `DEATH_LOSS` rather than unexplained. The window
length is configurable so the default can be checked against a real death.

Experience needs the same care as items. `StatChanged.getXp()` is the **lifetime total** for
the skill, not a change, so the first reading after a login or a state reset seeds a baseline
and emits nothing. Treating it as a delta would log the account's entire experience as a
single gain.

---

## Known Phase 1 gaps

These are real limitations, written down now so Phase 2 does not rediscover them as bugs.

### Untracked containers

Phase 1 diffs the inventory, equipment and bank. Items inside these are invisible to container
diffing, so moving items into or out of them looks like a loss or a gain:

- Rune pouch
- Looting bag
- Seed vault
- POH costume room and other POH storage
- STASH units
- Raid storage (Chambers of Xeric, Theatre of Blood, Tombs of Amascut)
- Death storage and the item reclaim service

The debug panel lists **every container id the client reports**, tracked or not, with a count.
That list is how you find out which of these an account actually touches, and it is also the
only thing that would catch the container id constants being wrong — if they were, the spine
would diff nothing, log nothing, and every unit test would still pass.

### Dose and charge ladders

Drinking a dose replaces a 4-dose potion with a 3-dose one, which are different item ids. Phase
1 reports that literally: one `UNCLASSIFIED_LOSS` and one `UNCLASSIFIED_GAIN`. It does not know
they are the same item partially consumed. Recognising dose and charge ladders is what
`CONSUMABLE` and `CHARGE` are reserved for.

**Note for Phase 2.** When that lands, a dose decrement must be costed as the **marginal
difference between the two ladder rungs**, not the full value of the rung that was consumed.
Drinking one dose of a four-dose potion costs roughly a quarter of the potion, not all of it;
charging the full rung would overstate consumable expense by a factor of four and make every
GP/hr figure for potion-heavy content wrong. The Phase 1 test
`potionDoseConsumedIsALossOfTheFourDoseAndAGainOfTheThreeDose` records both sides of the
movement precisely so that the marginal calculation is possible later — the gain of the 3-dose
item is not noise to be suppressed, it is the other half of the arithmetic.

### State transitions swallow the movements inside them

Invalidating every baseline on `LOADING` means the first real change after a teleport or region
load is absorbed as a re-seed rather than logged. Missing an event is the accepted price of
never inventing one — the success condition is zero phantoms, not zero omissions. The debug
panel counts silent reseeds so the cost is visible rather than hidden.

### Trades, shops and other players

Trading, shops, and every other container are out of scope for Phase 1 and will show up as
unclassified movements or as untracked container ids in the panel.

---

## Debug panel

The side panel shows the live session id and file, queue depth, written and dropped counts,
per-`EventType` and per-`MovementCategory` counters, every container id seen with its event
count, silent reseed count, and the last 50 events. It is the only window into whether the
spine is behaving.

Two config toggles add verbose client-log output: one per resolved tick, one per raw container
diff. Both are noisy by design and meant for checking a specific action.

---

## Building

```
./gradlew build          # compile and run the tests
./gradlew run            # launch a development client with the plugin loaded
```

Requires a JDK (11 or newer) and network access to `https://repo.runelite.net`, which is where
`net.runelite:client` resolves from.

No third-party dependencies are declared beyond what `runelite-client` already provides:
Lombok, JUnit 4, and Gson used through its streaming API. There is no reflection, no JNI, no
subprocess execution, and nothing is downloaded at runtime.

### Tests

Fixtures are hand-built snapshot sequences fed through the real pipeline; no running client is
needed. The negative fixtures matter at least as much as the positive ones — each of the
following must produce **zero** gain or loss events:

- Deposit 200 items into an open bank, and deposit several different stacks
- Withdraw items as notes
- Deposit via deposit box with the bank container never updating, including worn items
- A deposit whose effect lands a tick after the click
- Equip and unequip gear
- Bank tab reorganisation with no net quantity change
- Placeholder creation on a final withdrawal, placeholder release, and refilling placeholders
- Log out and back in with full containers
- Hop worlds mid-session
- Connection loss and reconnect
- Region load
- Place a buy offer, place a sell offer, abort an offer, collect an offer, collect to bank

Plus: a death produces exactly one `PLAYER_DEATH` and only `DEATH_LOSS` events, never
`UNCLASSIFIED_LOSS`; a truncated final JSONL line is skipped with every preceding event still
loading; and a queue overflow emits exactly one `DATA_LOSS` marker no matter how many events
are lost, with the total surviving a write/read round trip on `SESSION_END`.

Integrity signals are pinned in all four combinations: a crashed session is truncated but not
compromised, a session with drops and no `SESSION_END` is both, damage inside the body is
compromised but not truncated, and a clean session is neither.
