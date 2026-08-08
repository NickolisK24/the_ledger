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
- **`DEATH_LOSS`** — disappeared from the combined carried state across a death.
- **`UNCLASSIFIED_GAIN`** / **`UNCLASSIFIED_LOSS`** — a real change whose cause the spine cannot
  yet attribute.


`REVENUE`, `CONSUMABLE`, `CHARGE`, `REPAIR`, `DEATH_FEE` and `TRANSPORT` are declared so the
schema is stable, but Phase 1 never assigns them. There are no price fields.

**Category is the economic axis only.** How much the spine could actually observe is a separate,
orthogonal question answered by the flags. A movement can be directionally a gain while its
counterpart was invisible — so a consumer sums by category and filters by confidence
independently, and no category query has to special-case a member that is not an economic kind.

### Audit flags

Every inference the spine makes leaves a flag behind, so a movement that was reasoned about
rather than observed can always be found again:

- `INFERRED_DEPOSIT_BOX` — an inventory or equipment decrease attributed to a deposit box,
  whose destination container never updated.
- `INFERRED_GRAND_EXCHANGE` — an offer placement, abort or collection. The Grand Exchange has
  no container on the far side at all.
- `UNKNOWN_BANK_BASELINE` — the transfer was inferred while the bank interface had not been
  opened this session, so the other leg could not be confirmed.
- `DEATH_WINDOW` — part of a death: the PLAYER_DEATH itself, or a loss reconciled against it.
- `DEATH_BASELINE_HELD` — a state transition arrived while a death was unresolved, so the carried
  containers kept their baselines instead of being reseeded.
- `COUNTERPARTY_UNSEEDED` — a container this movement could have exchanged with had no baseline,
  so its leg could not appear even if it happened. The quantity must not be believed.
- `FIRST_OBSERVATION_RECONCILED` — both legs of this transfer were proven with the help of a
  container observed for the first time. Provenance, not economics: the category is still
  `TRANSFER` and the quantity is still exact.
- `DEATH_RECONCILE_FAILED` — a death was recorded and could not be reconciled. Accompanies a
  `DATA_LOSS`.
- `COUNTERPARTY_UNTRACKED` — a one-legged bank movement. The bank can only change by transfer, so
  the other side was storage the spine does not track. The quantity must not be believed.

### One-legged movements, and why confidence is a flag rather than a category

`UNKNOWN` stops the container that has no baseline from reporting phantoms. It does nothing for
that container's **partner**. A real session banked worn items while the equipment container had
never been observed, and the bank leg was logged on its own — ten items appearing from nowhere.
Transfer netting needs *both* sides seeded, and nothing used to check that precondition.

Two things now prevent it:

1. **Containers are seeded eagerly** — at `startUp`, on reaching `LOGGED_IN`, and as a per-tick
   backstop — by reading each one directly with `getItemContainer`. Equipment only fires
   `ItemContainerChanged` when it *changes*, so waiting left it `UNKNOWN` while the inventory and
   bank were live, and that asymmetry flagged 22 of 22 unclassified losses in a real session.

   **This does not close the hole, because `getItemContainer` returning null is ambiguous.** An
   empty equipment container is never allocated by the client, so null means *either* "empty" *or*
   "not loaded". A live session logged in wearing nothing, got null on every tick, and equipment
   stayed `UNKNOWN` all the way to the first equip.

   **`UNKNOWN` is not `EMPTY`, and a null carried container is never assumed empty.** A successful
   read of the inventory proves the client is alive; it proves nothing about the equipment
   container. Assuming empty would seed a baseline that was never observed, and the first real
   update would then read as a gain — trading a phantom loss for a phantom gain. The bank is the
   same: unopened means unknown, not empty.

