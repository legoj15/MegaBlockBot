# Planning inputs read from the game (`game.plan`)

What the Minecraft-free planner and executor are fed from the game (design 4.2 and 3.4). Bot node only (1.21.1).
Everything runs on the server thread; every product is immutable and published atomically.

## Recipe book

- `RecipeSource` (one family of recipes) is registered with `RecipeSources.register` during a feature's init. A source
  writes `RecipeDef`s, item remainders and notes into a `Collector`; `recipeTypes()` names the `RecipeManager` types it
  reads, only so the log can say which types nobody reads.
- `RecipeBookBuilder.rebuild` runs from `Platform.loader().onServerDataLoaded` (server start and every `/reload`),
  publishes through `RecipeBooks` and logs one line: recipes by source, remainders, notes, recipe types not read, ms.
  The first five note texts follow it at INFO (the rest at DEBUG), so a missing recipe can be explained from a normal
  log. A source that throws (or fails to link) is logged and dropped whole, with a note; a recipe whose id an earlier
  one already took is dropped with a note.
- `RecipeBooks.published()` returns the book together with the station set derived from it, one reference read, so a
  reader that needs both never pairs a book with the previous build's stations. `current()` and `stations()` are the
  same read for a caller that needs one half. The book is `SimpleRecipeBook.EMPTY` (no stations) until the first
  build and after the server stops. The station set is every station the book names, computed once per publish.
- `CraftingRecipeSource` reads `RecipeType.CRAFTING`: any `CraftingRecipe` with a fixed result whose ingredients all
  have items. One slot per non-blank ingredient, count 1, alternatives are the ingredient's distinct items in its own
  order. Station is `minecraft:crafting_table` unless `canCraftInDimensions(2, 2)`. `CustomRecipe`s (no fixed result),
  recipes with an empty-tag ingredient and recipes that throw while read are skipped and counted in one note each.
  Remainders: every item with a different crafting remainder item (the vanilla item-level accessor, no NeoForge type);
  an item whose accessor throws is skipped and counted in a note, and costs nothing else.
