# Reflex layer

Design: `AI-PLAYER-DESIGN.md` 3.2 and the Preemption paragraph of 3.4. Bot nodes only (1.21.1). Code: `game.reflex`;
tests: `game.gametest.ReflexGameTests`. Starting numbers come from AIBot (`docs/handoff/aibot-behaviours.md`
sections 5 and 7); every number is a named constant in `ReflexConstants`.

## Contract with the runtime

- `ReflexLayer.of(body)` is one layer per body (keyed by UUID, follows respawns, `clearAll()` on server stop).
  `ReflexLayer.of(body).tick(body)` is called **once per body tick from the brain, before the executor**, and returns a
  `ReflexHold` (`core.exec`): `NONE`, or `held` with a cause like `mobs: fighting zombie`.
- While the hold says `held` the layer drives the body (input, navigator, game mode). The runtime must not tick the
  executor's task or the navigator: the layer ticks the navigator itself when it has a command running. While it returns
  `NONE` the layer touches nothing.
- On taking the body it stops a running navigator command, clears the input, the item in use and any block break in
  progress, and swaps the navigator's break filter for "break nothing". On letting go it leaves no input, item in use
  or navigator command behind and restores the filter. A higher reflex taking the body from a lower one is a handover
  inside one hold (the executor sees one hold).
- It never changes the goal tree, never cancels a goal, never teleports. It breaks and places blocks only through the
  body's own game mode, and every break asks `BlockSafety.mayBreak` first (`game.task.BreakRules` does not exist yet;
  `BlockWork` is the single place the layer asks).
- A reflex that throws is logged with its stack trace, counted (`ReflexLayer.faults()`) and left out for 1200 ticks for
  that body; a brain must not throw and this runs inside the body's tick. GameTests assert the count stays put.
- Logs at INFO: `reflex <name>: hold started by <reflex>`, `hold handed from a to b`, `hold ended after N ticks: <cause>;
  <outcome>`, and `DIED: <death message>` from the dev brain. The mob reflex's outcome says kills and swings.
- `status()` is one line: what it is doing and why (or `calm`), health, food, a note when something is worth saying
  without a hold (`low health, nothing to eat`, `night, and nothing to build a shelter with`), the night policy.

## Priority order (`ReflexRegistry`, the one list)

1. **Hazards** (`HazardReflex`). In lava, on fire with water within 8 blocks (or standing in fire), air below 100 while
   the eyes are under water, head inside a block. Fire and lava: walk to the nearest water cell with open air above it
   (the navigator; by hand with jump held when in lava or when the navigator fails); no water: to the nearest safe
   standable cell. Drowning: hold jump, step sideways first when the water above is roofed over; holds until the head is
   out and air is back to 200. Suffocation: break the block at the eye (through `BlockSafety`). On fire with no water near
   and nothing burning under it takes no hold (nothing to do). About to walk off a drop is the navigator's.