2. **A first observation is carried through the tick as evidence, and may only corroborate.**
   When a container goes from `UNKNOWN` to observed there is no diff and there never can be one,
   so its contents are held separately from the deltas, where they cannot be mistaken for a
   change. At resolution they may match an opposite loss another container measured in the same
   tick; matched quantities become `TRANSFER` on both legs, flagged
   `FIRST_OBSERVATION_RECONCILED`.

   Everything else about it produces **no event at all**. Whatever it holds that nobody lost is
   the gear you were already wearing, and it becomes baseline and nothing more. The rule is
   one-directional: a first observation can never explain a *gain* elsewhere, because a container
   with no baseline cannot be shown to have given anything up.

   This is the production case it exists for: the inventory lost three graceful pieces and
   equipment reported its very first observation already holding exactly those three. They now
   reconcile as six transfer legs instead of three losses the player never took.

3. **Confidence is judged at capture time, not at resolution time.** Equipment became known during
   that same tick, so the counterparty rule saw a seeded container by the time the tick resolved
   and flagged nothing — the three losses read as fully trustworthy. A container that became known
   during a tick, whether by first observation or by direct read, is treated as unseeded for
   everything captured in it.

4. **A residual movement whose counterpart is invisible carries a counterparty flag** — either
   `COUNTERPARTY_UNSEEDED` (the counterpart container had no baseline) or
   `COUNTERPARTY_UNTRACKED` (a one-legged bank movement). The category still records which
   direction the items went and `qty` keeps its sign, because that is real information. The flag
   records that the quantity must not be believed.

   **Anything summing `UNCLASSIFIED_GAIN` or `UNCLASSIFIED_LOSS` must exclude flagged events.**
   An unflagged unclassified movement is the spine asserting a real change; a flagged one is the
   spine saying it saw one side of something and cannot vouch for it.

#### How the two flags are decided, and why it reads no strings

**The decision is made on container identity alone.** It reads no menu option, no target text
and no widget id — nothing a game revision or a localised client could change underneath it.

A rune pouch deposit and a monster drop are both one-legged gains with no observable
counterpart. They are told apart by **where they land**: a drop arrives in the inventory, a pouch
deposit changes the bank, and the bank cannot receive a drop. That is the whole rule.

| Movement in | Flagged when | Flag |
| --- | --- | --- |
| Inventory | equipment has no baseline | `COUNTERPARTY_UNSEEDED` |
| Equipment | inventory has no baseline | `COUNTERPARTY_UNSEEDED` |
| Bank | inventory or equipment has no baseline | `COUNTERPARTY_UNSEEDED` |
| Bank | everything observable was observed and it still did not balance | `COUNTERPARTY_UNTRACKED` |
| anything else | never | — |

The two flags answer different questions and their preconditions differ deliberately:

- `COUNTERPARTY_UNSEEDED` is returned **only while a counterpart is actually `UNKNOWN`.** The
  moment that container is seeded, movements stop being flagged for this reason.
- `COUNTERPARTY_UNTRACKED` does not depend on seeding, because the counterpart was never a
  tracked container in the first place. It is structural, not observational.

**The default is unflagged, and that direction is chosen on purpose.** A movement in a container
that gains items from the world — the inventory above all — is never flagged by either rule, so
a real drop is never suppressed. Over-counting a phantom is recoverable by inspection; discarding
revenue is not.

#### The flags are load-bearing, so they have their own discipline

Because `phantomCount` excludes flagged events, a bug that *adds a flag* would make a spurious
event vanish from the metric rather than fail a fixture — the inverse of the safety property the
negative fixtures exist to provide. Over-applying `COUNTERPARTY_UNSEEDED` would turn every
negative fixture green while the spine invented movements.

So the rule is pinned exhaustively: every container against every combination of seeded
baselines, one assertion each, twenty-four cases. Two invariants ride along with every case —
`COUNTERPARTY_UNSEEDED` never appears while all counterparts are seeded, and an unflagged result
never appears while one is not. The second is also a runtime `assert`, so the dev client (which
runs with `-ea`) fails loudly if the two rules ever drift apart.

**If both containers are seeded and a movement still resolves one-legged, that is a real phantom
and it is emitted unflagged so that it counts.**

