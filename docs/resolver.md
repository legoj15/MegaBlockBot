# Acquisition resolver and obtainability lint

Design: `AI-PLAYER-DESIGN.md` 4.3. Bot nodes only (1.21.1). Facts about the vanilla classes it reads:
`docs/handoff/vanilla-1.21.1-internals.md`.

## Pieces

- `core.acquire` (Minecraft-free, every node): `Source` (sealed: `MineBlock`, `KillMob`, `Fishing`, `Trade`,
  `ChestLoot`, `Harvest`), `KnownWay` (a way a player has that no `Source` records), the immutable `AcquisitionIndex`
  (item to sources, `knownWays()`, `items()`, `craftable()`, `unplannableRecipeTypes()`, `notes()`) and `LintReport`.
- `game.resolver`: `AcquisitionIndexBuilder.build(server)` (server thread), `AcquisitionIndexes.current()` (atomic
  holder; `AcquisitionIndex.EMPTY` before the first build and after the server stops), `ResolverFeature.init()` (rebuilds
  through `Platform.loader().onServerDataLoaded`, registers the command and `ResolverGameTests`).
- `/aiplayer lint [namespace] [write]`: permission 2 or console. Chat shows counts, notes and at most 40 ids; `write`
  puts everything in `<world>/megablockbot/lint.txt`, the no-strategy items with their ways before the unobtainable
  ids. The return value is the unobtainable count. A namespace named `write` cannot be linted by name.
- Evidence run: `scripts/bot-server.ps1 start -Node 1.21.1`, then `cmd -Command "aiplayer lint minecraft write"`.

## How each source is read

- Loot tables are encoded with `LootTable.DIRECT_CODEC` (registry-aware `JsonOps`) and walked as JSON in `LootEval`; no
  access transformer. NeoForge replaces 27 vanilla tables, so `neoforge:can_item_perform_ability` (`shears_dig`) is
  read as shears.
- A table is evaluated per scenario (tool: none that matters, silk touch, shears; killed by a player or not; block
  state; conditions `LootEval` cannot judge assumed to hold or not). Each condition is a number 0..1, `alternatives`
  stops at the first child that passes, and an item keeps only its least demanding route. The two readings of the
  unjudgeable conditions are merged per item: where both yield an item it keeps the larger expected count and the
  larger chance (so cod is 0.5368 per cast, the 85/95 * 60/100 of a cast away from open water, not the 0.51 of one in
  it). Ids carry `#silk_touch` or `#shears` for a special-tool route, so they are unique within one item's list, not
  across the index: key a source by `(item, id)`.