2. **Mobs** (`MobReflex`). Threat: a hostile (`Enemy`, not a `NeutralMob` unless it targets the body) whose target is the
   body, within 16 blocks, or any hostile within 3.5 blocks; in both cases with a line of sight (this is what keeps a body
   walled into its shelter from fighting the zombies outside). Fight: best sword or axe (damage times attack speed,
   durability above 2), brought to the main hand by an inventory swap; shield moved to the off hand; face the mob; walk at
   it (sprint only beyond 5 blocks); swing through `Player.attack` once `getAttackStrengthScale(0.5)` is 0.95 or more; the
   shield goes up between swings when a mob that targets the body is within 4 blocks and is lowered for the swing (a
   player cannot swing with the shield up). Retreat instead (navigator walk to a standable spot 14 blocks off, farther from
   every threat than the body is now, at most 4 picks): health at or below 10, a creeper about to explode, a mob with 60 or
   more health, more than 2 attackers, or no weapon and (more than 1 attacker or health below 14). A retreat that finds
   no spot or no way, or keeps being caught, becomes a stand: cornered for 80 ticks, it fights what is at hand. A target
   that gets no closer for 100 ticks is ignored for 600. The hold lasts until no threat for 20 ticks and no mob has hurt the
   body for 40 ticks (design 3.4's resume gate, less its health condition, which belongs to vitals).
   Creepers are never approached: one that targets the body counts as about to explode from 9 blocks (a charged one 14),
   which is where its fuse is cut anyway (it swells from 3 and keeps swelling to 7). A creeper whose fuse is burning within
   4.5 blocks, with a shield in the pack, is faced with the shield up instead of run from (an explosion is blocked like any
   hit from the front). A retreat spot the navigator cannot reach is remembered and another tried at once; spots the body
   can see in a straight line are strongly preferred, and spots a shooter (a skeleton) could see are marked down; after 8 arrivals with the mob still on its heels the body stands.
3. **Vitals** (`VitalsReflex`). Eat at food 14 or below, or at health 10 or below with room in the food bar: the best food
   (no waste, never chorus fruit, no harmful food or always-edible golden apple unless food is 6 or below or health 10 or
   below), moved to the main hand and used with `ServerPlayerGameMode.useItem`, then held still for as long as eating
   takes (bread 32 ticks). Keeps going to food 18 (or health 14 when health started it). Low health with nothing to eat
   takes no hold: it is a note in `status()` (see "Differences" below).
4. **Night** (`NightReflex`). Policy `IGNORE`, `SHELTER` (default), `BED` (`ReflexConstants.NIGHT_POLICY`; per body
   `ReflexLayer.setNightPolicy` or `/aiplayer reflex <name> night <policy>`). Night is day time 12000 to 23000 in a dimension
   with a day, and only a body under the open sky (sky light 8 or more) is exposed. SHELTER: with blocks from the shelter
   list (dirt, cobblestone, ...) and a spot `BlockSafety` would let it break again, the body stands in the middle of its
   cell and builds (a spot that will not do, a drop beside it or a post in the way, sends it to the nearest one within 6 blocks
   that will; a spot it cannot reach is not tried again that night), from inside, the four foot-level walls, the four head-level walls, and the cap. A click only lands
   on a face the eyes can see and reach; the cap has nothing to stand on, so a scaffold block goes on a head-level wall
   from the top of a jump and the cap against it. A flower or sapling in a wall cell is picked first; a foot-level wall
   needs ground under it. At morning it breaks what it placed (cap first), then walks over its dropped blocks and picks
   them up. A shelter whose wall is broken is repaired; a body driven off it abandons it (blocks stay). A shelter that
   never closed does not use up the night: it is tried again after 200 ticks, from wherever the body stands. BED: walks to a bed it placed or set its respawn at within 12 blocks and uses it; with none, or when it cannot
   sleep within 300 ticks, it builds a shelter. Nothing to build with: no hold, a note in `status()`.

## Thresholds against AIBot's

| Constant | Here | AIBot | Why different |
|---|---|---|---|
| retreat health | 10 | 10 | same |
| max attackers | 2 | 2 | same |
| eat threshold / critical food | 14 / 6 | 14 / 6 | same |
| eat until | 18 | one item per trigger | one hold per meal, not one per bite |
| low-health eat | 10 | 10 | same |
| air low | 100 | 100 (guard), 50 (top threat) | one number |
| on fire | water within 8, or standing in fire | on fire and health below 10 | acts only when it can do something |
| threat range | 16, targeting, with sight; 3.5 melee | 10 any hostile (20 ranged, 16 creeper) with sight | design 3.2 says "targeting the agent" |
| attack ready | 0.95 | 0.95 | same |
| shield | between swings within 4 | within 4 and health 16 or below, 12 ticks | a server-side player cannot swing while blocking |
| creeper | never approached, flee within 9, brace with a shield within 4.5 once the fuse burns | never meleed, escape 12 | the blast is blocked from the front |
| fists | 1 attacker and health 14 or more | never fought unarmed | the brief asks for fists or retreat |

## Differences from the design text, and decisions

- Design 3.4's resume gate lists "health above the retreat threshold". The mob hold ends on quiet; the vitals reflex then
  holds the body to heal when it has food. With no food the gate opens at low health instead of staying shut, as 3.2
  item 3 says ("otherwise only report"); a goal that cannot survive that is the executor's to give up on.
- The shelter is built, not dug: a dug one needs the cap placed too, and digging needs the break rules answered for blocks
  that are not the body's own.

## Dev commands and evidence

`/aiplayer reflex <name>` prints the status; `... on|off` gives the body a dev brain that only ticks the layer (and keeps
it loaded); `... night ignore|shelter|bed` sets that body's policy. A night run on a dev server is
`scripts/bot-server.ps1` with a fresh survival world at normal difficulty; see the results in the issue.

## Verification

`ReflexGameTests` (run by `scripts/gametest.ps1`; each test's body is despawned, its mobs removed and the time of day put
back at the end): defend against a zombie with a sword (the sword wears, the zombie dies by the body's hand, the shield is
raised between swings), fists without a weapon, a creeper twice (the body runs from one that is coming for it and the gap widens;
in a closed room with a shield it is hurt no more than a scratch: it braces, or kills the creeper first), fire beside water, surfacing with low air, breaking out of a block, eating bread
(32 ticks a bite), a fight pausing a walking task which then resumes, a shelter at dusk (enclosed, a zombie cannot reach it, gone
at morning, the dirt back), a body whose own spot will not do walking to one that will and closing itself in there, the BED policy (it sleeps in a bed it owns), no hold at dusk with nothing to build with. The time-of-day
tests each have a batch of their own, because tests of one batch run side by side in one world; the open-ground creeper test has
one too. Limits: a GameTest loads about one chunk around the body, so a run from a creeper cannot go on for long (an earlier
version of the test that watched the whole chase failed three runs in four, once with the body killed by the blast, which is why
it measures the gap widening instead), and the navigator's first path in a JVM takes about 1.5 s (the creeper test warms it with a
short walk); sleeping puts the body in the bed (vanilla's own move), so the test harness allows that one step.

## Evidence: a night on a dev server (the issue's "survives a night")

`scripts/bot-server.ps1` (node 1.21.1), a fresh survival world at normal difficulty (seed 4242), a body spawned from the console,
moved to open plains 340 blocks from the world spawn (`spreadplayers`), given an iron sword, a shield, 64 bread and 64 dirt,
the dev brain on (`/aiplayer reflex <name> on`), `time set 12000`, and the whole night left to run (daytime followed with
`time query daytime`, never skipped). Health and food come from `data get entity`; kills from the layer's INFO lines.

| run | build | dusk | dawn | what happened |
|---|---|---|---|---|
| SHELTER | final | 20 / 20 | 20 / 20 | walked one block to a spot that would do, closed in after 31 ticks with 10 dirt, no mob hold all night, took the shelter down at morning, 64 of 64 dirt back |
| IGNORE | final | 20 / 20 | 20 / 20 | two mob holds: 3 killed, none got away, health never below 20 |
| IGNORE | earlier | 20 / 20 | 15 / 17 | at least 8 killed (kills inside handed-over holds were not logged by that build), health as low as 3.5, ate 4 bread, retreated about 110 blocks; ended in a lake, where the dev brain (nothing else drives the body) lets it sink and surface 22 times |
| SHELTER | earlier | 20 / 20 | dead | **died**: "shot by Skeleton" at about 18700, at 2.2 health after losing 13 in ten seconds to arrows while retreating. No shelter was started at dusk and the layer logged no reason (the likely one is that the body's spot would not do, and the status line only said so on the tick it looked); the first mob hold came about 4500 ticks into the night and kept the body running and fighting, so the one shelter it began later was abandoned. Fixes made after it: the body looks for a spot within 6 blocks that will do, the reason stays in the status, and retreat spots in sight of a skeleton are marked down |

Reading the table: two nights survived on the final build are not a survival rate. The IGNORE nights differ by what the world
sent; the one death shows the weak point, a body at low health in the open under fire from several skeletons, which the layer
answers by running (a shield cannot face two ways) and by eating when nothing is after it.
