# AI Player Mod — Design

Working name: MegaBlockBot (repo name; the mod id/display name can change before first release).
Status: pre-implementation design, 2026-11-08; corrected 2026-11-09 after checking claims against
SprawlCrafting, BuildCraft, NeoForge and upstream repos. Audience: agents implementing it. Planned
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

Checked 2026-11-08. All are Fabric; none target NeoForge or Forge.

| Project | Licence | Notes |
|---|---|---|
| [zoyluoblue/mc_aiplayer](https://github.com/zoyluoblue/mc_aiplayer) ("AIBot") | MIT | 1.21.3. Closest match to this design: "LLM chooses intent, goals define completion, deterministic tasks execute". 63 LLM tools, 34 task state machines, typed goals with postconditions, a `strict_survival` profile, already has fishing + trading tasks. Its own A* navigation. ~146 stars, active. |
| [Goodbird-git/PlayerEngine](https://github.com/Goodbird-git/PlayerEngine) | LGPL-3.0 | AltoClef's task engine moved server-side. Most mature gather/craft/navigation behaviour. Behind Player2 NPC. |
| [adevivo/ai-companion](https://github.com/adevivo/ai-companion) | LGPL-3.0 | 1.20.1, bundles a PlayerEngine fork, any OpenAI-compatible endpoint incl. local llama.cpp. |
| [kylejckson/CraftDroid](https://github.com/kylejckson/CraftDroid) / MineAgent | LGPL-3.0 | 1.21.1 fake player + LLM tool calls + memory. Small. |
| [Mindcraft](https://github.com/kolbytn/mindcraft) | — | Mineflayer. Reference for LLM prompting/tooling only. |

Correction to earlier advice: adevivo's fork is not the "cleanest licence" — it inherits
PlayerEngine's LGPL-3.0. AIBot is the only MIT one.

**Decision: study AIBot as the architectural base; treat PlayerEngine as the navigation/behaviour
reference.**
- AIBot's MIT licence matches SprawlCrafting and McDevBridge, its layering already matches §1,
  and it already has the trading and fishing tasks that PlayerEngine-family mods lack.
- Its weak point is navigation maturity (custom A*). **There is no drop-in fallback**:
  PlayerEngine is Fabric-only, so it cannot be loaded as a jar next to a NeoForge mod, and
  Baritone is a client-side mod that steers the local player, so it cannot drive a server-side
  body at all. If AIBot's A* falls short, the fallback is *porting* PlayerEngine's movement code
  to NeoForge. LGPL-3.0 code copied into the tree makes those files LGPL; keep any port in a
  separate module/jar so the rest stays MIT.
- Either way the work starts with a port to NeoForge (AIBot is Fabric 1.21.3), so "fork" means
  "lift the design and selected classes", not "track upstream". Check upstream's attribution
  requirements before lifting.

Open question, settled by a time-boxed spike **before** M1 (milestone M0.5): lift AIBot's code,
or reimplement its layering cleanly on NeoForge. Read its task/goal core (~32K lines main Java)
and its navigation, and record the decision here. The spike also fills in §3.4.

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
  tick, as Carpet does. Navigation outputs those inputs, never teleports.
- It has a real inventory, real interaction distance, and real tool-tier rules.
- A client-less player is visible to the rest of the game. Decide each case in M1: the tab list
  entry, sleep-percentage counting, advancements and stats, and mods that send packets to "all
  players" (the stub connection must swallow them safely).
- Bound to an owner; it only takes goals from its owner (or ops).
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

The reflex layer never changes the goal tree. It only pauses it. Task state machines must
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

### 3.4 Executor contract (filled in by the M0.5 spike)

The least-specified piece of this design, and the core of M1–M3. The spike writes down, from
AIBot's code or a clean design:

- **Goal:** a typed target plus a postcondition checked against live world/inventory state.
  "Done" is the postcondition holding, never "the task returned".
- **Task:** a resumable state machine. Required operations: start, tick, pause (reflex
  preemption), resume, abort. Resume must re-check the world, not trust saved state.
- **Failure:** each task reports a typed failure (unreachable, missing tool, target gone,
  timed out). The planner decides retry, replan or give up. The model is told only on give-up.
- **Replanning:** when inventory or the world changes under a running plan (items picked up,
  a block mined by someone else), the planner re-solves from live state; plans are cheap
  (§8, off-thread).
- **Persistence:** the goal tree serialises into the body's player data, so a server restart
  resumes the goal. Running task internals are not saved; tasks restart from their
  postcondition check.

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
| Pluggable cost function replacing "first satisfiable, fewest steps" | An agent starting empty needs "cheapest to gather", not "satisfiable from inventory". | current rule |
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
has to change all of them. Also decide whether to rename the package
(`com.legoj15.sprawlcrafting.craft.solver`) before the first published version, because
renaming it afterwards breaks every consumer.

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
- Fabric second. BuildCraft now ships Fabric nodes for the same versions, and AIBot and
  PlayerEngine are Fabric, so a Fabric body is cheaper than it looked. Keep loader-specific
  code behind a thin platform layer from M1 (BuildCraft's `buildcraft.platform` split is the
  local precedent). 1.12.2: not planned. solver-core already supports it, but the body would
  be a separate implementation.
- Java 21 (1.21.1) / Java 25 (26.x), matching the existing repos.

## 6. Milestones

| # | Deliverable | Done when |
|---|---|---|
| M0 | solver-core made a published subproject of the SprawlCrafting repo; SprawlCrafting consumes the artifact. Acquisition resolver + `/aiplayer lint` command (no agent). | SprawlCrafting builds and behaves identically on all nodes; lint lists unobtainable items in vanilla (should be ~creative-only items) and in a BuildCraft dev world. |
| M0.5 | Spike (time-boxed): read AIBot's task/goal core and navigation. Record lift-vs-reimplement in §2, fill in §3.4, and judge whether its A* is good enough. | Decision and executor contract written into this doc. |
| M1 | Body: spawn, follow owner, navigate, reflex layer (defense, eat, hazards). | (a) Survives a night outdoors in a dev world, defending itself, without player help. (b) Navigation: reaches a target 200+ blocks away across mixed terrain (hills, water, a ravine or cave), bridging, pillaring and digging through where needed, with no teleports. |
| M2 | Vanilla gather-and-craft goals via solver-core + resolver (crafting only). | "Make a stone pickaxe" and "make a crafting table" from an empty inventory in a fresh world. |
| M3 | Smelting producers, tool-tier prerequisites, branch mining/cave strategy. | "Equip full iron armor" from an empty inventory. |
| M4 | Strategies: librarian reroll, fishing, blueprint build. | "Get a book of mending" in `assisted`, then in `strict_survival`. |
| M5 | BuildCraft recipe source (assembly table) + "power a station" strategy; cost model and budget tuning for deep chains. | (a) "Craft a quarry and give it to me" in a BuildCraft dev world. The quarry and its gears are plain crafting, so this tests the deep chain (diamonds, gold, iron), not the adapter. (b) "Make a gate" (or another item that needs a chipset), which forces the assembly table adapter and a powered station. |
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
  control. There is no jar to drop in on NeoForge (§2): PlayerEngine is Fabric-only and Baritone
  is client-side. Mitigation: judge AIBot's A* in the M0.5 spike against M1's navigation check.
  If it falls short, budget a port of PlayerEngine's movement into a separate LGPL module.
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