- A craft task finds a shaped recipe's layout in the game, not in the book. The book keeps the ingredients only (the
  slot list, blank cells dropped), so a shaped recipe's grid positions are not in a `RecipeDef`. The task looks the
  recipe up again in the game by `RecipeDef.id()` (the game recipe's id, `RecipeManager.byKey`) and takes the layout
  from that recipe. A shapeless recipe has none to take.
- Other recipe types (smelting, blasting, smoking, campfire, stonecutting, smithing) are unread until a source claims
  them. The summary line counts them, for example `{minecraft:smelting=70, ...}`.

## Snapshot

`SnapshotCollector.collect(body)` (or `collect(body, memory)`) fills the `core.snapshot.Snapshot`; it never opens a
container, moves an item or loads a chunk.

- `items` and `stackFacts` cover the whole inventory (main, armour, offhand). `stackFacts` has one entry per occupied
  slot, not per distinct item: two stacks of cobblestone are two entries.
- `bestTools` (`ToolFacts`): class from the item tags `minecraft:pickaxes/axes/shovels/hoes/swords`, shears from
  `c:tools/shear` or `ShearsItem`; for a tool no tag names, from the `minecraft:tool` component (a rule that mines and
  drops a `mineable/*` block tag). Tier from the same component's drop-denying rules, else the `TieredItem`'s tier. A
  tool's tier is its most restrictive rule: the blocks denied by every denying rule are united. One rule naming a
  vanilla `incorrect_for_*_tool` tag maps to that tier (gold to wood); any other denied set (a modded tier, several
  rules, or a direct block list) maps to the highest vanilla tier whose denied set contains it. Shears are recorded as
  `SHEARS` at `HAND`, so `bestTool(SHEARS)` cannot tell "carries shears" from "none"; `bestTools().containsKey` can.
- `stations`: blocks within `STATION_SCAN_RADIUS` (8, Euclidean) whose item is a vanilla workstation or a station a
  recipe in the current book names; nearest first. Chipped and damaged anvils count as `minecraft:anvil`.
- `containers`: only `BotMemory`, and only what the body noted when it opened a container (it cannot see into a closed
  one); the world is read for the block and the chunk, never for the contents. `contentsKnown` is true when the container is
  within the radius, its chunk is loaded and the same block still stands there; the contents are then what the body noted,
  stale if someone emptied it since. Otherwise `contentsKnown` is false and, for a container the body may not take from, the
  contents are withheld. Other dimensions are not listed. `remembered` is true whenever the contents are a note, in range or
  not, and stays true until the body opens the container again; a container it has never opened (one its owner named and it
  has not looked in yet) is listed with nothing and not `remembered`, and unknown counts as nothing.
  `mayTakeFrom` says whether the body may take from it: the body placed it, or its owner named it (`BotMemory.supply`). For
  such a container out of sight, or in range with its chunk not loaded, the contents are the remembered ones, so that the
  plan can walk over and count on them; one whose chunk is loaded and no longer holds the block (both blocks, for a double
  chest) is shown with nothing and may not be taken from. A double chest is one sight, at the half with the lower position
  hash, and one note: a note is per inventory.
- `nearbyBlocks` (the sight, `BodySight`): block id to the horizontal distance of the nearest such block, for the blocks
  `AcquisitionIndex.sourceBlocks()` names (every mining or harvesting source's block). A scan reads the chunks that
  are loaded now (`ServerChunkCache.getChunkNow`; a chunk that is not loaded is skipped, never loaded) within
  `RADIUS_CHUNKS` of the body's chunk and from `BELOW` blocks under the body to `ABOVE` over it, and walks a section's
  blocks only when its palette holds a wanted block that could still be nearer than the one found. A scan is kept in the body's `BotMemory` (not saved) and a
  snapshot reads it while it is at most `MAX_AGE_TICKS` old and the body is within `MOVE_BLOCKS` of where it was
  taken; the distances are measured from where the body is when the snapshot is taken. `collect` runs at every
  executor node boundary (pre-check, then the goal), plan request and adoption, and for commands, never per task tick, so
  a scan is taken about every 4 seconds at most while a body walks. `/aiplayer sight <name> [fresh [full]]` prints what
  a body sees and, with `fresh`, what a new scan costs (chunks read, sections walked and skipped, milliseconds; `full`
  walks every section that holds a wanted block, to compare). The cost numbers and the
  measurement are in the section "The sight" below.
- `structures` and `observations` are empty. They need the active goal's wants (blueprints, observation keys), which
  `collect` is not given yet; `SnapshotCollector.addGoalFacts` is where they go.

## The sight (`BodySight`, issue #27)

Why: the solver has to pick one item for a recipe slot that takes any plank, and with every log's mining source costing
the same the tie fell to oak, so a bot on a mountain walked 215 blocks for oak past other trees. The planner now prices
a source whose block the body can see below one it cannot (`GatherCosts`, design 4.1), so it takes the log that is there.

Numbers and why (all named constants; change one and re-measure with `/aiplayer sight <name> fresh`):

- `RADIUS_CHUNKS` 4 (9 by 9 chunks around the body's): every direction to at least 64 blocks, the far corner to 112. A
  bigger radius costs more for blocks the bot would not walk to first.
- `BELOW` 48 and `ABOVE` 32 blocks around the body's block: the trees on the slope under a mountain spawn, not the
  ore at the bottom of the world. An ore deeper than that is out of sight, which the cost model treats as dearer.
- `MAX_AGE_TICKS` 200 (10 s) and `MOVE_BLOCKS` 16 (one chunk): a felled tree is out of date by the first, and a body that
  has moved a chunk has changed what is near by more than the cost model can tell apart (1 per block). A scan is kept
  per body, in its `BotMemory`, never saved.
- `PlannerConfig.sightStep` 1 (per block of horizontal distance, capped below the penalty) and `unseenPenalty` 250:
  more than twice the farthest seen distance (112), less than one missing tool tier (400). With no sight at all the
  model adds nothing, so a plan made without one is the plan it always was (charging only block sources would move them
  against kills, fishing, trades, crafts and strategy proposals).

Cost of one scan (dev server, `scripts/bot-server.ps1`, 1.21.1, fresh world with seed 4242, one body at spawn and
teleported to three other places; 1026 wanted blocks; 81 chunks read, none missing). The scan reads chunks nearest first
and walks a section only when its palette holds a wanted block that could still be nearer than the one found, so the
stone, dirt and grass of far chunks are not walked once the body's own chunk has shown them at distance 0:

| | sections walked of 486 | time, warm | time, first scan after start |
|---|---|---|---|
| pruned (what a snapshot runs) | 43 to 106 | 2.2 to 3.6 ms | 16 ms |
| every section holding a wanted block (`fresh full`) | 332 to 389 | 14.6 to 17.4 ms | 103 ms |

Both find the same blocks (`SightGameTests.theScanThatSkipsSectionsFindsWhatTheFullScanFinds`, and the same 20 nearest
kinds in the radius-3 dev-server runs). With radius 3 (49 chunks) the pruned scan took 1.6 to 3.1 ms. A body that walks scans at
most about every 4 seconds (16 blocks), one that stands still every 10 seconds, only when something takes a snapshot.

End to end: a body spawned at the seed-4242 spawn, where only spruce is within reach, was given "make 1 stone pickaxe";
its first step read "mine (get 3 spruce log)". Before this change the tie fell to oak whatever stood nearby
(`SolverPlannerTest.theLogKindTheBodyCanSeeIsTheOneMinedAndItsPlankTheOneCrafted` pins the tie on a two-wood fixture).

How often `collect` runs: at each executor node boundary (a pre-check, then the goal), at each plan request and plan
adoption, when a held body is released, and for commands; never per task tick.

## BotMemory

Per body UUID (`BotMemory.of(uuid)`): positions of placed blocks per dimension, containers whose contents were seen (one
note per inventory, a pair with its second half), and the containers the owner named for the body to take from (`supply`,
one entry per inventory: a double chest is one entry holding both halves, with the block that was named; naming also
forgets what was noted of the container). `BotMemory.peek(uuid)` reads without making one.
`save()` is the compact persistent form; `BotMemory.restore(uuid, tag)` reads one back (tolerant of missing or
malformed parts) and installs it as that body's memory, replacing what was there, which is how a body gets its memory
back at spawn (`BotRuntime` installs a loaded memory when it first attaches to the body). `CraftTask` notes the table it
places; the tasks that open a container (`withdraw`, `look_inside`) note what they find in it, and `use chest at` records the
owner's word. The registry is cleared when the server stops.

## Checks

`PlanInputGameTests` (`scripts/gametest.ps1 -Node 1.21.1`): recipe shapes against vanilla data, every recipe's slot
count against the game's ingredients, the book rebuilt after a real reload (proved by a probe source read only by the
later build), the builder isolating a throwing source and a duplicate id, snapshot contents with a real body (offhand
counted once, two stacks of one item as two entries), tool classes and tiers, a tool known only by its data component,
a tool with two denying rules, the memory save round trip through `restore` (the owner's list of named containers and the
note of a double chest included).

`SightGameTests` (same run): the nearest block of each kind at its horizontal distance (a block in the body's column is
0 away, a 3-4-5 triangle is 5, a block no source names is absent); a block in a chunk nobody has loaded is absent and
looking does not load the chunk; two snapshots in a row take one scan, a step inside the bounds reads the kept scan with
the distance from where the body now stands, a walk or a climb past the bounds takes a new one, and so does a scan
older than `MAX_AGE_TICKS`; the pruned scan equals the full scan from five centres. They live in `game.plan`, not in
`game.gametest`, because they read the per-body scan counter, which is package-private.
