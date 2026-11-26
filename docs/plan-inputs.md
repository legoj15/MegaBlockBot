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
- `containers`: only `BotMemory`. `contentsKnown` is true when the container is within the radius, its chunk is loaded
  and the same block still stands there; the contents are then what the body last saw (stale if someone emptied it
  since). Otherwise `contentsKnown` is false and the contents are withheld. Other dimensions are not listed.
- `structures` and `observations` are empty. They need the active goal's wants (blueprints, observation keys), which
  `collect` is not given yet; `SnapshotCollector.addGoalFacts` is where they go.

## BotMemory

Per body UUID (`BotMemory.of(uuid)`): positions of placed blocks per dimension, containers whose contents were seen.
`save()` is the compact persistent form; `BotMemory.restore(uuid, tag)` reads one back (tolerant of missing or
malformed parts) and installs it as that body's memory, replacing what was there, which is how a body gets its memory
back at spawn. Nothing in production calls the `note` methods or `restore` yet: the brain's placement hook, the
container tasks and body spawn will. The registry is cleared when the server stops.

## Checks

`PlanInputGameTests` (`scripts/gametest.ps1 -Node 1.21.1`): recipe shapes against vanilla data, every recipe's slot
count against the game's ingredients, the book rebuilt after a real reload (proved by a probe source read only by the
later build), the builder isolating a throwing source and a duplicate id, snapshot contents with a real body (offhand
counted once, two stacks of one item as two entries), tool classes and tiers, a tool known only by its data component,
a tool with two denying rules, the memory save round trip through `restore`.
