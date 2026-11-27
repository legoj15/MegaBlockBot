# AI Player Mod — Design

Working name: MegaBlockBot (repo name; the mod id/display name can change before first release).
Status: pre-implementation design, 2026-11-08; corrected 2026-11-09 after checking claims against
SprawlCrafting, BuildCraft, NeoForge and upstream repos; M0.5 spike recorded 2026-11-24 (§2.1,
§2.2, §3.4). Audience: agents implementing it. Planned
work lives in GitHub Issues, not here.

## 1. Goal

A server-side AI player that takes natural-language goals ("find a book of mending", "equip
yourself with iron armor", "go build a quarry and bring it back") and achieves them through
legitimate survival play — in vanilla **and modded** worlds.

Two-fold split, fixed from the start:

- **Language layer (LLM):** turns chat into typed goals, picks strategies when the planner offers
  a choice, reports progress. Never drives per-tick movement, never edits the world.
- **Body (deterministic):** planner + task state machines + navigation + an always-on reflex
  layer (self-defense, survival). Keeps working when the model is slow, offline, or wrong.

Secondary goal: be a mod-development tool. "Can a fresh survival player actually obtain item X
from this modpack?" is a question the planner can answer statically, and the agent can prove
in a world.

### Non-goals (for now)

- Mineflayer / external-client bots. They can't follow modded content reliably; the body is a
  server-side player entity.
- Driving the user's own client. McDevBridge does that for dev automation; it is a test harness
  here, not the body.
- Letting the LLM write and run code (the Voyager/early-Mindcraft approach). Unsafe and slow,
  and it fails on modded content.
- PvP, griefing, multi-agent coordination. Revisit after M5.

## 2. Prior art and base decision

Checked 2026-11-08; PlayerEngine corrected 2026-11-24. All the prior-art mods are Fabric except
PlayerEngine, whose `*-arch` branches are Architectury builds with a NeoForge module (§2.2).
Mindcraft is an external Mineflayer client, not a mod.

| Project | Licence | Notes |
|---|---|---|
| [zoyluoblue/mc_aiplayer](https://github.com/zoyluoblue/mc_aiplayer) ("AIBot") | MIT | 1.21.3. Closest match to this design: "LLM chooses intent, goals define completion, deterministic tasks execute". 63 LLM tools, 38 task state machines, typed goals with postconditions, a `strict_survival` profile, already has fishing + trading tasks. Its own A* navigation. ~146 stars, active. |
| [Goodbird-git/PlayerEngine](https://github.com/Goodbird-git/PlayerEngine) | LGPL-3.0 | AltoClef's task engine moved server-side, on an embedded copy of Automatone (server-side Baritone). Most mature gather/craft/navigation behaviour. Behind Player2 NPC. Fabric + NeoForge via Architectury on branches `1.21.1-arch`, `1.21.8-arch`, `1.21.10-arch`. |
| [adevivo/ai-companion](https://github.com/adevivo/ai-companion) | LGPL-3.0 | 1.20.1, bundles a PlayerEngine fork, any OpenAI-compatible endpoint incl. local llama.cpp. |
| [kylejckson/CraftDroid](https://github.com/kylejckson/CraftDroid) / MineAgent | LGPL-3.0 | 1.21.1 fake player + LLM tool calls + memory. Small. |
| [Mindcraft](https://github.com/kolbytn/mindcraft) | — | Mineflayer. Reference for LLM prompting/tooling only. |

Correction to earlier advice: adevivo's fork is not the "cleanest licence" — it inherits
PlayerEngine's LGPL-3.0. AIBot is the only MIT one.

**Decision (2026-11-08, settled by the M0.5 spike, §2.1–2.2):**
- **Architecture: reimplement AIBot's layering on NeoForge.** Its MIT licence matches
  SprawlCrafting and McDevBridge, its layering matches §1, and its fishing and trading tasks are
  useful behaviour references. Its contracts are taken; its classes are not (§2.1).
- **Navigation: port Automatone's pathing and movement from PlayerEngine's `common` module into a
  separate LGPL-3.0 module** (§2.2). AIBot's A\* is not used. LGPL-3.0 code copied into the tree
  makes those files LGPL; keeping the port in its own module/jar keeps the rest MIT.
- Neither is a fork that tracks upstream. Both start from a specific commit, recorded where the
  code lands.

### 2.1 M0.5 spike result (2026-11-24, AIBot commit `a029fa6`, 2026-09-02)

**Decision: reimplement AIBot's layering cleanly on NeoForge. Lift its contracts and a few
hazard rules, not its classes.** The main tree is now ~66K lines (task/ 36.5K, goal/ 9.3K,
pathfinding/ 1.7K, the rest brain, persistence, commands). What it has and what we take:

| Piece | AIBot | Take? |
|---|---|---|
| Goal + postcondition | Sealed `Goal` (9 records), sealed `GoalPredicate` evaluated against an immutable, Minecraft-free `GoalSnapshot`; "done" = predicate SATISFIED; a goal result is PARTIAL when some matched. | **Shape, yes** (§3.4). It is ~600 lines and is the one part that already matches this design. Rewrite rather than copy: its snapshot collector has side effects (capability decisions) and the goal set is hard-wired to its planner. |
| Task lifecycle | `Task` interface + 110-line `AbstractTask` with guarded transitions (start from PENDING only, pause from RUNNING, resume from PAUSED, abort/cancel no-op on terminal states). | **Shape, yes** (§3.4). |
| Planner | `GoalPlanner`: ~2.3K-line hand-coded expander per goal kind, ~60-entry hand recipe table, falls back to crafting recipes only, reads the live world (not off-thread safe), mining policy embedded. A second, separate recipe planner in `craft/CraftingHelper`. | **No.** solver-core + acquisition resolver replace it (§4). |
| Executor | `GoalExecutor`: 6K-line singleton; `submit` alone is ~1.3K lines; 900 lines of checkpoint codec; builds ~15 task classes directly and names ~30. | **No.** Its replan budgets are worth keeping (§3.4). |
| Tasks | 38 task classes, largest 5.2K lines (`OreDigTask`), helpers copy-pasted across 7 files, 377 `fail("…")` sites with ~270 distinct string reasons routed by `startsWith`. | **No.** Read individual tasks (fishing, trading, ore dig lava handling) as behaviour references when writing ours. |
| Reflex | `DangerWatcher` (1.2K lines) + `SurvivalGuard` + `NavSafetyNet` + `StuckWatcher`, LIFO pause stack. Two hard-coded "survival task" lists that disagree. | **Ideas only**: the resume gate (§3.4, Preemption) and the hazard rules (§2.2). |
| Body | `ServerPlayerEntity` subclass + fake `ClientConnection` + six mixins (two of them body-related); no Carpet dependency. Movement: sets `forwardSpeed`/`sidewaysSpeed`, but every jump, drop, descent, swim step and sneak-edge shift is a bounded one-block `teleport`, and rescues teleport further (gated off in `strict_survival`). Its comments disagree on why: one says the server never runs `travel()` for its body (no passive gravity), another says gravity does pull it down. Checked 2026-11-26 against 1.21.1 bytecode: the second is right. `ServerPlayer.doTick()` runs `Player.tick` → `aiStep` → `travel`; AIBot calls it, but applies its inputs after it, one tick late. | **No.** Violates "never teleports" (§3.1). Carpet's fake player walks on plain inputs written before `doTick()` (docs/handoff/body-reference.md); #6 proves ours does. |
| Navigation | See §2.2. | **No.** |
| LLM | One OpenAI-compatible client (`DeepSeekApiClient`), 63 tools, async off-thread, never per-tick; terminal goal result injected into the next model turn. | Pattern only. Our tool set is deliberately smaller (§3.3). |
| Persistence | Gson `runtime.json` per world, schema-versioned, atomic writes, read-only on schema mismatch; saves mission spec + checkpoint map, re-plans on restore. | **Pattern, yes** (§3.4), stored in player data instead. |

Why not lift: every class is written against Yarn names and Fabric, so lifting means a full
remap anyway; the reusable parts are small; and the large parts are exactly the ones this design
replaces (planner, executor) or rejects (teleport movement, string failures). Lifting the task
layer drags most of `GoalExecutor` with it. Its tests would not carry either: confidence sits in
~590 GameTests plus "source contract" tests that assert on source text, not behaviour; no unit
test calls the planner or `TaskManager`.

Licence: nothing is copied, so no MIT notice is owed. If a class is lifted later, keep its
copyright header and add AIBot's licence text to the jar's licences folder.

### 2.2 Navigation verdict

**AIBot's A\* cannot meet M1(b).** It is a block-grid A\* with six moves (walk, diagonal, jump up
one, drop up to the safe-fall limit, dig through, pillar up). Against the M1(b) check:

- Water: no. `Standability` rejects any cell with fluid at feet or head as a place to stand; a
  comment says the clientless body cannot swim. Lakes and rivers are walls.
- Bridging: no horizontal place-and-walk move, only pillaring straight up. A ravine is crossed by
  going down and out, or not at all.
- Digging: flat cost 8 regardless of tool or hardness, and only a whitelist of stone, dirt, sand,
  gravel and ores. Logs, leaves, planks, netherrack and terracotta are impassable.
- Range: a single synchronous search on the server thread, capped at 50 ms and 10,000 nodes for
  walking (4,000 for the dig fallback, 24,000 for mining approach), start to goal, no segmenting (a weighted ε=3 mode trades optimality for reach). 200 blocks of mixed
  terrain very likely exceeds that budget (not measured; the evidence row below agrees).
- No teleports: no. Jumps, drops and descents are executed as one-block teleports (§2.1, Body).
- Its own evidence: `docs/CAPABILITY_MATRIX.md` row `navigate_120` (natural terrain, long
  distance) is 0/4, status BLOCKED.

Rules to carry into our navigation (#7), whatever engine it uses: never open a dig cell whose
sides or top touch lava, except the target cell itself, where a task first seals the lava source
beside it (AIBot logs this as `ore_dig_fluid_seal`); never dig a cell with a falling block (sand, gravel) above its head cell;
treat the blocks in AIBot's `Standability.isDangerous` (lava, fire, soul fire, cactus, magma,
campfires, sweet berry bush, wither rose, powder snow, pointed dripstone) as never-stand cells.

**Plan: port Automatone's pathing and movement, taken from PlayerEngine's `common` module, into a
separate LGPL-3.0 module.** Facts checked 2026-11-24 against PlayerEngine branch `1.21.1-arch`
(commit `aa4ad2d`, 2026-01-29) and Automatone:

- PlayerEngine embeds Automatone (Ladysnake's server-side Baritone fork, LGPL-3.0) as
  `com.player2.playerengine.automaton`: 278 files, ~27.7K lines, in the Architectury `common`
  module, Mojang mappings, with no Fabric or NeoForge imports. It does import PlayerEngine's root
  class (logger, mod id, executor, a few tags) and an enchantment helper; the port replaces
  those. A NeoForge module (one bootstrap class) and a Fabric module sit beside it; `common` also
  carries 17 mixins, which the port has to re-check. Branches `1.21.8-arch` and `1.21.10-arch`
  also exist, so two of our six versions (1.21.1 and 1.21.10, §5) have an upstream to diff
  against.
- Movement is input-driven: `InputOverrideHandler` writes the entity's `xxa`/`zza` (strafe and
  forward input), jumping and sneaking each tick. No teleports. The entity PlayerEngine drives is
  not a player: it is `AutomatoneEntity extends LivingEntity`, shipped only as a binary jar in
  `libs/` (its old `FakeServerPlayerEntity` was deleted in `2fad4d3`). The engine takes a
  `LivingEntity` plus three provider interfaces (inventory, interaction manager, hunger); the port
  points it at our `ServerPlayer` body and replaces `LivingEntityInteractionManager`'s hand-rolled
  block breaking with the player's own `ServerPlayerGameMode`, so breaking fires the normal
  events and tool rules (docs/handoff/automatone-port.md).
- Checked in code 2026-11-26: segmented long paths with the next segment precomputed, path search
  off the server thread (time-budgeted, 500 ms first plan), parkour, sneak-back-place bridging,
  pillaring, tool-aware breaking. Not as listed: this copy has no working chunk cache, and the
  off-thread search reads live chunks through an accessor with no snapshot or lock. Its
  enchantment helper returns the Efficiency level for every enchantment; the port does not carry
  it over.
- Swimming is the open risk. `allowSwimming` is off by default: with defaults the engine paths
  along a lake's top layer holding jump and treats deep and flowing water as blocked. With the
  setting on, deep water opens. Neither is tested upstream. Measured in #7 (`BotNavGameTests`):
  with `allowSwimming` off a body crosses a 3-deep pool at the surface holding jump (96 ticks for
  8 blocks); on, it was faster (65) and still at the surface, but it stops the jump-holding and,
  with `ignoreBreath` on, opens underwater paths nothing keeps from drowning the body, so a body
  keeps it off. Flowing water was a wall ("no path"); a new `allowFlowingWater` setting, on for a
  body, lets it wade a current. `ignoreBreath` is off for a body since #27: the search then spends the
  body's air on every move with its head under water, starts from the air it has now and refuses a
  move that would spend more, so no path outlasts a breath. The engine also never breaks a block with
  water directly above it or beside it (its cost is infinite, so it is never a mining target).
- Upstream Automatone is stale (Fabric only; `main` last committed 2021-05, newest activity on
  its version branches 2022), and upstream Baritone
  steers the local client player, so neither is a better base than PlayerEngine's copy.
  PlayerEngine's licence is LGPL-3.0 in its repo (Modrinth lists MIT; the repo file wins).

This makes #7 the largest M1 issue: porting an engine, not writing a feature. Still no drop-in:
PlayerEngine as a jar brings its own task engine, LLM integration and Player2 service.

## 3. Architecture

```
chat ──► Language layer (LLM, tool calls) ──► typed Goal
                                                 │
                                                 ▼
                          Planner ────────────────────────────────┐
                          ├─ target resolution (registry search)  │
                          ├─ solver-core  (recipe graph)          │ produces a Goal tree
                          ├─ Acquisition resolver (leaf → source) │
                          ├─ Strategy registry (trade reroll, …)  │
                          └─ cost model                           │
                                                 ▼                │
                          Executor: task state machines ◄─────────┘
                                                 │
                          Navigation + Actions ──► world
                                                 ▲
                          Reflex layer (always on, preempts) ─────┘
                          Perception ──► planner + LLM (visible state only in strict mode)
```

### 3.1 Body

- A direct `ServerPlayer` subclass with a stub network connection. **Not** a NeoForge
  `FakePlayer` subclass: `FakePlayer` is built for block-breaking machines, so it doesn't tick,
  ignores damage and can't die, and every one of those would have to be overridden back.
  Reference implementation: Carpet's `EntityPlayerMPFake` (fake connection, spawn/respawn,
  player-list handling).
- **Movement is simulated server-side.** A real player's entity tick (`ServerPlayer.doTick()`,
  which runs `aiStep` and `travel`) is called only from its connection's tick, and its position
  is then overwritten from the client's movement packets. A body's stub connection is never
  ticked, so the body's own `tick()` writes its movement input (`xxa`/`zza`, jumping, sneaking,
  sprinting, look direction) and then calls `doTick()` itself, as Carpet does. Inputs go in before
  `doTick()`, or they act a tick late. `ServerPlayer.setPlayerInput` is not the way in: it only
  writes for a passenger. Navigation outputs those inputs, never teleports. Verified in #6
  (`BodyGameTests`, run by `scripts/gametest.ps1`): on 1.21.1 the body falls, takes fall damage,
  walks, jumps, steps up a one-block ledge and swims up under plain inputs, and `ServerLevel` calls
  its `tick()` once per server tick (every player in the level, riders through `rideTick`).
- It has a real inventory, real interaction distance, and real tool-tier rules.
- A client-less player is visible to the rest of the game. Decided in #6, each with its evidence:
  - Tab list: listed, like a player (`ClientInformation.allowsListing` true; the tab entry is
    always sent as listed). `listedInTabList` checks the join packet.
  - Sleeping: a body never blocks the night skip. An awake body does not count as an active
    player in `SleepStatus` (mixin on `SleepStatus.update`; a sleeping body would still count).
    `doesNotBlockSleep` measures it against an awake ordinary player as control.
  - Advancements and stats: vanilla keeps them (saved with the body); a body's advancements are
    not announced in chat (mixin on `PlayerAdvancements.award` hands the callback a display
    with the announcement off, so the earned event still fires). `advancementsAreNotAnnounced`
    measures it, with a real connection as listener and control.
  - Packets "to all players": the body's listener and connection swallow every packet, skip
    NeoForge's payload check, and keep nothing (`send`, `flushChannel` and `runOnceConnected` are
    no-ops). The connection does own an unwritten `EmbeddedChannel`: NeoForge reads connection
    attributes off `Connection.channel()` for every player in `placeNewPlayer`, and a connection
    with no channel throws there (seen in the first GameTest run).
  - Mob spawning and targeting treat a body as a player, which is wanted (it must be attackable):
    `ChunkMap.skipPlayer` and `playerIsCloseEnoughForSpawning` exclude only spectators,
    `Level.getNearestPlayer` and `NearestAttackableTargetGoal` read the level's player list, and
    `Mob.checkDespawn` asks `getNearestPlayer`. Read from the 1.21.1 source, and run:
    `mobsTargetBody` has an ordinary zombie pick the body as its target.
- Bound to an owner; it only takes goals from its owner (or ops). The owner can pause, resume
  and cancel its goal by command (§3.4). The owner's UUID and name are saved with the body;
  `mayCommand` is true for the owner's own player (not any entity that carries the owner's UUID)
  and for any permission-level-2 source (the console runs at 4). Spawn rules, for a source below
  permission level 2 (`BodyManager.checkMaySpawn`): only a player can spawn a body; at most
  `MAX_BODIES_PER_OWNER` (2) bodies may be theirs, spawned or not; and a name the server's profile
  cache knows as a player is refused, since that player is only offline (the cache's own entries
  for bodies, under a body's UUID, do not count). The name check applies to a body that already
  exists too (the cap does not: it already counts). Level 2 and the console are not limited.
  `unprivilegedOwnerIsCappedAtTwoBodies`, `knownPlayerNameIsRefusedBelowLevelTwo`,
  `knownPlayerNameIsRefusedForAnExistingBodyToo` and `mayCommandRules` measure these, the first
  three through the command.
- A real player who joins under the name of an online body is never shut out of it. Vanilla's
  duplicate-login rule is that the newest login wins (the older session of the same profile is
  disconnected), but it compares UUIDs and a body's is its own, so nothing in vanilla stops two
  players of one name. The body gives way: `BodyManager.onPlayerJoined`, run from the loader's
  player-logged-in hook, logs it out through the normal path (saved, record kept, stay-loaded off),
  tells its owner if they are online, and the refusal above keeps it from being spawned beside
  that player for as long as the server knows them (a level 2 source can still spawn it while they
  are offline, and it gives way again at their next join; while they are online `spawn` refuses
  the name as taken). Left as it is: in chat the player's "joined" line shows before the body's
  "left" line, because the loader's login hook runs after vanilla has announced the join.
  `realPlayerJoiningTakesTheNameOfABody` joins a fake player through
  `placeNewPlayer`.
- Chunk loading: keeps a ticket around itself only while it has an active goal. A logged-in body
  loads chunks like any player (view distance 6), so the manager despawns a body whose brain does
  not want to stay loaded and whose owner is not online in the same dimension within 128 blocks,
  after 600 ticks (`BodyConstants`). A body with no owner (spawned from the console) has no owner
  to be near, so it gets a longer grace, 6000 ticks (`ownerlessBodyIsIdleLongerThanOwnedOne`).
- Death: drops are recorded with coordinates; respawn re-enters the goal tree with a
  "recover drops" prerequisite if they are still within reach and the despawn timer allows. The
  body respawns at the end of the tick; a respawn that throws is retried on the next ticks and
  the body is despawned after three failures. A body that is dead when it is spawned from its
  saved data (the server stopped between its death and its respawn) is respawned too
  (`bodySpawnedDeadComesBackAlive`). In a hardcore world vanilla's respawn would make the body a
  spectator, so a body that dies there is despawned instead, its record is marked dead, and
  spawning that name again is refused with a message saying why (run on a hardcore dev server;
  no GameTest can make the world hardcore). A despawn in the tick the body dies in, before the
  end-of-tick respawn would have retired it, marks the record dead the same way
  (`despawnInTheTickOfAHardcoreDeathMarksTheRecord`, with the manager told the world is hardcore
  for those few calls). A body that gave up on its respawn keeps its stay-loaded flag, because the
  server gave up, not its owner: a body with a goal comes back with the server
  (`bodyThatGaveUpRespawningStillComesBackWithTheServer`).

### 3.2 Reflex layer

Runs every tick, above the executor. It preempts the executor and returns control to it when
done. Priority order:

1. Immediate hazards: lava/fire, falling, drowning, suffocation.
2. Mobs **targeting the agent** (the mob's current target is the agent) or in melee range:
   fight with the best weapon and shield, or retreat when outmatched.
3. Health/hunger thresholds: eat, and retreat when health is low.
4. Night/exposure policy (configurable: shelter, bed, or ignore).

The reflex layer never changes the goal tree. It only pauses the active task (§3.4,
Preemption). Task state machines must
be resumable after preemption; this is a hard requirement on every task.

Decided in #8 (`game.reflex`, `docs/reflexes.md`, `ReflexGameTests` and `ReflexRuntimeGameTests`; the numbers are
`ReflexConstants`, starting from AIBot's):
- One `ReflexLayer` per body, wired in: `ReflexFeature.init()` makes it the reflex source of every body's runtime, and
  `BotRuntime.think` asks it first, every body tick, and passes the `ReflexHold` to the executor. While the hold is on, the layer
  drives the body (it ticks the navigator itself, so the runtime does not tick it in that tick), the executor's task is paused,
  and so is the runtime's own leftover-table step. Taking the body stops the navigator, clears input, item use and any block
  break, and sets the navigator's break filter to "break nothing". Letting go, however the hold ends (finished, the body died or
  was replaced, the layer stopped), leaves none of those behind and sets the filter to `BreakRules.filterFor(body)` for the body
  as it is then; the layer remembers no filter. A handover between reflexes stops the navigator command of the old holder: the
  layer does it, not each reflex. A hold that is dropped (the body died and was replaced) stops its command on the live body's
  navigator, so the replacement starts from an idle one. Reflexes are listed once, in `ReflexRegistry`; one that throws is
  quarantined, not allowed to take the tick down. A despawned body's layer is dropped by a sweep, as its navigator is.
- A task paused for a hold does not stop a navigator command that is not its own. In one body tick the layer runs first and may
  have begun a retreat when the executor pauses the task for it; the navigator therefore remembers who gave each command
  (`BotNavigator.issuingFor`, said by `BodyTask.nav` on the way to every command a task gives, and voided by anyone else asking
  for the navigator), and a task's pause, abort or ending stops the navigator only when the command it runs is the task's own or
  there is none. The navigator's engine is ticked once per body tick at most (the layer in a held tick, the runtime otherwise),
  counted by `BotNavigator.engineTicks()` and asserted on every tick of the runtime GameTests.
- A threat is a hostile whose target is the body, within 16 blocks, or any hostile within melee range, **and in sight**
  (which is what keeps a body walled into its shelter from fighting the zombies outside). A hold ends after a quiet
  period (no threat for 20 ticks, no mob hurt for 40; after a retreat from a threat that is still about, no threat in sight for
  100 ticks, so that one creeper is one hold and not a hold for each time the body runs out of its sight and its task walks it
  back). The resume gate's "health above the retreat threshold" is the
  vitals reflex's: it holds the body to eat while it can; with no food the gate opens (3.2 item 3: "otherwise only report").
- A creeper that targets the body is a threat within the reach of its blast (9 blocks, 14 when charged) or while it is coming
  closer (its own move, 0.6 blocks in 6 ticks); farther off and standing about (stuck behind a river, among trees) it is watched,
  not run from. The first night run took the body nine times in half a minute for creepers that did not come, and the executor
  gave the goal up (`PREEMPT_STALLED`, whose limit stays).
- A mob that hurts the body is never ignored: a chase given up on goes on the ignore list unless the mob has hurt the body in
  the last 100 ticks, and one that keeps hurting it from where it cannot be reached is retreated from, to the nearest spot out
  of its sight, never stood in front of.
- Attacks go through `Player.attack`, **before** `swing` (`ServerPlayer.swing` resets the attack cooldown, so a swing first makes
  every hit a weak one); a shield is lowered for the swing, since a player cannot swing with it up. A creeper is never
  approached: the body keeps or opens the gap from one that is a threat. **The exception is a body that is cornered, with no spot
  left to run to: it fights a creeper that is inside the swell range (3.5 blocks), or whose fuse is burning, as the last
  resort**, and otherwise waits for it facing it. One whose fuse burns near a body that holds a shield is faced with it raised.
- The night shelter is built from inside, bottom up, by clicking faces the eyes can see with the sneak key down (the cap goes
  against a block put on a wall from the top of a jump; a placement counts only when the cell holds the block), after asking
  `BlockSafety` whether each cell could be broken again, and is taken down in the morning by breaking what the body placed. The
  blocks it placed are recorded in the layer and leave the record only when the world no longer holds them: a takedown that
  could not finish is tried again by a later calm tick in daylight, each failed attempt at the same block doubling the wait
  before the next (400 ticks, up to a day), so a block that cannot be taken down does not take the body every few seconds. The
  night marked done is the night the hold was for (taken when it started), not the one the clock names when it ends, and a
  policy changed under a walk to the bed spends nothing. A body asleep in its bed gets up when the morning comes (day time
  23000) or the policy is changed, and the night counts as spent.
- A hazard that holds the body for 400 ticks is given up on per kind, so giving up on a fire does not switch off surfacing.

### 3.3 Language layer

- Provider-agnostic: an OpenAI-compatible endpoint (covers llama.cpp, Ollama, LM Studio, and
  most hosted models) plus the native Anthropic API.
- The tool set is small and high-level: `set_goal`, `cancel`, `status`, `search_items`,
  `choose_strategy`, `say`. The planner, not the model, expands goals.
- The model is consulted only at decision points: goal intake, ambiguous target resolution, a
  strategy choice the cost model can't break, and failure explanations. A slow or offline
  model never stalls the body.
- API keys live in a server-side config file that is never synced to clients or echoed in chat.

### 3.4 Executor contract (M0.5 spike, 2026-11-24)

The core of M1–M3. A clean design that keeps AIBot's Goal/Task shapes and replan budgets and
fixes what went wrong there (§2.1). Every rule below names the AIBot failure it prevents where
there is one.

**Snapshot.** An immutable, Minecraft-free record: body position and dimension (plain ints and an
id string), inventory and equipment counts by item key, best tool tier per tool class, food
units, nearby stations and container contents (radius configurable), and the extra data an
active goal asks for: per-stack component facts for the item keys a `componentCheck` names
(enchantments, potion, fill state), a structure report for a build goal, and observations a
strategy declares (the current offers of the villager a librarian reroll is watching). A container sight is what the
body noted when it last opened the container, never what the world holds now (a player cannot see into a closed chest):
it is marked remembered, in range or not, until the body opens it again, and a container it has not opened has no
contents in its sight and counts as nothing. A sight also says whether the body may take from it (one it placed, or one
its owner named: §3.5, supply containers). Collected on the server
thread, read-only. Collecting it never changes state (AIBot's collector made capability
decisions as a side effect). It also holds the body's **sight**: for each block the acquisition index names
as a mining or harvesting source, the horizontal distance of the nearest one in the chunks already loaded
around the body (absent means not seen; a chunk is never loaded or generated to look). The scan is kept per body
and renewed only when it is older than a few seconds or the body has moved a chunk, so a snapshot taken to check
a postcondition does not pay for it.

**Goal.** A sealed set of typed goals: `HaveItem(key, count, componentCheck?)`,
`HaveToolTier(toolClass, tier)`, `Equip(slotSet)`, `Stockpile(key, count, container)`,
`Build(blueprint, anchor)`, `Reach(pos, radius)`; strategies add their own. Each goal maps to a
**postcondition**: a pure function `Snapshot → Evaluation` where `Evaluation` is
`SATISFIED | UNSATISFIED | UNKNOWN` plus matched, required and the unmet keys. `UNKNOWN` means
the snapshot cannot tell (a container out of range or in an unloaded chunk) and is never treated
as done. On UNKNOWN the executor inserts one `Reach` node to bring the thing into snapshot range
and evaluates again. Still UNKNOWN after that: a leaf fails with `TARGET_GONE`; for a non-leaf
node or for the goal itself (the tree has run out), the goal gives up with `TARGET_GONE`.
- **Component goals.** For a goal with a `componentCheck` (a mending book, a water bucket), only
  stacks that pass the check count, both in the postcondition and in the shortfall the planner
  receives. The solver still plans by identity (§4.1): target resolution turns a
  component-qualified shortfall into a strategy (§4.4, the librarian reroll for mending), never
  into a recipe. A component miss is therefore an ordinary unmet key, and a wrong-component stack
  in the inventory does not hide it.
- "Done" is SATISFIED on a fresh snapshot, never "the task returned". The executor evaluates the
  goal after every node completes and when the tree runs out. On give-up the goal *result* is
  PARTIAL when matched > 0, otherwise FAILED (PARTIAL is a result status, not an Evaluation).

**Goal tree.** The planner (§4) produces a tree; each node holds a sub-goal with its own
postcondition, and each leaf binds to a task kind plus parameters. Node states: `PENDING`,
`ACTIVE`, `SATISFIED`, `FAILED`, `SKIPPED`. Before a node starts, its postcondition is checked;
if it already holds (items picked up on the way, a chest the owner filled), it is marked
SATISFIED without running. Nodes may be marked best-effort (food top-ups, torches); a failed
best-effort node is SKIPPED with no retry or replan, whatever the failure type says.

| Node transition | When |
|---|---|
| PENDING → SATISFIED | postcondition holds before start |
| PENDING → ACTIVE | executor starts a **new** task instance for it |
| ACTIVE → SATISFIED | task completed and postcondition holds, or postcondition holds at resume |
| ACTIVE → FAILED | task failed, or completed with the postcondition unmet |
| ACTIVE → PENDING | task aborted by the body's death (`DIED`) |
| (new) → PENDING | executor inserts a `Reach` (UNKNOWN) or "recover drops" (`DIED`) node |
| FAILED → PENDING | executor grants the one retry for that failure type |
| FAILED → SKIPPED | node is best-effort |
| any non-terminal → removed | a replan replaces the subtree |

The table is for leaves. A non-leaf node has no task; its state is derived: SATISFIED when its
own postcondition holds, ACTIVE while a descendant is, otherwise PENDING. Failures, retries and
skips apply to leaves only. A retry always creates a fresh task; failed task instances are never
restarted. Satisfied nodes are re-checked only at load and on replan, not continuously.
Executor-inserted nodes survive a replan: they stay at the front of the new tree until they
finish or fail.

The **goal** has its own states: `RUNNING`, `PAUSED`, `SATISFIED`, `GAVE_UP`, `CANCELLED`.
`PAUSED` is set and cleared only by the owner's pause/resume command (§3.1). The model-backed
language layer has `pause` and `resume` among its tools only to turn the owner's own loose words
("hold on a moment") into that command: it is asked when the owner speaks and at no other time,
so it never pauses or resumes on its own (decided in #20);
the active task is paused, and the goal still counts as active for respawn at server start. A
reflex preemption (below) pauses only the task; the goal stays RUNNING.

Goals the owner submits while one is running wait in a FIFO queue, saved with the goal state,
and start in order when the current goal ends (but not while the reflex layer holds the body; see
Preemption). Cancel (owner or the `cancel` tool, §3.3) aborts the active task, discards the tree,
publishes a CANCELLED result, and removes that goal from saved state at once, so a restart cannot
resume it; the queue then advances. "Cancel all" also clears the queue.

**Task.** A resumable state machine with guarded transitions:

| Operation | Allowed from | Result |
|---|---|---|
| `start(body)` | PENDING | RUNNING, or FAILED with a typed failure |
| `tick(body)` | RUNNING | `Running`, `Completed`, or `Failed(failure)` |
| `pause(body, reason)` | RUNNING | PAUSED; releases inputs and any held use/break |
| `resume(body)` | PAUSED | RUNNING; must re-acquire its targets from the world |
| `abort(body, reason)` | any non-terminal | CANCELLED; releases inputs; no-op on terminal |

- Resume re-checks the world: re-path from the current position, re-find the target block or
  entity, re-count inventory. Saved fields are hints for where to look, never facts. (Only 7 of
  AIBot's 38 tasks override resume, and most of those trust saved fields.)
- A task's `Completed` is a hint; the node is SATISFIED only when its postcondition holds. A
  task that completes with the postcondition still unmet counts as `Failed(NO_PROGRESS)`.
- Tasks never move the body directly. Movement goes through navigation as inputs (§3.1, §2.2).
- A task that throws is failed with `INTERNAL` and logged with its stack trace. Nothing swallows
  exceptions around the whole body tick (AIBot catches every `NullPointerException` there).
- Each task kind has a tick budget; exceeding it is `TIMED_OUT`. A stuck watchdog (no position
  or inventory change for N ticks while RUNNING) fails it as `NO_PROGRESS`. Budgets, N, and the
  reflex thresholds below are config values; their starting numbers are set and tuned in M1
  (#7, #8), not here.

**Failure.** A sealed type, never a string. Routing decisions read the type; free text is
attached for logs only. (AIBot has ~270 distinct reason strings and routes on `startsWith`, so
renaming a message silently changes behaviour.)

| Failure | Carries | Default executor response |
|---|---|---|
| `UNREACHABLE` | target, navigation reason | retry once with a fresh path search; then replan (another source or target) |
| `TARGET_GONE` | what | re-search once; then replan |
| `MISSING_TOOL` | tool class + tier | replan (tool tier becomes a prerequisite node) |
| `MISSING_INPUT` | item key + count | replan from live inventory |
| `NO_SOURCE` | item key | replan with that source excluded; give up when none remain |
| `PROTECTED` | position, rule (§3.5) | replan with that block or region excluded |
| `HAZARD` | kind (lava, fall, drowning…) | replan avoiding it; the reflex layer handles the immediate danger |
| `TIMED_OUT`, `NO_PROGRESS` | budget used | retry once; then replan |
| `DIED` | drop positions, death time | abort the active task, if any; its node returns to PENDING; replan from the respawned inventory; if the drops can still be reached before they despawn (§3.1), a "recover drops" node goes in front of the new tree, otherwise the death record is dropped |
| `PREEMPT_STALLED` | reflex cause | the reflex layer held the goal paused past its limit (see Preemption); give up the goal at once (the reflex layer still owns the body, so no replan can run) |
| `NO_PLACE` | what, radius | the body looked within `radius` blocks for somewhere to do `what` (put a crafting table down, stand to work) and found nowhere, and nothing a planner can change would alter that (the planner does not see where a block fits): give up the goal at once, with this failure as the result's reason; no retry, no replan |
| `NO_ROOM` | item key | the inventory cannot take what the step produces — a craft whose result does not fit, a mine step with no free slot and no stack of the item with room (no block is broken for a drop that would be lost): a replan would plan the same step into the same full inventory, so give up the goal at once, with this failure as the result's reason; no retry, no replan (#26) |
| `INTERNAL` | exception | a bug: give up on the goal; report to the owner and the server log |

**Retry, replan, give up.** The executor, not the task, decides. Budgets, based on AIBot's
tuned numbers:
- Retries: at most one per node per failure type, as listed above.
- A replan counts as progress if the goal's matched count rose or the plan fingerprint changed.
  The **plan fingerprint** is a hash of the tree's pending nodes (kind, target key, count, and
  the chosen source or position), so switching to another source counts as a change. AIBot
  applies this test only to postcondition repairs; here it applies to every replan.
- At most 3 consecutive replans without progress and 12 per goal in total. A strategy with long
  random phases (librarian reroll, long ore expeditions) may declare a higher total, capped by a
  server config ceiling; AIBot hard-codes a raise only for rare-ore runs. The total counts every
  plan-caused replan, progress or not, so a plan that keeps changing shape still ends (restart
  and death replans are capped separately; see Replanning).
- When the tree runs out with the goal unsatisfied, at most 3 repair replans (AIBot's
  `MAX_POSTCONDITION_REPLANS`), each also counted in the totals above.
- The counters persist with the goal, so a restart does not reset them.
- Give-up publishes a goal result (status, matched/required, unmet keys, the failure that ended
  it) to the owner and, only then, to the model (§3.3). Node-level failures the executor is still
  handling are not sent to the model (AIBot also holds model wake-ups while a goal plan is
  active).

**Replanning from live state.** Triggers: a node fails with a replan response; the tree runs out
with the goal still unsatisfied; the goal-level shortfall changes (an item the plan needs was
lost, or appeared from somewhere the plan did not expect); restore after a restart; respawn after
death. All but the last two count against the budgets above; a restart or a death is not the
plan's fault and must not use up a long goal's replan budget. Deaths have their own cap instead:
the third death within one goal gives up (persisted with the counters), and a death replan
excludes the area around the death position the way `HAZARD` excludes a hazard, so the planner
does not walk back into the same shaft. Between triggers, inventory changes only
re-check pending nodes' postconditions, which is cheap and not a replan.

Planning runs off-thread on a snapshot (§8). The **input fingerprint** is a hash of the inputs the
planner actually read: counts of the item keys in the shortfall and its recipe closure, tool
tiers, known stations, which of the blocks that the closure's mining and harvesting sources name are in
the sight (seen or not, never how far, so a body that walks does not look like a changed situation), and the
goal's exclusions whole (they decide which sources and containers a plan may use, so two requests that differ
only in what an earlier failure ruled out are different inputs). Item
pickups outside that set do not change it. The result is adopted
on the server thread if the input fingerprint still matches; otherwise it is re-run, at most twice
in a row, and the third result is adopted regardless. Drift that slips through is caught by the
per-node postcondition check before each node starts. A planner that throws is treated like a
task `INTERNAL` failure: the goal gives up and the exception is logged.

**Preemption.** The reflex layer (§3.2) sits above the executor. On preempt, the executor pauses
the active task and keeps it; reflex actions are not nodes in the goal tree and are not stacked
as tasks. One registry lists the reflex behaviours (AIBot keeps two hand-written lists that
disagree). Resume needs: no mob targeting the body, not hurt in the last N ticks, health above
the retreat threshold. On resume, the active node's postcondition is checked first: if it holds,
the task is aborted and the node marked SATISFIED; otherwise the task resumes. While the gate is
shut the executor starts nothing: no replan, no new tree, no queued goal. Responses that arrive
meanwhile (a `DIED` replan, the next queued goal) wait until the gate opens. If the gate stays shut past a configured limit (total paused ticks, or repeated
preemptions within a window), the active node fails with `PREEMPT_STALLED`, so a spawner or an
empty food supply cannot hold a goal paused forever without a result. A death starts the count again: `onDeath` resets
the goal's held ticks and its preemption window, as a goal's start does, so a goal that has just lost its kit to a death
is not also given up for the holds that led to it. The death cap (`maxDeathsPerGoal`, three per goal) is what bounds a
goal that keeps dying.

**Persistence.** Two records, both schema-versioned:
- **Body record**, in a server-level saved-data file: body UUID and name, owner, dimension and
  position, and whether it has an active goal. At server start every body with an active goal is
  re-created from this record (AIBot does the same from its `runtime.json`). Bodies without one
  respawn when their owner next asks for them. Built in #6: `megablockbot_bodies` in the
  overworld's saved data, schema 1, kept current on spawn, despawn, death and every 200 ticks;
  an entry that does not decode is skipped and logged; a file with a schema this build does not
  know is left untouched, the record set is read-only for the session, and that is logged once as
  an error naming the file (run on a dev server: the file's hash is the same after a session that
  spawned a body). A file with no schema tag counts as such a file: schema 1 is the only one this
  mod has written and it always carries the tag, so there is no older form to read, and the log
  says "no schema tag" instead of a schema number.
- **Goal state**, in the body's player data: the root goal, the goal tree's node specs and states,
  a small per-node checkpoint map, the replan counters, pending
  death records (drop positions and death time), the queued goals and the owner's paused flag.
  Vanilla does save player data for a stub-connection body (answer to #6's question):
  `PlayerList.save` only skips a player whose `connection` is null, and a body has one.
  `inventorySurvivesDespawnAndSpawn` gives a body items, a helmet, 7 health and 12 hunger,
  despawns it, spawns it again and finds all of it back, at the position it left from;
  the body record therefore holds no inventory. The brain saves and loads inside that player
  data, under one `megablockbot` compound (`BotBrain.save`/`load`), next to the owner; a brain
  that throws in `load` is replaced by a fresh one and one that throws in `save` is skipped,
  and the body is saved and loaded regardless (`badBrainDoesNotBlockSaveOrSpawn`;
  `brainStateSurvivesDespawnAndSpawn` for the round trip). Server stop: the stopping hook logs
  every body out first, which saves it (`PlayerList.remove` saves the player), because
  `PlayerList.removeAll` disconnects by index and a body's disconnect shrinks the list at once,
  which would skip the player after it. A GameTest cannot stop the server; on a dev server both
  bodies log out before "Saving players" in the log and come back where they were.
  A crash gets the same walk without the hook: `MinecraftServer.runServer` posts the stopping event
  only when the tick loop ends normally, and calls `stopServer` from its `finally` on every path.
  There `PlayerList.saveAll` has already saved the bodies when `removeAll` meets them, and `server.isStopped()`
  is true (it is set just before `stopServer`, on every path). A body's disconnect then leaves it
  listed, as a real player's does until its channel closes, and only brings its record entry up to
  date (the file is written by the level save that follows); the entry after it is no longer jumped
  over (`shutdownWalkReachesARealPlayerListedAfterABody` walks the list by index with the manager
  told the server is stopping). Nothing runs on a hard kill: the record is then up to 200 ticks old
  (its refresh interval) and player data as old as vanilla's last autosave. The manager of a server is
  dropped by the loader's server-stopped hook, which also runs on every path, not by the stopping
  hook that a crash skips; otherwise the static map kept a crashed server, its levels and its bodies
  reachable for the rest of the JVM (an integrated server that crashes and is followed by another).
  No GameTest can end the server it runs in, so that last part is measured on a dev server only.

The checkpoint map holds the few facts a task kind declares worth keeping across a restart,
as hints for where to look (a mine shaft's position and the Y it reached, a village found, a
librarian already rerolled). Checkpoints are keyed by node identity (task kind plus target); a
replan carries a checkpoint over to a new node with the same identity and drops the rest. Each
task kind owns and validates its keys; a task restarting from
a checkpoint still re-checks the world (Task rules above). Not stored: other task internals,
paths, reflex state, failure details beyond the counters. On load: re-evaluate every
node's postcondition, mark the satisfied ones, re-plan the rest from live state. A checkpoint that fails validation is dropped and that node
re-plans; a root goal that cannot be decoded is dropped and reported to the owner. A bad save
never crashes the server or blocks other bodies.

**Threading.** Executor, tasks, reflexes and snapshot collection run on the server thread.
Planning and path search run off-thread on immutable inputs.

**Amendments made while the executor core was built (2026-11-26, #12). Where they differ from the text
above, these hold; the code is `core/exec`.**

- A group node is evaluated when execution first reaches it: satisfied, it and its unfinished
  descendants are done (a prerequisite the bot was handed is not gathered again); unsatisfied or
  unknown, its children decide. A non-leaf UNKNOWN no longer gives the goal up.
- A task that completes with an UNKNOWN postcondition gets one reach node and a second look; only an
  UNSATISFIED one is NO_PROGRESS. An executor-inserted node (reach, recover-drops) whose postcondition
  cannot be known counts as done once its task completes.
- A death is handled before the best-effort rule: it is always counted, its area excluded and its
  drops queued for recovery. Deaths that arrive before the replan are all kept and applied together,
  earliest first. The host reports a death through `onDeath`; a task does not also fail with DIED.
- A drop in the goal's matched count after a node completes is a replan trigger (counted as a normal
  replan). An UNKNOWN goal evaluation there waits until the tree runs out.
- Planning has a deadline (`planTimeoutTicks`, 1200): past it the goal gives up TIMED_OUT.
- A budget give-up publishes the failure behind the latest failure-driven replan, with the budget
  named in its detail, and NO_PROGRESS only when there was none. That failure is not saved: right
  after a load a budget give-up reports NO_PROGRESS.
- Every way a goal ends retires its active task first, and evaluates the goal at that moment when a
  live body exists, so a result says what the bot has (PARTIAL at 3 of 5, not FAILED at 0). Between
  a death and the new body's first tick there is no body to look at: the last evaluation stands.
- A task hook that throws still gets `onAbort` to let go of what it held. A task that is not RUNNING
  under an active node ends the node (its failure is routed) instead of stalling the goal.
- Persistence: only executor-inserted nodes are read back from a save; planner nodes are replanned,
  and each node's postcondition is checked before it starts, not at load. The save holds the pending
  deaths (a list, with detail), the counters, the queue and the checkpoints, the live task's
  included, and never a null. Loading over a live goal cancels that goal first.
- Owner pause with no goal does nothing. An owner resume while a reflex still holds the body waits
  for the hold to end.

**Amendments made while the tasks that move and mine were built (2026-11-26, #12). The code is `game/task`.**

- A task that ends on its own, with a Completed or Failed result, has already let go of the body (the navigator
  stopped, a menu closed), because the executor does not abort it afterwards. The same code runs for an abort and a
  pause.
- A leaf's source id travels in `NO_SOURCE`, so the replan excludes that source, not every source of the item. A
  mining task has no tick clock of its own (the executor's budgets decide how long it may run). It bounds its search by
  a number of attempts and a distance explored (`MineTask.Search`), and past that fails `NO_SOURCE` saying how far it
  looked. For a block with a height range it digs to the best height first.
- A task that moves but gets nowhere watches itself, because the executor's watchdog cannot: its signature is the body's
  block position and inventory, so a body that keeps climbing out of a flooded cave and falling back in (the first live
  run, issue 27: ten minutes at one mineshaft pillar) changes it every few seconds and is never stuck. A mining step
  therefore sets blocks aside. The navigator sets one aside when the body has headed for it for 400 ticks without getting
  a block closer or breaking anything, or when the engine finds no path to it; the task keeps the set for its life and in
  its checkpoint (a retry does not go back), and the engine is only shown what is left. The task also watches the count held
  and the best distance reached to a known block: 600 ticks with neither improving sets every known block aside (a
  stalled round) and it looks elsewhere. After three stalled rounds, or when the search is spent, it fails `UNREACHABLE`
  when it set blocks aside (blocks were known and none could be got at) and `NO_SOURCE` when it never knew of any, the
  text saying what was tried. A block with water in or touching its cell is taken only when no dry block is known and the
  body's air is full. The executor's budgets and watchdog are unchanged.
- `PROTECTED` from a mining task is read from the world, not from the navigator: when the navigator finds nothing to
  mine, the loaded area around the body is searched for blocks of the kind, and when there are some and the break rules
  forbid every one the failure is `PROTECTED` at the nearest. The break rules have one home, `game.task.BreakRules`,
  which block safety (3.5) will fill in; until then everything may be broken.
- `recover_drops` never fails (it is best-effort and a position it cannot reach is skipped) and keeps no checkpoint:
  checkpoints are keyed by node identity, and two deaths' recover nodes share one.
- A deposit is done with the container's own menu: the body uses the block, moves items with slot clicks (a
  shift-click for a whole stack, one right-click per item for part of one) and closes the menu, which hands the cursor
  back to the inventory. A container with no room takes what it can and the task completes; the postcondition says what
  is missing.
- A withdraw is the same in the other direction (supply containers, 3.5): the planner puts one `withdraw` leaf per
  container and item in front of the rest of the plan, and the task takes the items with the same clicks. A container
  that holds fewer than the leaf needs gives nothing (`MISSING_INPUT`, and what it held is noted so the replan counts
  the truth); an inventory with no room for it is `NO_PROGRESS`, as in the craft task. A container that is there and may
  be used but gives no menu (a block on top of a chest, a cat) fails the leaf `NO_SOURCE` naming the leaf's own source,
  which the executor rules out, so the replan gathers or crafts instead; a deposit asks block safety before it opens a
  container, as a withdraw does (3.5).
- A craft that needs a table puts one down where a player could (a replaceable spot and a neighbour face the eyes can see
  and reach; no sturdy face is needed) and, when no such spot is within reach, walks to the nearest place that has one
  (`game.task.TableSites`, 12 blocks). It draws only from the main inventory and the hotbar, and takes its result whole or
  not at all. A table it may no longer break is left standing, remembered, and the craft still succeeds.
- A plan can fail for a reason the planner cannot see or avoid: where a block fits is in the world, not in its snapshot. Every
  kind in the table above except `PREEMPT_STALLED`, `NO_PLACE`, `NO_ROOM` and `INTERNAL` ends in a replan, and a replan only
  changes the plan when the planner can see why the node failed (a source to exclude, a block to avoid); a craft with nowhere
  to put its table would be replanned into the same craft three times. `NO_PLACE` is the kind for it, and the executor gives
  the goal up at once, as it does for `NO_ROOM` (a full inventory is just as little something a replan can see, #26). A craft
  inside a protected area (block safety would not let the body take the table up again) first walks out of it
  (`TableSites.WIDE_RADIUS`) and fails `NO_PLACE`, never `PROTECTED`, when it cannot (#27).
- A look inside (`look_inside`, the one leaf of the small goal the body gives itself when its owner names a container:
  a `Goal.Custom` whose postcondition reads the snapshot) walks to the container, opens it with the real menu, notes
  what is in it and closes it. It is how the body comes to know what a named container holds.

### 3.5 Block safety

Griefing is a non-goal, but a mining bot breaks blocks by default. Rules, on in every profile:

- Never break blocks inside a configurable protected radius around the owner's spawn/bed, or
  inside claimed regions when a claims mod exposes them.
- Prefer naturally generated blocks. Player-placed blocks are tracked (per-chunk placed-block
  set) and are off-limits unless the owner allows it for that goal.
- Never break or take from containers, beds, or villager workstations it didn't place, except
  where a strategy explicitly needs it (the lectern it placed during a librarian reroll).

Decided in #9 (`game.safety.BlockSafety`, the one place tasks and the nav engine ask):
- Radius: constant 16 (`SafetyConstants`), a horizontal square, around the owner's respawn point (online, or from
  saved player data, followed through logins and logouts) and the overworld's world spawn. Neither it nor a claim is
  lifted by anything, bot's own blocks included; an allowance lifts only the player-placed and container rules, never
  a bed or a job site, and ends with its body (dies, despawns, is replaced) if its goal does not close it.
- Container: a block whose block entity is a vanilla `Container` or `Clearable` (a campfire counts, so do generated
  loot chests; on the server thread the real block entity is asked too, for breaking as for using); workstation: a
  block of a POI type a villager profession holds or acquires as its job site (from the POI data, no list); bed:
  `BedBlock` or the `beds` tag. Decided per block type, so it works off-thread. A double chest is two records and one
  inventory: breaking is per half, using needs both halves to be the bot's.
- Player-placed: placed by a real player (an enderman setting a block down is not), recorded per level with the block
  type; a position the level holds another block at is pruned, and only on the level's word, never a caller's state.
  Doors, tall plants and beds answer for their other half. Everything the record cannot see is in
  `docs/block-safety.md`.

Supply containers (#12, "craft from a given chest"). The rule above forbids taking from a container the bot did not
place, and it stays: the one exception is the owner's word. `use chest at <x> <y> <z>` names a container, `forget chest
at <x> <y> <z>` takes it back, `chests` lists them (only the owner or the console may say it: the rule that guards a
goal). The list is per body and saved with it (`BotMemory`); naming either half of a double chest names both, and one
half joined to it later is nobody's word, so the pair is refused (name it again to name the pair). A named container is
one the body may *use*: open it, take from it, put into it, exactly as it may its own. It may never *break* it, named or
not: `mayBreak` does not read the list. Whether using a container inside the protected radius is allowed was already
decided by the code and is kept: yes. `evaluateUse` skips the radius (it only ever stopped breaking, and would only keep
a bot from its own chest) and asks the claim rule, then the container rule; a supply container passes the container and
workstation rules for that block (a named barrel is also a job site) and still meets a claim. A bed cannot be named (it
holds nothing to take).

The body does only what a player can, and a player cannot see into a closed chest. Naming a container records its
position and its block and nothing about what is in it; the contents are unknown, and the planner counts nothing from a
container whose contents are unknown. So `use chest at` also sends the body to look: the runtime queues the small goal
`look_inside` for that container ahead of whatever follows (`use chest at x y z; make 4 stick` looks first and then
plans the sticks knowing what is there). Its one leaf walks to the container, opens the real menu, notes what is inside
and closes it, and its postcondition is that the snapshot has a note of the container. What the body noted is what the
plans count, marked remembered in the snapshot (in range or not) until the body opens the container again; every withdraw
refreshes it, and naming the container again drops the note and looks again. A note is per inventory, not per block
position: a pair is one note, and naming a chest that has since become half of a double chest leaves one note for the
pair, so one inventory is never counted twice.

The container that is used is the one that was recorded, and the rule is one rule in one place
(`SupplyChests.stand`) for the tasks, the snapshot and block safety. The owner's word and the body's own note of what is
inside each record the block, and a record counts only while that block stands at the position (both halves of a double
chest). So the withdraw and look tasks compare what they hold of the container with what stands there before they open
anything: another block means the record is gone (`TARGET_GONE`: the word, the note and the body's claim on the spot are
dropped, nothing is opened), whether the container was named or is one the body placed and has been inside, and block
safety's use check no longer passes a barrel put where a named chest stood. A position that is not loaded is "cannot
tell", never "gone": the task fails `UNREACHABLE` and keeps the word and the note, and the executor answers by going
there. A container that is there and may be used but will not open ends the withdraw leaf `NO_SOURCE`
for that leaf's source, so the replan leaves it out and gathers or crafts. Putting into a container asks block safety
too: a stockpile goal names its target, which is the owner's word for it for that goal, so the deposit task asks with
an allowance that covers exactly that container (both halves) open for the one question; the allowance lifts the
container rule and never a claim, a bed or a job site (a barrel the bot did not place is stocked once
it is named). A deposit notes what the container holds when it is done, as a withdraw does, so what was put in is
remembered. A stockpile goal whose target the body has no sight of at all (it has never been inside) cannot be judged
(UNKNOWN, and the executor's one `Reach` does not tell it what is inside), so its plan is the body's look at the target
(the same `look_inside` leaf, on the goal's own word) and nothing else: with the note made the goal is judged, and when it
is not met the executor replans knowing what is there. A double chest is one inventory for a goal as for a note: either
half, as the owner types it, finds the one sight (`ContainerSight.other`, `Snapshot.containerAt`), and the pair is the
goal's own target whichever half names it. A withdraw never ends with anything on the cursor: what a slot will not take
back (a furnace's result slot) goes to the inventory, and to the ground only when that is full too.

## 4. Planning pipeline

```
"get me a book of mending"
  → target resolution: enchanted_book + stored enchantment mending
  → solver-core: no recipe produces it → it is a leaf
  → acquisition resolver: sources = {villager trade (librarian), fishing loot, chest loot}
  → cost model picks: villager trade (deterministic) over fishing (random)
  → strategy "librarian reroll" expands to a goal tree:
        find village ─► find/obtain lectern ─► find unemployed villager
        ─► place/break lectern until offer == mending ─► obtain emeralds + books ─► trade
  → each sub-goal re-enters the pipeline (books → solver-core → leather/paper → …)
```

### 4.1 solver-core (shared with SprawlCrafting)

`RecipeGraphSolver<R, K>` already depends on nothing in Minecraft. It takes recipes through a
producer function, works on any item key, and has `shortfall()`, which returns the leaf demand
plus per-slot alternatives — the agent's gather list. Planned changes, all backward-compatible
so SprawlCrafting's behaviour is unchanged:

| Change | Why | SprawlCrafting default |
|---|---|---|
| `RecipeInfo.station()` → station key or none | Smelting, stonecutting, and machine recipes need a place to run; the station becomes a prerequisite goal. | none (2×2/3×3 gating stays in the adapter) |
| Per-slot quantity | Machine recipes consume more than one per slot; vanilla crafting is always 1. | 1 |
| Byproducts / chance outputs | Pulverizer-style secondary outputs. | none |
| Pluggable cost function replacing the current rule (producers tried in the order the adapter supplies; the first covers as much as it can, the next the rest; no plan-length search) | An agent starting empty needs "cheapest to gather", not "satisfiable from inventory". | current rule |
| Memoization option | Long modded chains will exhaust the attempt budget. Depth and attempt limits are already constructor parameters (`maxDepth`, `maxAttempts`), so larger budgets need no change; caching repeated sub-solves does. | off |

Known limits carried over: greedy left-to-right slot resolution with no cross-slot
backtracking, and planning by item identity only (NBT/components are re-checked at
execution). Keep them until an agent run actually fails on them. Then fix them in solver-core
so SprawlCrafting benefits too.

**Code sharing:** solver-core stays in the SprawlCrafting repo as a real Gradle subproject that
publishes a plain-Java library artifact (mavenLocal or a Gradle composite build for local
development; JitPack or GitHub Packages for releases). Every SprawlCrafting node, the
`forge-1.12.2/` build and this mod depend on that artifact and jar-in-jar it. Solver fixes
are made in the SprawlCrafting repo and both mods pick them up with a version bump. Do not
copy it into this mod. Two copies of the planner will drift, and planner bugs found by the
agent are exactly the bugs SprawlCrafting wants fixed. It stays Java 8 for the 1.12.2 consumer.

Rejected: depending on the SprawlCrafting *mod* (with a config to hide its player features).
SprawlCrafting has no 1.21.10/1.21.11 nodes, so that would mean maintaining two full mod nodes
to carry ~1.2k lines of plain Java; hidden features still load its mixins and hooks on every
bot server; every solver fix would wait on a SprawlCrafting release across all its nodes; and
SprawlCrafting would carry a feature-gate for every future feature. Its Minecraft→solver
adapters are version-specific code the bot needs on its own versions anyway (§4.2).

Current state (2026-11-26): the extraction is done (#2). solver-core is a standalone Gradle
build publishing `com.legoj15:solver`, package `com.legoj15.solver` (#1); every SprawlCrafting
build pulls it in as a composite and nests it. This mod does the same from the sibling checkout
(`settings.gradle.kts` looks for it; docs/platform-layer.md). The changes in the table above are
#13 (station, per-slot quantity, cost model) and #19 (byproducts, memoization).

How the cost model works (#13): it does not search plan space. The solver derives a unit cost per
item (the cheaper of gathering it and its cheapest producer, bounded by `maxDepth`, the same answer
whatever was asked before) and uses it to order producers and slot alternatives. The greedy engine
is unchanged, so SprawlCrafting's behaviour without a cost model is. Two rules keep it honest:

- Planning from stock (`solve`): cost only reorders. Nothing is dropped for being dear or
  unobtainable, so a target the inventory can make is always found.
- Reporting what is missing (`shortfall`, the gather list): what stock can make is credited first.
  Only the remainder is costed: an item no dearer to gather than to craft becomes a leaf, a slot's
  remainder goes to its cheapest alternative, and an unobtainable option is picked only when every
  option is unobtainable, so the planner still gets a demand it can report as having no source.

A station is an item that must be in stock while its step runs and is not consumed. The bot keeps
its crafting table as an inventory item: it places it, uses it and picks it up again (§4.2).

What the body can see is a third input to the cost model (the sight, §3.4). A mining or harvesting source whose
block is in the snapshot's sight costs its usual price plus a small charge that grows with the block's horizontal
distance; one whose block is not costs it plus a penalty larger than any seen charge (both are `PlannerConfig`
numbers). It only reorders, as every cost does: it is what lets a slot that takes any plank use the wood that
stands nearby instead of the one listed first, and a source that was unusable stays so. A snapshot with no sight
at all adds nothing, so a plan made without one is the plan it always was.

### 4.2 Producer adapters

Each adapter turns one recipe family into `RecipeInfo` for the solver:

- **Vanilla crafting:** exists (SprawlCrafting's `CraftPlanner` adapters; lift the
  Minecraft→solver adaptation, not the UI).
- **Vanilla smelting/blasting/smoking/campfire, stonecutting, smithing:** generic, from the
  server `RecipeManager`. Smelting, blasting and smoking are in (#14, `CookingRecipeSource`),
  and fuel is modelled as one `RecipeDef` per fuel class, in whole runs: a furnace recipe has
  no fixed fuel cost per item (one coal smelts eight, one plank one and a half) and a slot
  counts whole items, so with cooking time `c` and a burn time `b`, `b/c` in lowest terms
  `p/q` gives a variant whose run takes `p` of the ingredient and `q` of the fuel and makes
  `p` results, id `recipe#class` (`minecraft:iron_ingot_from_smelting_raw_iron#coal`). The
  classes are a fixed list (coal, planks, logs, sticks) whose burn times are asked from the
  game; the solver chooses between variants by cost, as between any two recipes that make one
  item, and the task that runs a `smelt` leaf is built (`game.task.SmeltTask`, on the station machinery the craft
  task was split into). Campfire cooking, stonecutting and
  smithing are not in yet.
- **Modded machine recipes:** there is no single place to read them from. Some mods register
  custom recipe types in `RecipeManager`, each with its own serializer and fields. Others keep
  recipes in their own registry entirely: BuildCraft's assembly table recipes live in a static
  map (`buildcraft.lib.recipe.AssemblyRecipeRegistry.REGISTRY`), not in `RecipeManager`. So an
  adapter is a per-mod **recipe source**: it reads wherever that mod stores recipes and
  produces `RecipeInfo`. Unknown `RecipeManager` types are visible but unplannable, and the
  planner reports them ("needs a Pulverizer recipe adapter"), which is itself useful dev
  output. Recipes kept in private registries are invisible until an adapter exists. The lint
  can only flag those indirectly, as items with no source. First adapter: BuildCraft (you
  maintain it). Machines that need power (the assembly table needs laser power) also need a
  "power this station" strategy, not just a station key.
- Explicitly not used: JEI/EMI as a recipe source. Neither exists server-side, and the agent
  must work on a dedicated server.

### 4.3 Acquisition resolver (new)

Maps every leaf item to how it can be obtained. It is a reverse index built at server start
and on datapack reload:

| Source | Built from | Extra data |
|---|---|---|
| Mine block | block loot tables, read backwards (item → blocks) | required tool + tier from `mineable/*` and `needs_*_tool` tags; silk-touch/fortune conditions |
| Ore search hint | `c:ores/*` tags; placed-feature height providers for the Y range | best branch-mining Y |
| Kill mob | entity loot tables | spawn biome/light conditions |
| Fishing | fishing loot table | marks the item as random |
| Villager trade | profession trade lists | random → needs a reroll strategy |
| Chest loot | structure loot tables | random + exploration; lowest preference |
| Harvest/grow | crop and sapling blocks | renewable flag |

A required tool tier becomes a prerequisite goal (diamond ore → iron pickaxe → iron → stone
pickaxe), which re-enters the pipeline. This is how "equip iron armor" becomes "mine
diagonally down to the iron Y range, or explore a cave if one is seen" without anyone writing
that chain by hand.

Known traps when building the index:

- Loot tables are trees, not lists: nested `loot_table` references, `alternatives`/`group`
  entries, tag entries, and conditions (silk touch, tool match, random chance) all have to be
  evaluated or kept as conditions on the source. The tree's fields are private in 1.21.1 with no
  getters; the resolver encodes each table through its codec and walks the JSON, which is the
  stable datapack format, instead of opening the classes with an access transformer. Ore height
  ranges are read the same way from the placed feature's codec.
- NeoForge global loot modifiers add or replace drops without appearing in the tables. Run the
  modifiers over a test context per block/entity, or accept that the lint misses them and say
  so in its output.
- Villager trades on 1.21.1 are hard-coded Java (`VillagerTrades`, plus NeoForge's trade
  events for modded additions), not data. Read them from the populated trade lists after the
  events fire. Verify the 26.x format before writing the 26.x adapter.
- Modded ores are often added through biome modifiers. The Y range comes from the placed
  feature, whichever way it was added.

The resolver alone, with no agent, gives the **obtainability lint**: every item in the
registry that has no recipe and no source. That's milestone M0.

### 4.4 Strategy registry

Some goals are multi-stage plans no generic planner should invent. They are written by hand
and registered with a precondition, an expected yield, and a cost:

- Librarian reroll (enchanted books). Designed here: AIBot has no reroll, lectern or village
  code, only a single trade with a villager already beside it.
- Fishing (manual — implemented, issue #16: the `fish` task casts, watches the bobber dip and reels in like a
  player; live on 2026-11-27, two cod in four minutes with a rod at a river). No auto-fisher: the game has no
  fishing without a player holding the rod, so a contraption would only hold the bot's own hand still, which the
  bot does for itself; an owner who wants a fish farm for their own use can have one built from a blueprint like
  any other structure. A fish is caught, not hunted: the planner does not plan a kill of a mob that lives in
  water, because the kill task does not fight under water.
- Branch mine at Y, cave exploration, travel until biome/structure found
- Blueprint build from structure NBT (farms, shelters, auto-fisher). What exists (#17): a blueprint is a file in
  `<config dir>/megablockbot/blueprints/` or one of the server's structure templates, saved by
  `/aiplayer blueprint save`, and a blueprint may hold only plain blocks — a block whose item places it, no block
  entity, and no second half of a two-block thing.

When several strategies apply, the cost model ranks them; the LLM is asked only when costs
are close or the user stated a preference ("fish for it").

### 4.5 World knowledge and fairness

Two profiles (same idea as AIBot's):

- `strict_survival`: the agent knows only what it has seen or explored. Finding a village
  means exploring. No locate, no hidden-block scans.
- `assisted`: it may use structure/biome locate and ore scans. Faster, and the right default
  for dev-testing runs.

## 5. Targets

- NeoForge first, on BuildCraft's version set (1.21.1, 1.21.10, 1.21.11, 26.1.2, 26.2, 26.3,
  the same set McDevBridge targets), so every BuildCraft dev workspace can use it. SprawlCrafting
  has no 1.21.10/1.21.11 nodes, which doesn't matter: solver-core is plain Java and runs on any
  version.
- Fabric second. BuildCraft now ships Fabric nodes for the same versions, AIBot is Fabric,
  and the navigation port's source already has a Fabric module (§2.2), so a Fabric body is
  cheaper than it looked. Keep loader-specific
  code behind a thin platform layer from M1 (BuildCraft's `buildcraft.platform` split is the
  local precedent). 1.12.2: not planned. solver-core already supports it, but the body would
  be a separate implementation.
- Java 21 (1.21.1) / Java 25 (26.x), matching the existing repos.

## 6. Milestones

| # | Deliverable | Done when |
|---|---|---|
| M0 | solver-core made a published subproject of the SprawlCrafting repo; SprawlCrafting consumes the artifact. Acquisition resolver + `/aiplayer lint` command (no agent). | SprawlCrafting builds and behaves identically on all nodes; lint lists unobtainable items in vanilla (should be ~creative-only items) and in a BuildCraft dev world, where it should list exactly the assembly-table products (chipsets and other items with no crafting recipe) until the M5 recipe source exists. |
| M0.5 | Spike (time-boxed): read AIBot's task/goal core and navigation. Record lift-vs-reimplement in §2, fill in §3.4, and judge whether its A* is good enough. | Decision and executor contract written into this doc. |
| M1 | Body: spawn, follow owner, navigate, reflex layer (defense, eat, hazards). | (a) Survives a night outdoors in a dev world, defending itself, without player help. (b) Navigation: reaches a target 200+ blocks away across mixed terrain (hills, water, a ravine or cave), bridging, pillaring and digging through where needed, with no teleports. |
| M2 | Vanilla gather-and-craft goals via solver-core + resolver (crafting only). | "Make a stone pickaxe" and "make a crafting table" from an empty inventory in a fresh world. |
| M3 | Smelting producers, tool-tier prerequisites, branch mining/cave strategy. Needs the solver-core station, per-slot quantity and cost-function changes (§4.1). | "Equip full iron armor" from an empty inventory. |
| M4 | Strategies: librarian reroll, fishing, blueprint build. | (a) "Get a book of mending" in `assisted`, then in `strict_survival`. (b) "Fish for it": catch a named fishing-loot item with the manual fishing strategy. (c) Build one structure from an NBT blueprint, gathering its missing blocks. |
| M5 | BuildCraft recipe source (assembly table) + "power a station" strategy; cost model and budget tuning for deep chains. | (a) "Craft a quarry and give it to me" in a BuildCraft dev world. The quarry and its gears are plain crafting, so this tests the deep chain (diamonds, gold, iron), not the adapter. (b) "Make goggles". Their recipe needs a redstone chipset, and chipsets come only from the assembly table, so this forces the adapter and a powered station. Gates are not a valid check: the basic and iron gates have plain crafting recipes, and identity-only planning picks those. |
| M6 | LLM layer polish: progress chat, failure explanations, local-model profile. | The M2–M5 goals work when phrased loosely. |

The LLM layer can be a thin stub (fixed command parser) through M5. The body's correctness
doesn't depend on it.

## 7. Testing

- solver-core: extend the existing JUnit suite for each new field (station, quantities, cost
  function) with SprawlCrafting's current behaviour as the expected result for the defaults —
  this is the "port must match source" case, so write those tests first.
- Acquisition resolver: assert against vanilla loot tables (diamond → diamond ore + deepslate
  variant with an iron-tier tool). This protects against loot-table format changes between MC
  versions.
- Body/tasks: NeoForge GameTests for short deterministic scenarios (craft from a given chest,
  defend against a spawned zombie). Long runs (M3+ "done when" rows) are evidence runs, not CI
  tests. Drive and observe them through McDevBridge from a dev client attached to the same
  world.
- No tests for chat phrasing, prompts, or config strings.

## 8. Risks and open questions

- **Navigation quality** is what makes or breaks the whole project and is the least within our
  control. There is no jar to drop in on NeoForge (§2.2): PlayerEngine's jar brings its own task
  engine and LLM service, and Baritone is client-side. The M0.5 spike found AIBot's A* falls short (§2.2), so M1 budgets a port of
  the Automatone copy in PlayerEngine's `common` module into a separate LGPL module. Remaining
  risk: swimming, which Automatone does not list as supported.
- **Modded recipe coverage** is adapter-per-mod, forever. The lint makes the gaps visible
  instead of silent.
- **NBT-dependent goals** (a specific enchantment, potion, or filled bucket): solver-core plans
  by identity only. The mending book shows this works: identity resolution happens in target
  resolution + strategy, not in the solver. Watch for cases that don't fit that pattern.
- **Server tick cost**: planning runs off-thread against a snapshot of recipes and inventory,
  then validates on the main thread before executing (solver-core is pure, so this is easy).
- **Local model + game on one GPU**: document VRAM expectations; default the docs to a hosted
  or second-box endpoint.
- **Issue tracker**: milestones from §6 are seeded as GitHub issues; this doc should not become
  a backlog.
