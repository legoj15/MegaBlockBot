# Block safety

Design: `AI-PLAYER-DESIGN.md` 3.5. Bot nodes only (1.21.1). Code: `game.safety`; `BlockSafety` is the one question
(`mayBreak`, `mayUseContainer`), asked by every task and by the navigation engine's cost function.

## Rules, in the order they apply

1. Protected radius (`SafetyConstants.PROTECTED_RADIUS`, a horizontal square) around the owner's current respawn point
   (online or not) and the overworld's world spawn. Never lifted.
2. Claims (`ClaimProvider`, none ships). Never lifted.
3. A block this body placed: allowed.
4. Beds and villager workstations it did not place: refused. Never lifted by an allowance.
5. Containers it did not place, and blocks a player or another bot placed: refused unless an `Allowance` of this body
   covers the position. These two rules are all an allowance lifts. A goal closes its allowance, or the brain calls
   `closeAll(body)` when the goal ends; one left open ends when its body dies, despawns or is replaced.
6. Everything else is terrain.

A body that cannot be answered for refuses: no published zones for its owner yet, a level whose record is not loaded yet
(off the server thread only), a container position that is not loaded.

## Supply containers

The owner can name a container for a body to take from (`use chest at <x> <y> <z>`; design 3.5, `BotMemory.supply`). The
body may then *use* it (open it, take from it, put into it) as it may its own, and may never *break* it. Using was never
subject to the protected radius (only breaking is), so a named chest inside the radius is used too; a claim still refuses
it. Naming lifts the container rule and the workstation rule for that block, not the bed rule (a bed holds nothing to take
and cannot be named). A double chest is named as one inventory (both halves), and a half joined to it later is not named:
using needs both halves, so the pair is refused until the owner names it again. Naming is the owner's word for a block at
a position, and counts only while that block stands there (`SupplyChests.stand`, the one rule the tasks, the snapshot and
this check share): `mayUseContainer` lets a named container through only when the block at the position, and for a pair at
the second half too, is the one that was named, so a barrel (also a job site) put where a named chest stood is refused like
any other container the body did not place. A position that is not loaded is not decided and so not allowed. The check only
reads: a task that finds another block there (either half of a pair) drops the word, and one that finds the position not
loaded decides nothing and drops nothing.

Putting into a container asks the same question. The `deposit` task asks `mayUseContainer` before it opens the container,
with an `Allowance` that covers exactly that container (both halves of a double chest) open for the length of the question:
the stockpile goal names the container, which is the owner's word for it for that goal. The allowance lifts only the
container rule (as it always did), so a claim, a bed, or a job site such as a barrel the body did not place still refuse;
name such a barrel with `use chest at` to stock it. Nothing in `game.safety` changed for this.

## Threads

`mayBreak` is callable from any thread and allocates nothing when it allows. Off the server thread it trusts the block
state it is handed; on the server thread it reads the level and uses the level's own block (a stale snapshot from a
caller never decides, and never prunes a record). `mayUseContainer`, `noteBotPlaced` and `placedBlocks` are server
thread only. Zones are published by the server thread every `ZONE_REFRESH_TICKS` ticks; a new level gets its record
within a tick.

## Two-block things

The loader reports a placement at one position only. A door and a tall plant (`DOUBLE_BLOCK_HALF`) and a bed (`PART` and
`FACING`) answer for the half nobody recorded through the other half. A double chest is two block entities, each recorded
when its own half was placed: breaking is per half, using needs both halves to be the bot's (or allowed), because they
are one inventory.

## What the record cannot see

The placed-block record is fed by two loader events, a player placing a block and a player breaking one
(`docs/platform-layer.md`, `onBlockPlaced` / `onBlockBroken`). Everything else that changes a block is invisible:

- Blocks set by commands (`/setblock`, `/fill`, `/place`, functions), structures and world generation, and blocks set by
  other mods' code. They read as terrain, not as player builds.
- Tools that change a block in place: stripping a log, flattening a path, tilling farmland, waxing, and the like. The
  record holds the block type it saw, so the position then holds another block and reads as stale: the record is dropped
  the next time that position is asked about, and the block reads as terrain. The same goes for blocks that change by
  themselves (grass to dirt, concrete powder to concrete, decaying leaves).
- Blocks removed by explosions, pistons, fluids and falling blocks leave a record behind; it is pruned the next time that
  position is asked about, and only on what the level holds there. A block a piston or a landing sand moves to a new
  position is not recorded there.
- Blocks placed before the mod was installed, and by an entity that is not a player (an enderman).

What covers a base the record does not know is the protected radius, which is why a bed is protected by position and not
by record. A mod that exposes claims fills the same gap through `ClaimProvider`.