#### The one remaining display-string dependency, disclosed

The counterparty rule reads no strings, but a *different* inference does.
`ActionContext.looksLikeDeposit()` falls back to a case-insensitive `"deposit"` prefix on the
menu option when the click is not on the deposit-box interface (group 192). That is a display
string, with the same revision and localisation fragility as the menu labels replaced by
`MenuAction` constants elsewhere.

It is kept because it is narrow, it is always flagged (`INFERRED_DEPOSIT_BOX`), and its failure
direction is the safe one: on a localised client the inference simply stops firing, so a deposit
becomes an `UNCLASSIFIED_LOSS` — a visible phantom loss — rather than silently suppressing a real
movement. Nothing gains items from it. Worth replacing with a widget-id rule in Phase 2 once the
full set of deposit-capable interfaces is known.

An unseeded **bank** is deliberately not treated as a plausible counterpart for the inventory or
equipment: the bank interface has to be open to move anything into or out of it, and opening it
populates the container. So a kill drop before the bank is ever opened is still a real gain.

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

**The boundary of that rule.** Classifying damage by position assumes that only the *final*
write can be partial. That holds for this output — a single writer, append-only, one flush per
batch, each line written whole — but it is an assumption about the writer, not a guarantee the
reader can check. Two consequences worth knowing before trusting the predicates:

- Two or more adjacent malformed lines at the end of a file are all classified as truncation and
  none as corruption. If something ever produced interleaved partial writes, a hole in the body
  could hide in that tail and `isCompromised()` would not see it.
- Damage that leaves a line *syntactically valid but semantically wrong* — a plausible number in
  place of another — is invisible to both predicates. There is no checksum per line, and Phase 1
  does not add one.

So `isCompromised() == false` means "no detectable hole", not "provably intact". If a future
phase needs a stronger guarantee than that, the place to add it is a per-line or per-batch
checksum, and it would be a schema change.

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
5. **Destinations that never update a container are inferred from the interface the click landed
   on** and always flagged. The deposit box and the Grand Exchange are both invisible otherwise;
   unhandled, a ten million coin buy offer logs as a catastrophic loss.

Rule 5 reads structure and never display text. The interface group ids it uses are verified
against upstream `net.runelite.api.gameval.InterfaceID`: `BANKMAIN = 12`, `BANKSIDE = 15`,
`BANK_DEPOSITBOX = 192`, `GE_VIEWONLY = 200`, `GE_COLLECT = 402`, `GE_OFFERS = 465`,
`GE_OFFERS_SIDE = 467`. A live session independently showed `@192` on deposited soul runes, law
runes and a medallion, classified as inferred deposit-box transfers.

At a bank there is nothing to infer: `BANKMAIN` and `BANKSIDE` both report their side of the
movement, so an ordinary deposit or withdrawal nets into a plain `TRANSFER` carrying no flags at
all. The deposit box is the one verified surface that takes items into the bank while the bank
container stays silent, and group 192 is the whole test — every widget inside a deposit box, the
inventory it renders included, is packed under that group, so no component id is needed and none
is consulted.

**There is no display-string fallback.** A menu option beginning "Deposit", on any surface that
was not a bank, used to be treated as an invisible deposit. That was wrong in both directions at
once. A localised or renamed option silently loses the inference — the label is presentation, and
presentation is not evidence. Worse, a "Deposit" on a surface that is not a bank at all was
promoted to a confident bank transfer on no evidence, which does not merely mislabel the movement:
it fabricates bank contents that were never there, and a later phase would price them. When the
structure is unrecognised the movement stays an auditable `UNCLASSIFIED_LOSS`. Unknown must not
become a known deposit through prose.

The cost of that rule is stated rather than hidden: a deposit into storage the spine does not
model — a seed vault, a group ironman shared bank, a deposit imp — now logs as an unflagged
unclassified loss instead of an invented bank transfer. The items really did leave the inventory,
so the direction is true and the movement is auditable; what is missing is the destination, which
is exactly what the log now admits. Those surfaces are Phase 2 scope, and each one needs its
group id confirmed against a real client before it earns an inference.

