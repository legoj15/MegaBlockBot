# Acquisition resolver and obtainability lint

Design: `AI-PLAYER-DESIGN.md` 4.3. Bot nodes only (1.21.1). Facts about the vanilla classes it reads:
`docs/handoff/vanilla-1.21.1-internals.md`.

## Pieces

- `core.acquire` (Minecraft-free, every node): `Source` (sealed: `MineBlock`, `KillMob`, `Fishing`, `Trade`,
  `ChestLoot`, `Harvest`), the immutable `AcquisitionIndex` (item to sources, `items()`, `craftable()`,
  `unplannableRecipeTypes()`, `notes()`) and `LintReport`.
- `game.resolver`: `AcquisitionIndexBuilder.build(server)` (server thread), `AcquisitionIndexes.current()` (atomic
  holder; `AcquisitionIndex.EMPTY` before the first build and after the server stops), `ResolverFeature.init()` (rebuilds
  through `Platform.loader().onServerDataLoaded`, registers the command and `ResolverGameTests`).
- `/aiplayer lint [namespace] [write]`: permission 2 or console. Chat shows counts, notes and at most 40 ids; `write`
  puts everything in `<world>/megablockbot/lint.txt`. A namespace named `write` cannot be linted by name.
- Evidence run: `scripts/bot-server.ps1 start -Node 1.21.1`, then `cmd -Command "aiplayer lint minecraft write"`.

## How each source is read

- Loot tables are encoded with `LootTable.DIRECT_CODEC` (registry-aware `JsonOps`) and walked as JSON in `LootEval`; no
  access transformer. NeoForge replaces 27 vanilla tables, so `neoforge:can_item_perform_ability` (`shears_dig`) is
  read as shears.
- A table is evaluated per scenario (tool: none that matters, silk touch, shears; killed by a player or not; conditions
  `LootEval` cannot judge assumed to hold or not). Each condition is a number 0..1, `alternatives` stops at the first
  child that passes, and an item keeps only its least demanding route. The two readings of the unjudgeable conditions
  are merged per item: where both yield an item it keeps the larger expected count and the larger chance (so cod is
  0.5368 per cast, the 85/95 * 60/100 of a cast away from open water, not the 0.51 of one in it). Ids carry
  `#silk_touch` or `#shears` for a special-tool route, so they are unique within one item's list, not across the index:
  key a source by `(item, id)`.
- Counts come from `set_count` (constant, uniform and binomial providers, exact) on the entry and then on its pool, in
  the game's order, only when the function's conditions hold under both readings. A pool-level `set_count` is not
  applied over a nested `loot_table` entry, and neither are table-level count functions; both, and the pool-level
  functions that change counts in other ways (`limit_count`, `enchanted_count_increase` and the wrappers `sequence`,
  `filtered`, `reference`), are named in the notes. One table that throws while it is evaluated costs that table, named in
  the notes. A `loot_table` entry with no readable value is noted with the table that holds it.
- Tool class from `mineable/*`, tier from the lowest `incorrect_for_*_tool` tag that lacks the block (HAND when no tool
  speeds the block up and none is required; shears routes are SHEARS/HAND).
- Ore ranges: every biome's placed features, `height_range` read from the encoded placement, targets from the
  `OreConfiguration`. Anchors resolve against the dimension's own limits (nether and end biomes by tag, else
  overworld) and ranges are clamped to the build limits; the range covers every feature. Best Y is the peak of one
  feature: of those peaking at or below the dimension's sea level (the noise settings' `sea_level`), the one with the
  largest veins, then the widest range; when none peaks that low, the lowest peak. So iron is 16 (the middle feature),
  not the mountain-only 232, and diamond is -64.
- Kill sources: entity types that have attributes (a living entity), other than the player and the armor stand.
- Trades: each listing of `VillagerTrades.TRADES` and the wandering trader is sampled 64 times with a fixed seed against
  an unplaced villager. Explorer-map listings are skipped (each roll would allocate a world map), a null listing is
  noted and skipped.
- Recipes: every `RecipeManager` output is craftable; types other than crafting, smelting, blasting, smoking, campfire
  cooking, stonecutting and smithing are counted as unplannable.

## What it does not know (also printed by the lint)

Global loot modifiers, mob drops coded outside loot tables, loot tables of type archaeology, barter, gift, shearing and
equipment, blocks that drop their own item but never exist naturally, and trades rarer than about 1 in 20 per listing.
Recipes of special crafting types (armor dyeing, firework rockets, tipped arrows) report no result item, so what only
they make is not in `craftable()`. With the `trade_rebalance` feature on, villagers and the wandering trader use the
experimental per-biome trade lists, which are not sampled. Yields are level-0 (no fortune, looting or luck); a count that
depends on block state is the lower bound, and two pools that read one block state each (a sweet berry bush's age 3 and
age 2) are both counted, so that yield is high.
