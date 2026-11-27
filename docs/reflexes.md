# Reflex layer

Design: `AI-PLAYER-DESIGN.md` 3.2 and the Preemption paragraph of 3.4. Bot nodes only (1.21.1). Code: `game.reflex`;
tests: `game.gametest.ReflexGameTests` (the layer on its own, with a test brain) and `ReflexRuntimeGameTests` (through the
real runtime). Starting numbers come from AIBot (`docs/handoff/aibot-behaviours.md` sections 5 and 7); every number is a
named constant in `ReflexConstants`.

## Contract with the runtime

The layer is wired in. `ReflexFeature.init()` makes it the reflex source of every body's runtime (`game.bot.ReflexSources`),
and `game.bot.BotRuntime.think` does the rest, once per body tick:

- It asks `ReflexLayer.of(body).tick(body)` **first**, before anything else it does, and passes the `ReflexHold` it gets
  (`core.exec`: `NONE`, or `held` with a cause like `mobs: fighting zombie`) to the executor, which pauses its task for as
  long as the hold lasts and resumes it after.
- Its own leftover-table step (taking up a table a stopped craft left standing) is a task like any other: paused for the
  hold, resumed after it. The executor waits for that step, not the other way round.
- It does not tick the navigator itself in a tick the layer held. The layer ticks the navigator while it has a command of
  its own running (a retreat, the walk to water); a second tick would move the body at twice a player's speed. The guard is
  `BotNavigator.engineTicks()`, a count of engine ticks: every `ReflexRuntimeGameTests` test asserts, on every tick, that it did not
  rise by more than the game's ticks did. (A bound on how far the body moves in a tick cannot see a double tick: walking is 0.2
  blocks a tick and the bound a jump and a knockback need is 0.8.)
- **A task pausing for a hold does not stop a command that is not its own.** In one body tick the layer runs first and may
  begin a retreat; the executor then sees the hold start and pauses the task, and the task's pause used to stop the
  navigator, which was by then the layer's. The navigator remembers who gave each command (`BotNavigator.issuingFor`, which
  `BodyTask.nav` says on the way to every command a task gives; the claim is voided by anyone else asking for the navigator
  with `BotNavigator.of`), `serial()` counts the commands, and `BodyTask.release` (pause, abort, and a task ending itself)
  stops the navigator only when the command it runs is the task's own or there is none. The task cannot snapshot the
  navigator's state at the end of its own tick (its ticks are the subclasses'), which is why the navigator remembers.
- A reflex source that throws is logged and the body is the executor's for that tick.

What the layer does with the body:

- `ReflexLayer.of(body)` is one layer per body (keyed by UUID, follows respawns, `clearAll()` on server stop, and
  `ReflexLayer.sweep` every 100 ticks drops the layers of bodies that are gone for good, as `BotNavigator.sweep` does for
  navigators). While the hold says `held` the layer drives the body (input, navigator, game mode). While it returns `NONE`
  it touches nothing.
- **Taking the body** stops a running navigator command, clears the input, the item in use and any block break in progress,
  and sets the navigator's break filter to "break nothing" (a reflex never digs: breaking terrain is the executor's business).
- **Letting go**, for whatever reason, leaves no input, item in use or navigator command behind and sets the filter to
  `game.task.BreakRules.filterFor(body)`, the rule every task arms the navigator with, for the body as it is *then*. The layer
  remembers no filter of anyone else's, so there is nothing to restore wrongly: a hold that is finished, one that is dropped
  because the body died, was replaced by a respawn or disconnected (the navigator is kept per UUID across respawns, so a
  filter left at "break nothing" would have refused every break for the rest of the session), and one the dev command ends
  with `stop()`. A dropped hold gives the filter to the live body on its first tick, and stops the command it was running
  on that body's navigator, so the replacement starts from an idle navigator and not from the dead body's failed command.
  **A hold ends with the body that died** (issue 29): a dead body's brain is not ticked, so the layer would only have
  noticed on the replacement's first tick; `BotRuntime.onDeath` tells it through the reflex source (`ReflexSource.onDeath`)
  and the hold and its per-hold state are dropped at once, in the tick the death is reported.