### Why rules 3 and 4 stop at the tick boundary

Netting is per tick, and that is an assumption about the client rather than a proof, so it is
written down here and pinned by `CrossTickAssumptionTest` rather than left implicit.

What RuneLite documents: `GameTick` is "an event called once every game tick, **after all packets
have processed**". The implementation in `Hooks.tick()` matches — a server tick sets a flag, and
the next client cycle replays the deferred event bus and then posts `GameTick`, incrementing the
tick counter afterwards. So whether a container change is posted immediately during packet
processing or deferred to the replay, it reaches subscribers **before** the `GameTick` for the
cycle that caused it. The plugin buffers on the container events and resolves on `GameTick`, which
is the only ordering property it actually needs, and that property is documented rather than
assumed.

What nothing documents: whether the *server* always sends both container updates for one action in
the same tick's packets. That is invisible from the API. Every ordinary operation observed across
several live sessions — bank withdrawals and deposits including large rune stacks, equip and
remove, deposit worn items — has resolved both legs inside one tick, but "not yet observed" is not
"cannot happen".

**No cross-tick matching window exists, and none will be added on suspicion.** The cost is
asymmetric: a window wide enough to rejoin a straddled transfer is also wide enough to swallow a
real pickup followed by a real drop, or a monster drop followed by eating the food that killed it.
Trading a hypothetical missing pair for a real class of wrongly-netted movements makes the log
less trustworthy, not more.

If a straddle ever does occur it is already visible in the log with no extra instrumentation. The
signature is an `UNCLASSIFIED_LOSS` in one tracked container on tick N and an **unflagged**
`UNCLASSIFIED_GAIN` in another on tick N+1, same canonical item id, equal and opposite quantity.
`CrossTickAssumptionTest` asserts exactly that shape, so the thing to grep for is pinned in code.

**The 13393 / 22400 anomaly is closed, and it was not this.** A live session showed a movement of
Xeric's talisman (13393) and a movement of Drakan's medallion (22400) both carrying an action
context reading like `Withdraw-1:Drakan's medallion`. The items are unrelated and there is no
canonicalisation between them. The resolver could not have paired them under any circumstances:
`resolveTick` groups deltas by canonical item id and nets only within one group, and the action
context is passed through to the emitted event as a label without a single branch pairing on it.
The shared label is a context that outlived its click by a tick or two — which it is designed to
do, because the client applies a deposit or a Grand Exchange confirmation after the click that
caused it — stamped onto the next movement to come along. **Action context is observational
metadata, never authority.**

Deaths are handled explicitly, and **as a lifecycle sequence rather than a duration**.

The old model started a five-tick window at `ActorDeath`. A live death reached its respawn
transition at tick 269 having died at 262, so the window had already expired: the carried
baselines were reseeded, the wipe was absorbed, and the recovered items surfaced later as
unflagged gains — a missing loss and a fabricated revenue from one event. Widening the number
would have moved the cliff, not removed it, because the gap is a death animation plus a server
round trip and neither is constant.

```
IDLE ──ActorDeath──► AWAITING_RESPAWN ──LOADING──► AWAITING_WIPE ──both carried report──► IDLE
```

- **`ActorDeath` freezes the carried state** — inventory and equipment as they stood at that
  instant. Ordinary snapshot churn afterwards cannot redefine it.
- **The respawn transition advances the death rather than ending it**, and the carried baselines
  are held across it so the wipe is measured against what was actually being carried. The
  `STATE_RESET` carries `DEATH_BASELINE_HELD`.
- **Both carried containers reporting closes it, immediately.** Evidence ends the death, not a
  clock, which is what keeps the state short in practice while still surviving real timing.
- **Loss is measured on the combined carried state.** An item that was worn and is now in the
  inventory was not lost; only what disappeared from inventory and equipment together counts, and
  only the quantity that actually went.