- Block state: a block is in one state, so `block_state_property` conditions that test one property for different values
  never hold together. The table is evaluated once per state it names (for each property, each value its conditions
  mention that the block can have, from the block's own state definition, and one more state for all the others), and
  the states tested inside the tables it references through `loot_table` entries count too (bounded like the chain of
  references). Which state a yield is read in depends on the kind of source: **a block that is mined (`MineBlock`) is
  read in its default state when that state yields the item at all, the state that yields it the most only when the
  default state yields none of it; a block that is harvested (`Harvest`: whatever the builder makes one, a crop, a berry
  bush, cocoa, nether wart) is read in the state that yields it the most**, because harvesting waits for the plant to
  grow, so an unripe figure is never what a bot picks. Tall grass is 2 short grass with shears, not 4 (a lower and an
  upper half are two pools of which one is the block's, and the default state is the lower half). Mined: a slab is 1,
  not 2 (only a double slab gives two), a candle block 1 (not 4), a sea pickle 1 (not 4), pink petals 1 (not 4),
  snowballs from snow 1 (not 8 layers), and a glow lichen with shears 6 (a lichen on every face; the default state has
  no face and gives nothing). Harvested, the grown figure: a carrot crop 2 (1 in every state and another at age 7; the
  unripe plant's 1 is not read), a potato crop 2 the same way, a full cocoa pod 3 (1 before age 2), nether wart 3
  (uniform 2..4 at age 3; 1 before), sweet berries 2.5 (not 2.5 + 1.5: age 3 and age 2 are alternatives) and wheat 1
  (only age 7 gives it). The reason is the planner's: it prices a source by this number, and for a mined block a figure
  above what the block holds when placed makes a plan fall short (four candles counted on, one found) where a low one
  only collects a little more than needed. No `Source` says which state; the lint prints one standing note instead.
  Where the environment cannot say what the default state is (a test's, or a block the registry lacks) the best state is
  read for everything. Past 256 combinations of states (vanilla's most is 64: the six faces of glow lichen and sculk
  vein) the default state alone is read when it is known and the table is noted, because the best-state rule could not
  be applied; when the default is not known the state conditions are read as unjudgeable, both ways, and the table is
  noted the same way. A property tested by a range instead of a value is never enumerated.
- Counts come from `set_count` (constant, uniform and binomial providers, exact) on the entry and then on its pool, in
  the game's order, only when the function's conditions hold under both readings (one that cannot hold in the state
  being tried is simply not there). Three providers too big to enumerate are replaced, and each replacement is noted
  with the table it was read in: a binomial with n above 1000 keeps its mean, n times p, as a count of the whole number
  below it or the one above with the odds of the fraction; a uniform spanning 4096 values or more becomes its midpoint;
  and a `set_count` with `add` whose sum would span more than 4096 values becomes the sum of the means. A pool-level
  `set_count` is not applied over a nested `loot_table` entry, and neither are table-level count functions; both, and the
  pool-level functions that change counts in other ways (`limit_count`, `enchanted_count_increase` and the wrappers
  `sequence`, `filtered`, `reference`), are named in the notes. One table that throws while it is evaluated, or whose
  block states cannot be looked up, costs that table, named in the notes; a throw while a referenced table's state tests
  or state values are collected costs the referenced table, not the table that references it. A `loot_table` entry with
  no readable value is noted with the table that holds it, and so is a table that loops back to itself (once per table,
  not once per state).
- A pool's pick: one roll takes one entry among those that are there, by weight. Beside entries that are only there
  some of the time (a `random_chance` on the entry) an entry's share is averaged over which of them are there: weight 3
  beside a weight 1 that is there half the time gets 1/2 * 3/3 + 1/2 * 3/4 = 0.875, not 3/3.5. The average runs over the
  different totals of weight the uncertain entries can make (`LootEval.MAX_PICK_SUPPORT`, 4096); past that the entry's
  weight over the expected weight of the pool is used, and the table is noted. Vanilla has no pool with an uncertain
  entry beside another, so no vanilla figure depends on this.
- `loot_table` references, inline ones too, are followed `LootEval.MAX_TABLE_DEPTH` (128) deep, the table where the chain
  was cut is noted and the deeper references ignored; the longest chain in vanilla is three tables. A chain that long
  would otherwise overflow the stack, which no guard of the build catches. The depth bounds the stack, not the work: a
  table's result is kept by scenario and by how much depth is left where it was met, so a web of tables that reference
  each other many times over (64 tables with two pools each referencing the next is 2^63 paths) costs as many
  evaluations as it has tables and depths, not as many as paths. Without that, a result under a cut was not kept and the
  same web took longer than 25 seconds where it took 90 ms before the bound. The work of one top-level read is budgeted
  as well: at most `LootEval.MAX_TABLE_EVALUATIONS` (16384) table evaluations, counted where the memo misses, per
  `routes()` call. Tables times states times tools is not a shape vanilla writes (64 states x 2 readings x 3 tools x a
  chain of 3 tables is 1152, an order of magnitude under it), but a ladder of 200 tables whose root names eight boolean
  properties, 256 states, is past two hundred thousand evaluations: 44 seconds measured by a reviewer, and without the
  budget it exhausted the heap outright here. Past the budget the read stops, what was computed stands, and the table is
  noted as cut for size by its own name.
- Tool class from `mineable/*`, tier from the lowest `incorrect_for_*_tool` tag that lacks the block (HAND when no tool
  speeds the block up and none is required; shears routes are SHEARS/HAND).
- Ore ranges: every biome's placed features, `height_range` read from the encoded placement, targets from the
  `OreConfiguration`. Anchors resolve against the dimension's own limits (nether and end biomes by tag, else
  overworld) and ranges are clamped to the build limits; the range covers every feature. Best Y is the peak of one
  feature: of those peaking at or below the dimension's sea level (the noise settings' `sea_level`), the one with the
  largest veins, then the widest range; when none peaks that low, the lowest peak. So iron is 16 (the middle feature),
  not the mountain-only 232, and diamond is -64.
- Mining sources say whether the world generates their block (`MineBlock.generated`, read by `NaturalBlocks`): every
  configured feature and every dimension's terrain settings is written out through its own codec and each block id in
  the result counts, so a mod's ores and plants are covered unnamed. A feature that places a block from its own code
  with nothing in its configuration is missed; the four such blocks that a recipe also makes are listed by hand with
  their feature (`PLACED_BY_CODE`: glowstone, the snow layer, dripstone block, bone block). Structures are not read.
  The planner asks only about blocks a recipe makes: one of those that the world does not generate (a crafting table,
  planks, a campfire, a bookshelf) is not a source, whatever it drops; a generated one stays (diorite, clay, a melon),
  and so does every block no recipe makes. Before this the rule was "a block that drops itself and has a recipe",
  which let a live run go looking for a campfire to get charcoal from and kept diorite from being mined. A failed read
  counts every block as generated: a wasted search at worst, never a lost source.
- Kill sources: entity types that have attributes (a living entity), other than the player and the armor stand. Each
  carries what the fight is, from the type's default attributes: `health` (max health), `attack` (attack damage, 0
  when it has none) and `spawnsNaturally` (its spawn category is not `MISC`: a golem or a villager stands only where a
  village or a player made it). The planner prices a kill by them (`GatherCosts.fightCost`: the base price is a cow's,
  times health over ten, plus a quarter for each point of the mob's blow) and does not plan a kill of a mob the world
  does not spawn on its own. Found by a live run: with every kill at one price, four ingots from an iron golem was
  the cheapest iron, and "equip iron armor" began by exploring for a golem.
- Trades: each listing of `VillagerTrades.TRADES` and the wandering trader is sampled 64 times with a fixed seed against
  an unplaced villager. Explorer-map listings are skipped (each roll would allocate a world map), a null listing is
  noted and skipped, and a listing that throws, while it offers or while its offer is read, is noted and skipped on its
  own: the rest of its level and the other professions are still sampled.
- Recipes: every `RecipeManager` output is craftable; types other than crafting, smelting, blasting, smoking, campfire
  cooking, stonecutting and smithing are counted as unplannable.

## Known ways: obtainable, no strategy yet

An item with no source and no recipe is not necessarily unobtainable — a player may have a way the index has no
`Source` kind for (issue #28). Each such way is a `KnownWay`, held apart from the sources (`knownWays(item)`, sorted
by id) and read by nothing in `core/planner/`: they exist so the lint can say "obtainable with no strategy yet"
instead of "unobtainable". Each builder section below is guarded like the source sections, so one that throws costs
its ways and is noted.

- Loot tables of the types no source reads (`archaeology`, `barter`, `gift`, `shearing`, `equipment`, any other type
  except `generic`) are evaluated with `LootEval.routes` as a chest table is (the plain tool, no player kill, the
  mined state rule): every item one yields is a way `from` that table's id — `archaeology`, `barter`, `gift`,
  `shearing`, `equipment` for those types, `other_loot` for any other. A `generic` table is read only through the
  table that references it and is not read on its own.
- Buckets, read off the item classes: a `BucketItem` holding a fluid that is not empty is `fill_bucket` from the
  fluid's id; a `MobBucketItem` is `catch_in_bucket` from its entity type's id and never `fill_bucket` (the water it
  also holds is not the way); the `SolidBucketItem` (powder snow) is `fill_bucket` from its block's id. The entity
  type and the block are private fields with no getters, so each is named the way the ids are laid out
  (`<fish>_bucket` for the fish `<fish>`); a bucket whose content cannot be named that way is counted in the notes.
- Game code: a small named table in the builder, one row per drop the game's own code makes outside any loot table —
  the wither's nether star, a baby turtle's scute, an armadillo's scutes, dragon breath, an end ship's elytra, the
  dragon egg of the first dragon kill — each row commented with where in the game the drop happens. A row whose item
  is not in the registry (another game version) or is behind a disabled feature flag is skipped silently.

The lint has four lists: an item with a source counts as with a source; else a craftable one as craftable only; else
an item with a known way is listed obtainable with no strategy yet (the file shows each such item with its ways,
`id: way from, way from`); the rest is unobtainable. The chat summary counts all four, and `itemLines` (the capped
chat list) stays the unobtainable one.

## What it does not know (also printed by the lint)

Global loot modifiers, mob drops coded outside loot tables beyond the rows the game-code table names (a totem of
undying, a goat horn), blocks that drop their own item but never exist naturally, and trades rarer than about 1 in 20
per listing. Recipes of special crafting types (armor dyeing, firework rockets, tipped arrows) report no result item,
so what only they make is not in `craftable()` and has no known way either. With the `trade_rebalance` feature on,
villagers and the wandering trader use the experimental per-biome trade lists, which are not sampled. Yields are
level-0 (no fortune, looting or luck); a yield that block state decides is the default state's for a mined block and
the grown state's for a harvest (see Block state above).