- **A handover** from one reflex to another (a higher one takes the body from a lower) is one hold to the executor. The layer
  itself stops whatever navigator command the old holder had running and clears its own use of the navigator; the new holder
  starts with it idle and uses it only for a command it starts. A reflex's `release` need not (and cannot be relied on to).
- It never changes the goal tree, never cancels a goal, never teleports. It breaks and places blocks only through the body's
  own game mode, and every break asks `BlockSafety.mayBreak` first (`BlockWork` is the single place the layer asks).
- A reflex that throws is logged with its stack trace, counted (`ReflexLayer.faults()`) and left out for 1200 ticks for that
  body; a brain must not throw and this runs inside the body's tick. GameTests assert the count stays put.
- Logs at INFO: `reflex <name>: hold started by <reflex>`, `hold handed from a to b`, `hold ended after N ticks: <cause>;
  <outcome>`, `hold dropped ...` (the body was gone), and `DIED: <death message>` from the dev brain. The mob reflex's outcome
  says kills and swings; the night reflex's says what the night came to.
- `status()` is one line: what it is doing and why (or `calm`), health, food, a note when something is worth saying
  without a hold (`low health, nothing to eat`, `night, and nothing to build a shelter with`), the night policy.
  `holderName()` is the name of the reflex that holds the body (`hazard`, `mobs`, `vitals`, `night`), or empty: what a test
  keys on, never the text of a cause. `lastNightResult()` is how the latest night hold ended, as a type.

## Priority order (`ReflexRegistry`, the one list)

1. **Hazards** (`HazardReflex`). In lava, on fire with water within 8 blocks (or standing in fire), air below 100 while
   the eyes are under water, head inside a block. Fire and lava: walk to the nearest water cell with open air above it
   (the navigator; by hand with jump held when in lava or when the navigator fails); no water: to the nearest safe
   standable cell. Drowning: hold jump, step sideways first when the water above is roofed over; holds until the head is
   out and air is back to 200. Suffocation: break the block at the eye (through `BlockSafety`). On fire with no water near
   and nothing burning under it takes no hold (nothing to do). About to walk off a drop is the navigator's. **Give-up is per
   kind of hazard**: a kind that has held the body for 400 ticks is given up on and left alone for another 400, and a hazard
   of another kind is looked for at once (giving up on a fire it could not put out must not leave a drowning body alone). The
   window is there so that a hold that cannot end (a body in a cage of fire) does not keep the executor from its goal for good,
   and so that the kind is tried afresh afterwards, when what stopped the body may be gone.