**A death owns what it observed, for the whole tick.** A carried container's change is marked as
belonging to the death when it is **captured**, not when it is classified, and that mark is what
suppresses it — in both directions, gains as well as losses. Reconciliation against the frozen
baseline is the authority on what a death did to the carried state; classifying the same deltas as
well would count the movement twice.

Capture-time ownership is not a detail. The client delivers the container changes first and the
game tick afterwards, and the tick reconciles the death *before* it classifies what it buffered —
so reconciliation routinely succeeds on the strength of exactly the observations still sitting in
the buffer, and closes the death. A suppression keyed to "is a death pending right now" therefore
reads IDLE by the time it is consulted and lets those observations straight through. A live death
did exactly that: five correct `DEATH_LOSS` lines followed, on the same tick, by the same five
losses again as `UNCLASSIFIED_LOSS`. Ownership has to outlive the phase that granted it.

Consequences, stated plainly:

- Ownership survives the reconciliation that closes the death, and lapses at the end of the tick
  like everything else the buffer holds. Ordinary classification resumes on the next tick.
- It covers gains too. Protected items arriving in the inventory are not revenue, and an item
  that ends up worn is not an equipment gain — the reconciler already knows both were carried
  before and are carried still.
- It applies only to the inventory and the equipment. A bank change during a pending death is
  ordinary banking and classifies as it always did.
- If the death fails closed, its observations stay inside the `DATA_LOSS` marker rather than
  being released as ordinary movement. That is deliberate: an unmatched carried gain during an
  unresolved death is dropped rather than reported as revenue. Dropping it leaves a gap the
  `DATA_LOSS` already declares; reporting it would fabricate income out of respawn bookkeeping,
  and inventing economic movement is the one failure Phase 1 cannot absorb.

**Failing closed.** A logout, a world hop or a connection loss during a pending death is not a
respawn and is never treated as one; nor is the client shutting down. Any of them, and a safety
budget of 25 ticks from the death itself, end the death by emitting a `DATA_LOSS` flagged
`DEATH_RECONCILE_FAILED` and invalidating the carried baselines. That budget is **not** "the point
after which the death is probably over" — it is the point at which reconciliation has
demonstrably failed and must say so rather than resume clean accounting. `DATA_LOSS` is the right
marker because it is exactly what has happened: the log knows economic events occurred and cannot
say what they were, and `isCompromised()` already picks it up.

There is no death-window setting any more. There is no duration left to tune.

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

**The rune pouch is the one to fix first, and the magnitude is why.** A single logged session
produced these bank movements with no counterpart leg anywhere:

```
t580  +14875  +15911  +15851  +14694   "Deposit runes"
t582  -16000  -16000                   "Withdraw-All"
t583  -16000  -11966                   "Withdraw-All"
```

Nothing was earned or lost — runes moved between the bank and a pouch. At Phase 2 prices that is
**millions of GP of fabricated swing per session**, in both directions, from one item type. A
GP/hr figure computed over a session containing pouch use would be worse than no figure at all.

Phase 1 still does not track the pouch, but it no longer reports these as gains and losses: a
bank movement with no counterpart leg is flagged `COUNTERPARTY_UNTRACKED`, because the bank can
only change by transfer. That contains the damage without pretending to explain it.
**Rune pouch support is a Phase 2 blocker, not a nice-to-have** — until it lands those quantities
are recorded but unattributable, and Phase 2 must exclude them rather than price them.

#### Worn items carry different ids

Some items have one id in the inventory and a different one when worn — all graceful including
every recolour, penance gloves, boots of lightness. Without a mapping, taking a piece of graceful
off logs as one item destroyed in the equipment and a different item created in the inventory:
two real-looking events for a movement that changed nothing, and one that transfer netting cannot
match because the ids differ.

`WornItemIds` carries that mapping, **87 entries mirroring
`net.runelite.client.game.ItemManager.WORN_ITEMS`**. RuneLite maintains that table for exactly
this reason, so:

