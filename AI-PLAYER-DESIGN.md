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
| Body | `ServerPlayerEntity` subclass + fake `ClientConnection` + six mixins; no Carpet dependency. Movement: sets `forwardSpeed`/`sidewaysSpeed`, but every jump, drop, descent, swim step and sneak-edge shift is a bounded one-block `teleport`, and rescues teleport further (gated off in `strict_survival`). Its comments disagree on why: one says the server never runs `travel()` for its body (no passive gravity), another says gravity does pull it down. | **No.** Violates "never teleports" (§3.1). Carpet and Automatone fake players move through inputs, so plain-input movement is achievable; #6 must prove it. |
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
sides or top touch lava, except the target cell itself, which a task seals first (AIBot's
`ore_dig_lava_seal`); never dig a cell with a falling block (sand, gravel) above its head cell;
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
  forward input), jumping and sneaking each tick. No teleports. Automatone's own
  `FakeServerPlayerEntity extends ServerPlayerEntity` on a dummy connection is the same body
  shape as §3.1. Which entity class PlayerEngine itself drives is unconfirmed; the engine takes a
  `LivingEntity`, so pointing it at our player body is part of the port.
- Automatone has what AIBot lacks, per its feature list (not yet checked in code): segmented long
  paths with the next segment precomputed, path search off the server thread, a chunk cache,
  parkour, sneak-back-place bridging, pillaring, tool-aware breaking, ladders, doors and gates.
- Swimming is the open risk. It is not a listed Automatone feature; its `feature/swimming` branch
  was last touched 2021-04-30. PlayerEngine's copy has 17 references to swimming, unread. The
  first check after the port is crossing a lake and a river.
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
- **Movement is simulated server-side.** A normal `ServerPlayer` never moves itself; its position
  comes from the client's movement packets. The body must set its own movement input (forward
  and strafe, jumping, sneaking, look direction) and run the living-entity movement step each
  tick, as Carpet does. Navigation outputs those inputs, never teleports. AIBot's `ServerPlayer`
  subclass (`super.tick()` + `playerTick()`) moves its jumps, drops and descents by teleport,
  and one of its comments blames a missing `travel()` step (§2.1). #6's first check is that our body falls,
  walks, jumps and swims under plain inputs on 1.21.1, in a GameTest.
- It has a real inventory, real interaction distance, and real tool-tier rules.
- A client-less player is visible to the rest of the game. Decide each case in M1: the tab list
  entry, sleep-percentage counting, advancements and stats, and mods that send packets to "all
  players" (the stub connection must swallow them safely).
- Bound to an owner; it only takes goals from its owner (or ops). The owner can pause, resume
  and cancel its goal by command (§3.4).
- Chunk loading: keeps a ticket around itself only while it has an active goal.
- Death: drops are recorded with coordinates; respawn re-enters the goal tree with a
  "recover drops" prerequisite if they are still within reach and the despawn timer allows.

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
strategy declares (the current offers of the villager a librarian reroll is watching). Collected on the server
thread, read-only. Collecting it never changes state (AIBot's collector made capability
decisions as a side effect).

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
`PAUSED` is set and cleared only by the owner's pause/resume command (§3.1; not a model tool);
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
| `INTERNAL` | exception | give up on the goal; report to the owner and the server log |

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
tiers, and known stations. Item pickups outside that set do not change it. The result is adopted
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
empty food supply cannot hold a goal paused forever without a result.

**Persistence.** Two records, both schema-versioned:
- **Body record**, in a server-level saved-data file: body UUID and name, owner, dimension and
  position, and whether it has an active goal. At server start every body with an active goal is
  re-created from this record (AIBot does the same from its `runtime.json`). Bodies without one
  respawn when their owner next asks for them.
- **Goal state**, in the body's player data: the root goal, the goal tree's node specs and states,
  a small per-node checkpoint map, the replan counters, pending
  death records (drop positions and death time), the queued goals and the owner's paused flag.
  #6 must confirm that vanilla saves player data for a stub-connection body; if it does not, the
  goal state *and* the body's inventory, equipment, health and hunger move into the body record's
  file.

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

### 3.5 Block safety

Griefing is a non-goal, but a mining bot breaks blocks by default. Rules, on in every profile:

- Never break blocks inside a configurable protected radius around the owner's spawn/bed, or
  inside claimed regions when a claims mod exposes them.
- Prefer naturally generated blocks. Player-placed blocks are tracked (per-chunk placed-block
  set) and are off-limits unless the owner allows it for that goal.
- Never break or take from containers, beds, or villager workstations it didn't place, except
  where a strategy explicitly needs it (the lectern it placed during a librarian reroll).

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

Current state the extraction has to replace: solver-core is not a Gradle module today. Every
SprawlCrafting node build adds `solver-core/src/main/java` (and its tests) as an extra source
directory, and the separate `forge-1.12.2/` RetroFuturaGradle build does the same. Extraction
has to change all of them. The same change renames the package from
`com.legoj15.sprawlcrafting.craft.solver` to `com.legoj15.solver` (decided in #1), before
the first published version, because renaming it afterwards breaks every consumer.

### 4.2 Producer adapters

Each adapter turns one recipe family into `RecipeInfo` for the solver:

- **Vanilla crafting:** exists (SprawlCrafting's `CraftPlanner` adapters; lift the
  Minecraft→solver adaptation, not the UI).
- **Vanilla smelting/blasting/smoking/campfire, stonecutting, smithing:** generic, from the
  server `RecipeManager`. Fuel is modelled as an extra slot whose alternatives come from the
  fuel registry.
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
  evaluated or kept as conditions on the source.
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

- Librarian reroll (enchanted books)
- Fishing (manual), and auto-fisher build (blueprint)
- Branch mine at Y, cave exploration, travel until biome/structure found
- Blueprint build from structure NBT (farms, shelters, auto-fisher)

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