2. **Mobs** (`MobReflex`). Threat: a hostile (`Enemy`, not a `NeutralMob` unless it targets the body) whose target is the
   body, within 16 blocks, or any hostile within 3.5 blocks; in both cases with a line of sight (this is what keeps a body
   walled into its shelter from fighting the zombies outside). A creeper that targets the body is a threat only within 9 blocks
   (14 when charged) or while it is coming closer, see below. Fight: best sword or axe (damage times attack speed,
   durability above 2), brought to the main hand by an inventory swap; shield moved to the off hand; face the mob; walk at
   it (sprint only beyond 5 blocks); swing through `Player.attack` once `getAttackStrengthScale(0.5)` is 0.95 or more; the
   shield goes up between swings when a mob that targets the body is within 4 blocks and is lowered for the swing (a
   player cannot swing with the shield up). Retreat instead (navigator walk to a standable spot 14 blocks off, farther from
   every threat than the body is now, at most 8 failed picks): health at or below 10, a creeper that is a threat (below), a mob with 60 or
   more health, more than 2 attackers, or no weapon and (more than 1 attacker or health below 14). A retreat that finds
   no spot or no way, or keeps being caught, becomes a stand: cornered for 80 ticks, it fights what is at hand. The hold
   lasts until no threat for 20 ticks and no mob has hurt the body for 40 (design 3.4's resume gate, less its health
   condition, which belongs to vitals); **after a retreat, while a threat it engaged is still about (alive and loaded), it lasts
   until no threat has been in sight for 100 ticks** (`RETREAT_QUIET_TICKS`): out of sight is not dealt with, and a hold that ends
   the moment the creeper is hidden hands the body back to a task that walks it into sight again, which is what made nine holds
   in half a minute in the first night run. A threat that is dead or gone gets the short quiet. One creeper that is retreated from
   is one hold.
   - **A mob that hurts the body is never ignored.** A chase that gets no closer for 100 ticks puts the mob on the ignore list
     for 600, unless the mob has hurt the body in the last 100 ticks: then the chase is given up on after 40, the mob is marked
     out of reach and **stays a threat**, and a mob that hurt the body (by whatever route it got on the ignore list) comes off
     the list the tick it does. What hurt the body is remembered per mob by the layer (the body's own record holds one attacker
     for five seconds).
   - **A mob that keeps hurting it and cannot be reached is retreated from, out of its sight.** The retreat looks for the
     nearest standable spot, within 12 blocks, that no threat can see (neither the body's eyes nor its middle; arrows are aimed
     low), on a route that does not pass close to a threat, and goes to that very spot (an arrival radius of zero: a step short
     may be in the line of fire). Only when there is none does it run to a far spot as for any retreat, **and then it arrives
     within 2 blocks of it as any retreat does** (a hide that falls back to an ordinary spot used to demand the exact block). Once out of sight there is
     no threat and the hold ends after its quiet period. A body standing in front of a shooter it cannot hit, shield down, is the
     failure this replaces.
   - **Creepers are never approached, and far ones that do not come are watched.** One that targets the body is a threat when it
     is within 9 blocks (14 when charged: the reach of its blast, past which its fuse is cut) or while it is **closing**: its own move over
     the last 6 ticks has brought it 0.6 blocks nearer to where the body stands (`ApproachWatch`; `CLOSING_WINDOW_TICKS`,
     `CLOSING_MIN_BLOCKS`). A threat counts as about to explode, and the body keeps or opens the gap from then. One that targets the body
     from farther off and stands about (stuck behind a river, among trees) is not a threat: the layer takes no hold for it. A creeper
     that is not after the body counts only within 3.5 blocks or with its fuse lit. **A body that cannot run (cornered) fights a
     creeper inside the swell range, 3.5 blocks, or one whose fuse is burning, as the last resort**, and otherwise waits for it
     facing it; one whose fuse burns within 4.5 blocks, with a shield in the pack, is faced with the shield up (an explosion is
     blocked like any hit from the front). A creeper that does not come for 100 ticks is ignored like any mob that does not.
   - **A retreat starts running at once.** The navigator needs a few ticks for a first path and writes no input meanwhile; for the
     first 12 ticks of a retreat, while it has put the body in motion not at all, the body runs straight away from the nearest
     threat (onto ground that is there), through the `Reflex.afterNavigator` hook, which runs after the navigator's tick; the
     navigator's own input replaces it the tick it has any.
   - A retreat spot the navigator cannot reach is remembered and another tried at once; spots the body can see in a straight
     line are strongly preferred, and spots a shooter (a skeleton) could see are marked down; after 8 arrivals with the mob still
     on its heels the body stands.
3. **Vitals** (`VitalsReflex`). Eat at food 14 or below, or at health 10 or below with room in the food bar: the best food
   (no waste, never chorus fruit, no harmful food or always-edible golden apple unless food is 6 or below or health 10 or
   below), moved to the main hand and used with `ServerPlayerGameMode.useItem`, then held still for as long as eating
   takes (bread 32 ticks). Keeps going to food 18 (or health 14 when health started it). Low health with nothing to eat
   takes no hold: it is a note in `status()` (see "Differences" below).