> **This table must be reviewed whenever RuneLite changes `WORN_ITEMS` upstream.** It is a copy,
> not a reference, and a copy goes stale silently. `WornItemIdsTest.tableIsTheExpectedSize` is
> the tripwire for a merge truncating it, but only upstream review catches an *added* entry.

It lives in the RuneLite-facing layer beside the plugin, **not in `capture`** — that package's
whole value is that it imports nothing from the client, and this table is nothing but client
constants. It is written with named `gameval.ItemID` constants rather than bare integers so an
entry can be checked against upstream by eye. The `Canonicalizer` seam and its cache are
unchanged: the table is consulted from the plugin's implementation of that seam, as the last
step, exactly where `ItemManager.canonicalize` consults its own copy.

#### The blind spot in `COUNTERPARTY_UNTRACKED`, and its direction

This flag is not a clean signal. **On a bank GAIN it conflates two populations that look
identical to the spine:**

1. **Untracked-container shuffling** — runes moving between a pouch and the bank, a looting bag
   being emptied, a seed vault. No economic value is created; the flag is exactly right.
2. **Direct-to-bank delivery** — rewards and some purchases that land in the bank instead of the
   inventory, typically because the inventory was full. **This is real revenue**, and the flag is
   exactly wrong.

Both are a bank quantity going up with no counterpart leg anywhere. Nothing in the container
diff distinguishes them.

Today this is harmless: Phase 1 assigns no prices, so a mislabelled bank gain costs nothing.
**In Phase 2 it silently deletes income** — the failure direction that is worse than
over-counting, because an inflated number gets questioned and a missing one does not.

> **Constraint for Phase 2.** A `COUNTERPARTY_UNTRACKED` bank **gain** is `UNRESOLVED`, not
> "excluded". It must be surfaced for review — listed, totalled, attributable to a time and an
> item — never silently dropped from revenue. Excluding it from a GP/hr figure is acceptable
> only if the figure also reports how much was excluded and why. A bank gain that turns out to be
> a reward is income the account genuinely earned, and a tracker that quietly loses it is wrong
> in the way that matters most.

Bank **losses** do not carry this ambiguity: nothing delivers items *out* of a bank without a
transfer, so a flagged bank loss really is storage shuffling.

**How to separate them, when Phase 2 gets there.** The strongest move is not to discriminate at
all — it is to **eliminate population 1 by tracking it**. The rune pouch, the looting bag and the
seed vault are enumerable, and once their contents are diffable those movements become
two-legged and net to `TRANSFER` on their own. Whatever is still flagged afterwards is delivery.
That is a better answer than any heuristic and it is already a Phase 2 blocker for other reasons.

Weaker signals, listed so they are not rediscovered, **none of them built in Phase 1**:

- **Inventory slot occupancy at the moment of the gain.** Direct-to-bank delivery happens
  *because* the inventory is full, so a full inventory is a genuine prior. Cheap —
  `ItemContainer.count()` against `size()` — but far from conclusive, since pouch shuffling often
  happens with a full inventory too. A prior, not a test.
- **Monotonicity across the session.** Shuffling is bidirectional and roughly conserves quantity;
  delivery only ever adds. An item that only ever gains in the bank is more likely delivered.
- **Chat messages.** Many deliveries announce themselves. This is a display string, with all the
  localisation and revision fragility that implies, so it would have to be an inference and be
  flagged as one.

The same containment covers the looting bag, the seed vault and any other bank-adjacent storage.
It does **not** cover storage that exchanges with the *inventory* rather than the bank, because a
one-legged inventory movement is ordinary — that is exactly what a kill drop looks like. Filling
a looting bag from the inventory still reads as an `UNCLASSIFIED_LOSS`.

### State transitions and how often they fire

A logged session recorded 27 `STATE_RESET` events across 916 ticks, every one of them `LOADING`
— roughly one every 34 ticks in ordinary play with teleports. Each one drops every baseline, so
the next real movement in each container is absorbed as a re-seed rather than logged.

The likely reason is that not all `LOADING`s are alike. A `LOADING` reached directly from
`LOGGED_IN` is a teleport or a region crossing, and the client does not resend containers for
one. A `LOADING` that follows `LOGGING_IN`, `HOPPING` or `CONNECTION_LOST` is part of a genuine
repopulation — and those states have already invalidated the baselines on their own account
before `LOADING` is ever reached.

So the discrimination is on the **previous** state, and it ships behind a config toggle,
**"Keep baselines across region loads", now default ON.**

**Confirmed against a live session:** with the toggle on, **11 teleports produced zero
`STATE_RESET` events and zero unflagged `UNCLASSIFIED_GAIN`.** Containers do not repopulate on a
region change. The toggle stays so the old behaviour can be restored, but the hypothesis is no
longer a hypothesis.

| | Toggle OFF (default, current) | Toggle ON (experimental) |
| --- | --- | --- |
| `LOGGED_IN` → `LOADING` | reseeds everything | keeps every baseline, no `STATE_RESET` |
| `LOGGING_IN` / `HOPPING` / `CONNECTION_LOST` → `LOADING` | reseeds | reseeds, unchanged |
| Movement right after a teleport | absorbed | logged |
| Death through the respawn load | works | works |

What to compare in the panel if you ever want to re-test it:

| Panel line | Toggle OFF | Toggle ON | Meaning |
| --- | --- | --- | --- |
| `STATE_RESET` counter | one per region load, tens per session | only logins, hops, disconnects — low single digits | the change working |
| `seeded` | roughly 2–3 × the reset count | 2–3 for the whole session | baselines no longer being rebuilt |
| `kept-rgn` | 0 | one per teleport | the new path being taken |
| `reseeds` | climbs with resets | near zero after startup | absorbed movements |
| `UNCLASSIFIED_GAIN` unflagged | some | **must not increase** | **the pass/fail test** |

**The last row is the verdict**, and in the confirming session it was zero.

Death does not depend on the setting either way: the carried containers hold their baselines
through a respawn regardless, and on the experimental path an open death window is pushed back
by the transition itself even though no reset is recorded.

### Experience baselines

`StatChanged.getXp()` is a lifetime cumulative total, so a baseline stays valid across anything
that does not change *which account* is being read. It survives a region load, a world hop and a
logout and login to the same account. Clearing them on every `STATE_RESET` threw away the next
gain in every skill, 27 times in one session, for no correctness benefit.

Baselines live and die with the resolver, which is replaced when the session rotates to a
different account hash — the only event that genuinely invalidates them.

**Experience cannot decrease in this game.** A negative computed delta is therefore proof of a
stale or wrong baseline and never a real event: the baseline is silently repaired, nothing is
emitted, and the occurrence is counted on the panel's `xp-bad` line. That number must stay at
zero; a non-zero value means something is handing out stale totals and the XP stream should not
be trusted until it is explained.

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

### Dependency floor: the client bundles Gson 2.8.5

**Gson 2.8.5, released in 2018. No Gson API newer than that is available in any phase.** This is
not a preference, it is the ceiling — the client's bundled version is what the plugin compiles
and runs against, and Maven Central will happily hand a local build something newer that does not
exist in production. That is exactly how `JsonParser.parseString` (added in 2.8.6) compiled
locally and failed the real build.

**Verify any Gson call before writing it**, against the jar rather than the documentation:

```
javap -cp <gson jar> com.google.gson.JsonParser | grep parse
unzip -p <gson jar> META-INF/MANIFEST.MF | grep Bundle-Version
```

The same applies to anything else arriving transitively. The local test harness is pinned to the
versions the client actually resolves — Gson 2.8.5, JUnit 4.12 — and the pin is verified with the
command above rather than assumed. A test environment more permissive than production will
certify code that cannot run.

### Closing the client does not call `shutDown()`