4. **Night** (`NightReflex`). Policy `IGNORE`, `SHELTER` (default), `BED` (`ReflexConstants.NIGHT_POLICY`; per body
   `ReflexLayer.setNightPolicy` or `/aiplayer reflex <name> night <policy>`). Night is day time 12000 to 23000 in a dimension
   with a day, and only a body under the open sky (sky light 8 or more) is exposed.
   - **SHELTER**: with blocks from the shelter list (dirt, cobblestone, ...) and a spot `BlockSafety` would let it break again,
     the body stands in the middle of its cell and builds (a spot that will not do, a drop beside it or a post in the way,
     sends it to the nearest one within 6 blocks that will; a spot it cannot reach is not tried again that night), from
     inside, the four foot-level walls, the four head-level walls, and the cap. A click only lands on a face the eyes can see
     and reach; the cap has nothing to stand on, so a scaffold block goes on a head-level wall from the top of a jump and the
     cap against it. A flower or sapling in a wall cell is picked first; a foot-level wall needs ground under it.
     **A block is placed the way a player places one against a block that can be used: with the sneak key down** (a click on a
     crafting table or a furnace opens it otherwise; with the key not yet down the body presses it and clicks the next tick), and
     a placement counts only when the cell holds the block afterwards. A menu a click opened is closed.
   - **Taking it down** at morning breaks what the body placed (cap first), then walks over its dropped blocks and picks them up.
     **The blocks it placed are recorded in the layer** (`ShelterBlocks`), not in the hold, and leave the record only when the
     world no longer holds them. A takedown that cannot finish (the 600 ticks ran out, a fight carried the body off, the break
     rules refused a block) ends, keeps them recorded and puts them off for 200 ticks; **a later calm tick in daylight takes them
     down**, wherever the body is within 48 blocks, walking back to a block that is out of reach (the navigator, 300 ticks at most,
     breaking nothing on the way). A shelter that is abandoned (the body was driven off it) keeps its blocks recorded the same
     way. They are never forgotten while they stand, and **the record travels with the brain's save** (issue 29): `BotRuntime.save`
     asks the reflex source for where they stand (dimension and position of each), `load` gives it back on the body's first
     tick, and a position that no longer holds a shelter block -- air, another block, a dimension that is gone -- is dropped
     rather than restored.
     **A block the body cannot take down does not keep taking the body**: each failed attempt at a block (no face of it
     can be seen from where the body can stand, the navigator cannot get near it, the break rules refuse it) doubles the wait
     before the next, 400 ticks after the first, then 800, 1600 and so on up to a day (`TAKEDOWN_RETRY_TICKS`,
     `TAKEDOWN_MAX_WAIT_TICKS`), a refused one never under 6000; the block stays recorded. Without that, a block walled in by
     stone took the body every 200 ticks all day (12 holds in 2400 ticks, each a preemption the executor counts).
   - A shelter whose wall is broken is repaired. A shelter that never closed does not use up the night: it is tried again after
     200 ticks, from wherever the body stands.
   - **BED**: walks to a bed it placed or set its respawn at within 12 blocks and uses it; with none, or when it cannot sleep
     within 300 ticks, it builds a shelter. Nothing to build with: no hold, a note in `status()`. **The sleep branch looks at
     the morning and at the policy before it answers "sleeping"**: a body whose night is over (day time 23000, a little before the
     game wakes it) or whose policy was changed under it gets up at once (the hold ends and its release wakes the body), and
     the night counts as spent because it slept.
   - **Which night is done.** The night a hold is for is recorded when the hold starts, and that is the night marked done when
     it ends: a hold that ends after 24000 (a bed skips to morning, a takedown runs on) used to mark the *next* night done and
     switch the policy off for it. A night is marked done only when it was spent (a shelter closed, a bed slept in, morning
     came). **A policy changed from BED to SHELTER on the way to the bed spends nothing**: the walk is dropped, the night is not
     done, the new policy gets its turn next tick, and the outcome says the body did not sleep (`lastNightResult()` is
     `DID_NOT_SLEEP`; `SLEPT` only when the body was seen asleep).

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
| creeper | targeting: a threat within 9 (14 charged) or while closing, never approached, a cornered body fights it inside 3.5; far and not closing: watched; brace with a shield within 4.5 once the fuse burns | never meleed, escape 12 | the blast is blocked from the front |
| fists | 1 attacker and health 14 or more | never fought unarmed | the brief asks for fists or retreat |