`Plugin.shutDown()` is called when a plugin is disabled. It is **not** called when the client
window is closed. RuneLite's window listener posts a `ClientShutdown` event and then starts a
thread that waits up to ten seconds for registered consumers, calls `stopNow()`, and exits.

Three consecutive real sessions therefore had no `SESSION_END`, which disabled two of the three
integrity mechanisms at once: `isTruncated()` was permanently true so it could not distinguish a
crash from a clean exit, and `getDroppedEvents()` was permanently null because the total is only
recorded on the footer.

The footer is now written from a `ClientShutdown` subscriber: the work is submitted to the
already-injected executor and the returned `Future` is handed to `ClientShutdown.waitFor`, so the
client blocks on it under its own bounded timeout. Deterministic rather than opportunistic, no
shutdown hook, no thread of our own, nothing that outlives the client, and the client thread is
never blocked.

**The footer also bypasses the write queue.** Enqueueing it made the one line that says "this
session ended cleanly" the first casualty of a full queue — so a compromised session was also
permanently unreadable as clean, exactly when it most needed explaining. It is written directly
under the writer's lock after everything else has gone out.

**To verify on a real file:** the last line is a `SESSION_END` carrying a `droppedEvents` field.

### Commit signing

`commit.gpgsign` is set to **false** in this repository's local config, deliberately. It
overrides whatever is set globally on the machine, so commits here are unsigned rather than
signed by whichever key happens to be configured — a key that is not the repository owner's
would attribute the work to someone else and show as Unverified on GitHub.

If you want signed commits, enable it locally *with your own key*:

```
git config --local user.signingkey <your key>
git config --local commit.gpgsign true
```

Do not simply flip `commit.gpgsign` back on and inherit a global `user.signingkey`.

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

Death is exercised in its real ordering rather than a convenient one — `ActorDeath`, then the
container wipe, then `GameStateChanged(LOADING)`, and the variant where the load lands before the
wipe. One-legged movements are covered in both directions for bank↔inventory and
inventory↔equipment, plus the rune pouch shape where every container is seeded and the
counterpart is simply not a container at all.

The counterparty flags are pinned exhaustively — three containers against all eight seeding
combinations, plus the invariant that a both-seeded one-legged movement stays unflagged and
counts as a real phantom, and that the flag stops the moment its container is seeded. The
decision's independence from menu strings is asserted by running the same scenario through six
different menu options, including an empty one and a non-English one.

Experience baselines are covered for teleport, relog to the same account, world hop, account
switch, and the negative-delta guard. The region-load toggle is pinned in both settings,
including the cost the conservative default pays, so the A/B has a fixed reference on the code
side.

**Fixture realism is itself a thing to test.** Every one of the defects above got past a green
suite, because the fixtures seeded only the containers a scenario happened to touch and modelled
death without the load that always follows it. Fixtures now start from `loggedIn()`, which mirrors
what eager seeding produces on a real client: inventory and equipment readable immediately, bank
unreadable until opened.

The death double-count was the same lesson again, at the level of *callback order*. Twenty-two
death fixtures were green while a live death emitted its wipe twice, because they all asked
reconciliation for a verdict the instant a container reported and threw the tick's deltas away.
The client does not work that way: it delivers container changes and then, separately, a game
tick, and the bug lived entirely in the gap between the two. `SnapshotFixtures.gameTick` now
mirrors `onGameTick` in full — death resolution included — and `DeathOwnershipTest` drives nothing
but `containerChanged` and `gameTick`, so a fixture sees the ordering production sees. Seven of
its fourteen cases fail against the previous commit's classification logic.

The same failure shape bit the build once more, from a different direction: the local test
harness resolved Gson from Maven Central and got a newer version than the client ships, so
`JsonParser.parseString` compiled locally and did not exist in production. **A test environment
that is more permissive than production will certify code that cannot run.** The harness is now
pinned to the versions the client actually resolves — Gson 2.8.5, JUnit 4.12 — and that pin is
verified rather than assumed.