Constants with no AIBot number (round two): a mob counts as hurting the body for 100 ticks after a hit
(`HURT_MEMORY_TICKS`), a chase that is being hurt is given up on after 40 (`CHASE_GIVE_UP_HURT_TICKS`), a hiding place is looked
for within 12 blocks (`HIDE_SEARCH_RADIUS`), recorded shelter blocks are pursued within 48 blocks (`TAKEDOWN_PURSUE_RADIUS`)
and a refused one is asked again after 6000 ticks at the soonest (`REFUSED_RETRY_TICKS`). Round three: a far creeper is closing
when it has come 0.6 blocks nearer in 6 ticks (`CLOSING_MIN_BLOCKS`, `CLOSING_WINDOW_TICKS`), a hold that retreated lasts 100
ticks past the last sight of a threat (`RETREAT_QUIET_TICKS`), and a shelter block that could not be taken down is asked
about again after 400 ticks, doubling on each failure up to a day (`TAKEDOWN_RETRY_TICKS`, `TAKEDOWN_MAX_WAIT_TICKS`).


## Differences from the design text, and decisions

- Design 3.4's resume gate lists "health above the retreat threshold". The mob hold ends on quiet; the vitals reflex then
  holds the body to heal when it has food. With no food the gate opens at low health instead of staying shut, as 3.2
  item 3 says ("otherwise only report"); a goal that cannot survive that is the executor's to give up on.
- The shelter is built, not dug: a dug one needs the cap placed too, and digging needs the break rules answered for blocks
  that are not the body's own.
- The executor declares a goal satisfied as soon as its postcondition holds, which after a hold can be before the craft
  that made the item has taken its table up (the runtime's leftover-table step does it on the next tick). A test of a goal
  through the runtime waits for the table instead of expecting it gone at the tick of the result.

## Dev commands and evidence

`/aiplayer reflex <name>` prints the status; `... on|off` gives the body a dev brain that only ticks the layer (and keeps
it loaded); `... night ignore|shelter|bed` sets that body's policy. A night run on a dev server is
`scripts/bot-server.ps1` with a fresh survival world at normal difficulty; see the results below and in the issue.

A body with the default brain (`BotRuntime`) stays loaded while it has a goal, running or paused
(`wantsToStayLoaded` is `executor.active()`): the runtime night run gives it a long goal.

## Verification

`ReflexGameTests` (run by `scripts/gametest.ps1`; each test's body is despawned, its mobs removed and the time of day put
back at the end; the harness keys on `ReflexLayer.holderName()`, never on the text of a cause or a status): defend against a
zombie with a sword (the sword wears, the zombie dies by the body's hand, the shield is raised between swings), fists without a
weapon, a creeper three times (the body runs from one that is coming for it and the gap widens; one seen coming from twelve blocks
off is not let close in while the body has room to run, and one that hovers there is not run from; in a closed room with a shield it is hurt no more than a scratch: it braces, or kills the creeper first), fire
beside water, surfacing with low air, giving up on a fire not switching off surfacing, breaking out of a block, eating bread
(32 ticks a bite), a fight pausing a walking task which then resumes, a shelter at dusk (enclosed, a zombie cannot reach it,
gone at morning, the dirt back), a body whose own spot will not do walking to one that will, a shelter built against crafting
tables (no menu opened, the dirt that left is the dirt that stands), a takedown that a fight broke off finished afterwards (the
body is carried off by the test, walks back, takes down, picks up), the BED policy (it sleeps in a bed it owns; the night a bed
skips does not switch off the next one; a policy changed on the way to bed builds a shelter and does not claim it slept), no
hold at dusk with nothing to build with, a body that dies in a hold able to break blocks after its respawn, a handover from
the hazard reflex stopping the navigator, a shooter that cannot be reached being hidden from, and a despawned body's layer
dropped by the sweep. Round four's additions (issue 29): a hold that ends in the very tick its body's death is reported, and shelter
blocks that survive a save and a load into a fresh layer for the same body (the one that still stands is taken down, the
one that was replaced by air is not among them).
`ReflexRuntimeGameTests` (default-brain bodies, a mob arriving mid-goal; on every tick of every test the navigator's engine has
not been ticked more often than the game): a craft finishing satisfied after a fight, a mining goal getting its blocks after a
hold, a hold while the runtime's leftover-table step runs, a retreat that begins on the tick the hold does staying on (the
navigator working on each of the next ten ticks, the body four blocks on in twenty-five; fails if the paused task stops the
navigator), and a creeper hovering twelve to fourteen blocks off for 600 ticks while a goal runs, which the layer never takes
the body for (the brief allows two holds). Round three's `ReflexGameTests` additions: a shelter block walled in by stone, and
one walled in too deep to get near, are asked about 3 times in 2400 daylight ticks (12 times before the backoff; the test
allows 5) and the body is held for 21 and 37 of those ticks (under 2 percent; before the backoff 92 and 64), a body that runs
from a creeper that then goes out of sight is held for the retreat's quiet period and not handed back at once, a body that dies
walking to water leaves the replacement an idle navigator, and a policy changed under a sleeping body gets it up. Not tested: a hide that falls back to
an ordinary spot arriving within 2 blocks (the radius of a navigator goal is not observable except as where the walk ends, and
the spot is the layer's pick, so a test would repeat the pick).
The time-of-day tests each have a batch of their own, because tests of one batch run side by side in one world; so do the
tests that mine (the break rules are global state) and the open-ground creeper tests. Limits: a GameTest loads about one chunk
around the body, so a run from a creeper cannot go on for long (an earlier version of the test that watched the whole chase
failed three runs in four, once with the body killed by the blast, which is why it measures the gap widening instead; a
creeper put in a chunk the body has not loaded stands still, so the far-creeper test keeps both inside the arena), and the
navigator's first path in a JVM takes about 1.5 s (the creeper tests warm it with a short walk); sleeping puts the body in the
bed and waking takes it out (vanilla's own moves), so the harness allows those two steps.

## Evidence: a night on a dev server (the issue's "survives a night")

`scripts/bot-server.ps1` (node 1.21.1), a fresh survival world at normal difficulty (seed 4242), a body spawned from the console,
moved to open plains 340 blocks from the world spawn (`spreadplayers`), given an iron sword, a shield, 64 bread and 64 dirt,
the dev brain on (`/aiplayer reflex <name> on`), `time set 12000`, and the whole night left to run (daytime followed with
`time query daytime`, never skipped). Health and food come from `data get entity`; kills from the layer's INFO lines.

| run | build | dusk (health / food) | dawn (health / food) | what happened |
|---|---|---|---|---|
| SHELTER | final | 20 / 20 | 20 / 20 | walked one block to a spot that would do, closed in after 31 ticks with 10 dirt, no mob hold all night, took the shelter down at morning, 64 of 64 dirt back |
| IGNORE | final | 20 / 20 | 20 / 20 | two mob holds: 3 killed, none got away, health never below 20 |
| IGNORE | earlier | 20 / 20 | 15 / 17 | at least 8 killed (kills inside handed-over holds were not logged by that build), health as low as 3.5, ate 4 bread, retreated about 110 blocks; ended in a lake, where the dev brain (nothing else drives the body) lets it sink and surface 22 times |
| SHELTER | earlier | 20 / 20 | dead | **died**: "shot by Skeleton" at about 18700, at 2.2 health after losing 13 in ten seconds to arrows while retreating. No shelter was started at dusk and the layer logged no reason (the likely one is that the body's spot would not do, and the status line only said so on the tick it looked); the first mob hold came about 4500 ticks into the night and kept the body running and fighting, so the one shelter it began later was abandoned. Fixes made after it: the body looks for a spot within 6 blocks that will do, the reason stays in the status, and retreat spots in sight of a skeleton are marked down |

Reading the table: two nights survived on the final build are not a survival rate. The IGNORE nights differ by what the world
sent; the one death shows the weak point, a body at low health in the open under fire from several skeletons, which the layer
answers by running (a shield cannot face two ways) and by eating when nothing is after it.

Round two, with the real brain (`BotRuntime`, the default; `scripts/bot-server.ps1`, seed 4242, normal difficulty, `time set 12000`,
the same plains, an iron sword, a shield, 64 bread, 64 dirt, policy `IGNORE`, a goal "get 64 oak log" given with `/aiplayer goal`):

| run | dusk (health / food) | dawn (health / food) | what happened |
|---|---|---|---|
| goal running | 20 / 20 | no body | 9 holds in the half minute after the first mob came (game time about 14600), most of them short and with no swing (retreats; the cause the executor was told was a creeper); the executor gave up the goal at 36 of 64 logs (`PREEMPT_STALLED: held past the preemption limit`), and the body, with no goal and no owner, was despawned by the idle policy (6000 ticks) at about 20560 with 10 health. No death, no dawn reading. |
| goal paused | 20 / 20 | 12.5 / 17 | alive and loaded all night (8 holds, 3 killed, the lowest health 7.0 after a skeleton and several zombies, three meals); two holds were handovers into the vitals reflex |

A body with the real brain stays loaded for as long as its executor has a goal, running or paused (`wantsToStayLoaded` is
`executor.active()`): the first run gave it a goal that was then given up on, and it was despawned; the second paused the
goal at once, which keeps the body loaded, leaves the reflex layer exactly as active, and keeps the executor from giving up on
it. A night of creepers makes many holds (a creeper that targets the body now counts from 16 blocks), and the executor's
preemption limit (`ExecutorConfig`) ends a goal that is held that often: a limit to tune, not the layer's.

Round three (the last fix round), real brain, policy `IGNORE`, the goal "get 1000 oak log" given with `/aiplayer goal` and left running
(not paused: a count the body cannot finish in a night), the same seed, plains, kit and `time set 12000`; two nights, the second after
the layer was changed to finish a retreat it has begun when nothing is in sight any more (before, it stopped where it lost sight and
stood for the quiet period, which with the longer quiet of a retreat would have been a hundred ticks standing in the open):

| run | dusk (health / food) | dawn (health / food) | holds | goal at dawn | deaths | what happened |
|---|---|---|---|---|---|---|
| 1, stood when nothing was in sight | 20 / 20 | no body | 6 | given up | 2 | three fights (a kill or two each, health down to 9.7 once, a meal); then a hold that took the body from 10.7 to 2.7 health in 17 seconds with no swing (three mobs, a skeleton among them) and it was **shot by a skeleton**; the respawned body (what it carried is dropped where it dies) ran for 2000 ticks, health down to 4.8, and was **slain by a spider** after the goal was given up (`PREEMPT_STALLED` on the executor's 2400 held ticks, not on its count of holds); no body was logged in at dawn |
| 2, finishes the retreat | 20 / 20 | 20 / 15 | 6 | given up | 1 | one fight (a kill, 4 swings), then **slain by a spider** in a hold of 330 ticks that began at full health and was a retreat at 2.0; the respawned body, with nothing in its pack, was held four times (205, 493, 432 and 1217 ticks) and the goal was given up for the held ticks (`PREEMPT_STALLED` again); alive and calm at dawn, no goal |

Reading it: 6 holds in a night against 24 in round two's first run (and in neither run a count that the executor's limit of 8 in 1200
ticks came near); the goal went the way of the held ticks instead, after a death. The log names a hold's cause only at its end,
where it is mostly "calming down", so which of the holds were for a creeper is not in it. The goal was **not** still running at dawn in either
run, and the body died in both: each night ends at a death with the pack dropped far from the respawn point, after which a body with
nothing in its pack is held for most of the rest of the night by what it cannot fight. What killed it was a skeleton's arrows and a
spider's bites during a retreat at low health, the layer's known weak point (round one's only death was a skeleton too); it is
not something this round's items touch, and the report lists it as an issue to take up.
