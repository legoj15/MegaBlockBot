# The bot runtime (`game.bot`, `game.task`)

What turns a goal the owner states into crafting a body does. Design: `AI-PLAYER-DESIGN.md` 3 (3.4 and its
amendments are the contract), 4.2 and 6. Bot node only (1.21.1). Server thread throughout, except the planner thread.

## How a goal flows

1. Words arrive: `/aiplayer goal <name> <text...>` or a chat line from the body's owner that starts with its name
   (`Bot1, make 4 planks`, `Bot1 make 4 planks`). Both go to `BotTalk.interpret`; the other commands (`cancel [all]`,
   `status`, `pause`, `resume`) skip the language layer and go to `BotTalk.apply` as the matching `Intent`. Commands need
   `body.mayCommand(source)`; chat is the owner's voice only (an op's chat line is not a command). The supply commands
   (`use chest at <x> <y> <z>`, `forget chest at ...`, `chests`) are words of the fixed grammar like any other, so they
   take the same two ways in and the same guard; see "Supply containers" below.
2. `LanguageLayers.current()` (the `FixedGrammar` until a model-backed layer replaces it with `set`) answers with
   `Intent`s for a `LanguageContext` built per message (`RegistryItemSearch`, dimension, owner and bot position, the status
   paragraph, no blueprints, the game's enchantment ids). A layer that already answered is applied at once; one that
   completes later is applied after a hop to the server thread (`server.execute`).
3. `SetGoal` calls `BotRuntime.submit`: the executor starts the goal or queues it (design 3.4).
4. `GoalExecutor` asks its host, `BotRuntime`, for a snapshot (`SnapshotCollector`), then `plan`: `BotPlanner` runs the
   `SolverPlanner` (over `RecipeBooks.current()` and `AcquisitionIndexes.current()`, rebuilt when either publishes a new
   instance) on one daemon thread `MegaBlockBot planner` shared by every bot, and returns a cancellable `PlanTicket`.
   The thread is created on first use and shut down in `onServerStopping`.
5. For each leaf the executor calls `createTask`, which is `TaskRegistry.create(leaf, hints)`. A kind nobody registered
   gets a task that fails on its first tick with `Failure.Internal` naming the kind, so the goal ends with a reason.
6. A result goes to `BotRuntime.publish`: one log line (goal, status, matched/required, failure), then the bot says it
   (`BotSpeech.result`: what was asked, the status, and for a failure the typed reason in words).

Where the bot's words go (`BotRuntime.say`): always the log; the owner as a system message `<Name> text` when online;
the command being carried out gets the same line unless it came from that owner (they would read it twice). With the owner
offline: the log and the command's feedback only. `/aiplayer status` is `BotSpeech.status`: the goal, its state, the step as
`Step i of n: <kind> (<goal>)`, then `On it for 2m 13s: <what the task says>` (how long the body has been on the
step: `BotRuntime` notes the game time when the executor first asks for a task for that node, a retry of it keeps
counting; the phrase is `Task.progress()`, empty by default, for a mine task "heading for a block at x y z", "waiting
for its air to come back" (the wait for air is made out of the water: the navigator gets the body's head above the
surface first, and only then counts the wait), "waiting to look again", "exploring for oak_log" and how many blocks it
set aside; when the step ends because every block of the leaf's source it found was beyond reach, the failure is
`NoSource` carrying that source, which the executor excludes so the next plan gathers the item another way), replans,
queue; an idle bot repeats its last result.

## `BotRuntime` (one per body, registered with `BotBrains.setFactory` in `BotFeature.init`)

- `tick`: asks `ReflexSources.current()` for this tick's `ReflexHold` (default: none; the reflex layer sets its own),
  ticks the executor, and resets `body.input()` once when the task the executor last created stops being RUNNING or PAUSED.
  A hold a command put on a body that never had a task (`/aiplayer input`) is never touched.
- `progressSignature`: hash of block position and every inventory stack. `profile`: `STRICT_SURVIVAL`.
  `dropsRecoverable`: the death's dimension is loaded and the death is under 5 minutes (6000 ticks) of game time old.
- `onDeath` / `onRespawn` map to the executor's (`Failure.Died` from the `DeathRecord`); `wantsToStayLoaded` is
  `executor.active()` or a goal in the queue: a goal typed while the reflex layer holds the body is queued and has not
  started, and the body must still be there when the hold lifts (`aQueuedGoalKeepsTheBodyLoaded`).
- `NavCommands` (`/aiplayer nav`, a dev command) replaces the body's brain with its own `DevBrain`: a body driven by it has
  no runtime (`BotRuntime.of(body)` is null and the goal commands say so).

## Save form

`BotBrain.save` writes into the brain compound: `schema` (1), `executor` (the executor's plain-map save form as NBT) and
`memory` (the body's `BotMemory`). An empty compound loads as nothing; any other schema throws, and the body then replaces
the brain with a fresh one (its contract).

- `NbtForm` converts the plain-map form (`String`, `Long`, `Double`, `Boolean`, `List`, `Map<String, ?>`, no null) to NBT
  and back exactly: String-StringTag, Long-LongTag, Double-DoubleTag, Boolean-ByteTag, map-compound. A list is a list of
  `{v: element}` compounds, because an NBT list holds one tag type and a form list may mix maps and strings. Writing
  anything else throws; reading takes an int or short as a long and a float as a double.
- The memory belongs to the body's UUID, which the brain does not know while the body is still being built (`load` has no
  body), so a loaded memory is parked and installed by `BotMemory.restore` on the first `tick`/`onRespawn`/command. The
  executor is loaded with a null body for the same reason and replans on its first tick (design 3.4: a restart replan).
- Server restart: the body record (`BodyManager`) already brings back every body whose brain wanted to stay loaded; its
  player data carries the brain's compound. Evidence: `BotRuntimeGameTests.aGoalInProgressSurvivesALogout` (logout and
  spawn through the manager) and the dev-server restart in the report of the brief.

## The task registry (`game.task.TaskRegistry`)

`TaskRegistry.register(kind, factory)` from a feature's `init()`; one factory per kind (a second registration throws).
A task extends `core.task.AbstractTask<BotPlayer>`, acts only through the body it is handed each tick, finds the body's
memory with `BotMemory.of(body.getUUID())`, and must release what it holds in `onAbort` and `onPause` **and before it
returns its own `Failed`** (the executor does not abort a task that failed itself; `CraftTask.stop` does this).
`waiting()` is true while it waits on the game (a break, a pickup) so the stuck watchdog leaves it alone; `checkpoint()`
returns hints for where to look after a restart, never facts.

Registered here: `craft`, `smelt` and `equip`. `navigate`, `reach`, `mine`, `fish`, `recover_drops`, `deposit` and `withdraw` are
registered by the navigation work (`TaskFeature`), not here.

What the navigation tasks need from this runtime:

- `BotNavigator.of(body)` is ticked by whoever owns it: a `navigate`/`mine` task calls into it from its own `onTick`, and
  must `stop()` it in `onAbort`/`onPause` (the runtime only resets `BodyInput`, it does not stop the navigator).
- The runtime resets `body.input()` on the tick after the last active task ends, so a task need not clear the input of a
  task that ended; it still clears its own on pause and abort.
- `BlockSafety.of(server).mayBreak/ noteBotPlaced` and `BotMemory.notePlaced/forgetPlaced` are the contract for any block
  the bot places or breaks (see `CraftTask.placeTable`).
- The snapshot reads `BotMemory` for stations and containers; a `deposit` task notes what it saw (`ContainerAccess.note`), as `withdraw` and `look_inside` do.
- `Failure.Died` is reported through `onDeath`; a task whose body died does not also fail with `Died`.

## `craft`

Leaf params: `recipe` (the game recipe id), `times`, `station` (empty or `minecraft:crafting_table`), optional `layout`.

Everything about the station block — a table found standing or put down, walked to, opened, broken and taken up again,
with the walks, the wide search out of a protected area and the interruptions — is `game.task.StationTask`, the base
class `CraftTask` and `SmeltTask` share; `CraftTask` itself is the crafting grid. What the base class asks of its
subclass: the station's item and block and what its failures call it, whether a standing one may be used where it is
(any table, but only a furnace the body's memory says it placed) and whether it may be taken up now (a furnace only
with a tool that makes it drop), whether the station's menu is open, whether the work is done, what must still be
carried before anything is put down, and the work itself, which runs with the menu open.

- The recipe is found again in the game by id (`RecipeManager.byKey`), because a shaped recipe's grid positions are not in
  the planner's book. Cells are the recipe's non-blank ingredients in order (= the planner's slot order); a shaped recipe
  keeps its shape at the grid's top left, any other crafting recipe fills cells in order.
- `layout` (`CraftLayouts`): runs `crafts*item,item,...` separated by `;`, one item per slot in slot order, the runs'
  crafts adding up to `times`. Followed exactly when present; a malformed layout, an item the ingredient does not accept,
  or a count that is not `times` fails with `Internal` rather than re-deciding. Without a layout each slot takes the first
  alternative (the ingredient's own item order) the body carries. `SolverPlanner` writes the param (`CraftLayout.encode`,
  `core/planner`, from the solver's `SlotLayout`: one item per non-blank cell in row-major order, the same order as
  `cellsOf`), and the two formats agree; `aCraftFollowsTheLayoutThePlannerWrote` plans a pickaxe with the real planner,
  reads the layout back, runs it through the runtime and then through the task with a stock that cannot supply it. On the
  recipes looked at (pickaxe, stone pickaxe, chest boat) the planner's layout is also what "first alternative held" would
  have chosen, so a test cannot tell the two apart by what is consumed; the second half of that test is what can.
- A craft is: pick up a stack with a click, right-click one item into each cell that wants it, put the cursor back, check
  the result slot offers the recipe's result and that carried storage has room for all of it, shift-click the result out,
  check it all arrived, empty the grid. All through `AbstractContainerMenu.clicked` (`MenuHand`), one craft every 3 ticks.
  The crafts still owed are worked out from the leaf's goal (an absolute count), so a task started again after a restart
  makes only what is missing.
- Which slots a craft uses: the main inventory and the hotbar (`MenuHand.CARRIED_SLOTS`, the 36 of `Inventory.items`), for
  ingredients, for the table item and for the result; an item worn or held in the off hand is in use. Every count the task
  makes is `MenuHand.carriedCount`, so what it counts is what it can find (it used to count the off hand with
  `Inventory.countItem` and then fail to find the item). The snapshot the planner reads still counts worn and off-hand
  stacks as stock (`SnapshotCollector`, not touched here), so a plan that leans on one ends in `MissingInput`.
- The result is taken whole or not at all. A shift click on the result slot moves what fits, consumes the ingredients and
  drops the rest at the body's feet (1.21.1's `quickMoveStack`: nothing is destroyed, but the old task counted the craft as
  made, `aResultThatDoesNotFitIsNotTakenAtAll`). `MenuHand.roomFor` says what carried storage can take; short of that the
  task fails `NoRoom` naming the result item, with the ingredients given back from the grid and nothing taken from the
  result slot, so nothing is lost and nothing lies on the floor.
- No station: the inventory's 2x2 grid (`player.inventoryMenu`, grid slots 1 to 4).
- Table: use one within reach (`canInteractWithBlock`); otherwise put the table item down where a player could
  (`TableSites`). A spot is usable when it is replaceable, no entity that blocks building is in it, and a neighbour that is not
  itself replaceable has a face the eyes can see and reach (a ray from the sneaking eye, `Level.clip`, must land on that very
  face within `blockInteractionRange`). That is all the game asks (`BlockItem.useOn`, `BlockPlaceContext`): a sturdy face is
  not needed, so a table goes on leaves. Two things are asked beyond the game: the block below the spot has a collision
  shape (so the table's item comes to rest beside the body when the table is broken, not down a drop) and
  `BlockSafety.mayBreak` allows breaking it again (so no spot inside a protected area is ever offered). The click is made sneaking (`BodyInput.sneak`, one tick to take effect;
  released a tick before the table is used), as a player builds against a chest or a door, because the click on an
  interactive block would use it instead. The look that finds the sites and the click use the same eye (the sneaking one,
  `TableSites.sneakingEye`). `interactBlock` then does what the packet handler does (reach, `mayInteract`,
  `ServerPlayerGameMode.useItemOn`) and the world's answer decides: the first site whose spot becomes a table is the one.
- No usable spot within reach: the body walks, with `BotNavigator.goTo`, to the nearest place it can stand (feet and head
  free, a full-height floor) that has a usable spot beside it, searched shell by shell outward within
  `TableSites.DEFAULT_RADIUS` (12) blocks in every direction, and looks again from where it arrives. Up to
  `CraftTask.WALKS_ALLOWED` (4) walks; a walk that fails (`NO_PATH`, `STUCK`, or a navigator that stops three times) makes
  the next nearest place the next try, and a walk that gets there clears the remembered failure.
- Leaving a protected area. Inside the protected square around the owner's respawn point or the world spawn block safety
  refuses every spot, since the table could not be taken up again. When the search found candidates and every one of them was
  refused, it goes on over the shells beyond the normal radius out to `TableSites.WIDE_RADIUS` (`PROTECTED_RADIUS + 1 +` the
  sideways reach of a look, 20 with the square at 16: far enough to stand beyond the far edge of the square, and to put the
  table down beside that stand, from its middle) and the body walks there. The search over the cube costs the cheap block-safety
  question first and the entity query and the rays only for spots that pass it. `Protected` is never the answer for "nowhere
  to put my own table"; with no place even within the wide radius the answer is `NoPlace`.
  `TableSites.setRadii(bodyUuid, radius, wideRadius)` narrows both for one body (a test builds "nowhere" and "a way out that
  is not 17 blocks long" in a 16-block arena that way; the arenas of other tests stand five blocks off).
- The table is opened with a use, the craft made, the menu closed, and the table broken the way a client does (with the hotbar slot
  that breaks it fastest, else one that cannot wear out, so the pickaxe just made keeps its durability;
  `START_DESTROY_BLOCK`, per-tick progress, `STOP_DESTROY_BLOCK` at 1.0), then up to 200 ticks for the item. Before the
  break starts, carried storage must have room for the table's item (`MenuHand.roomFor` again): with none the table is not
  broken -- it stays standing and remembered as the body's, and the tidy step or the next craft takes it up once there is
  room -- because the item of a table broken into a full inventory would lie on the floor for good. The item hops
  about and now and then rests outside the body's pickup box (a block on each side), so from 20 ticks after the break the
  body goes to a table item within `CraftTask.DROP_SEARCH_RADIUS` (12) blocks of where the table stood: through
  `BotNavigator.goTo` while the item is more than `NEAR_DROP` (2.5) away, by its movement input for the last steps (or all of
  them when the navigator will not go; the input is reset when the item is in or the task lets go). A table the body did not place is left standing; a table is the body's when this task placed it or
  `BotMemory` says an earlier one did. The task records what it placed in `BotMemory` (and `BlockSafety.noteBotPlaced`). A
  remembered table that is gone when the task looks for it or opens it is forgotten (`BotMemory.forgetPlaced`).
- Interruptions. A reflex holds the body, which pauses the task, and may carry it several blocks off in the middle of a craft
  or of the take-up. Resume looks again from the top (`PREPARE`): a table of this task's (the one it placed, found or was
  given in the checkpoint) that stands out of reach is walked back to with `goTo(table, 2)` within `RETURN_RADIUS` (24) blocks
  (up to `RETURNS_ALLOWED` (3) walks that end with it still out of reach, then `Unreachable`), before the menu is opened or the
  break begun again. A table already broken is not forgotten: its item is looked for within `DROP_SEARCH_RADIUS` of where the
  table stood, and walked to wherever in that radius it lies; none there, nothing to take. A body pushed out of reach of the
  table while it is being broken is walked back the same way. Covered by `aCraftInterruptedAtItsTableWalksBackToIt`,
  `aTableBeingTakenUpWhenTheBodyIsCarriedOffIsWalkedBackTo` and `aTableItemFarFromTheBodyIsWalkedTo` (the goal is paused at the
  moment, the body walked off through its input until the table is out of reach, the goal resumed).
- A break the rules refuse (`BlockSafety.mayBreak` now says no: a claim arrived while the craft ran), a take-up the inventory
  has no room for, and a break or a pick-up that runs out of its ticks (400 for the break, 200 for the item) do not fail the
  craft: the craft's result is what the goal asked for, so the task completes, the table stays standing and stays in
  `BotMemory` as the body's, and the log says why (`MegaBlockBot` info line "leaves the crafting table at ... standing"). A
  broken table's item that never comes back is nothing the code can remember, so only the log names it. The tidy step and the
  next craft that finds it try again. Covered by `aRefusedTableBreakStillCompletesTheCraft` and
  `aTakenUpTableWhoseItemDoesNotFitIsLeftStanding`.
- Failures: `MissingInput` (an ingredient or the table item is not carried), `TargetGone` (unknown recipe id), `Protected`
  (a table that stands within reach and will not open: the failure names the table's position, the executor excludes that
  block, and the next plan's craft puts its own table elsewhere), `NoPlace` (the body found nowhere to put the table down: no
  place within the radius, none out of a protected area within the wide radius, or the walks it allows all failed; the failure
  carries `what` ("put a crafting table down") and the widest `radius` any of its searches looked over, not only the last
  call's), `Unreachable` (the game refused every click that would put the table down, it could not walk back to the table it
  had put down, or the game would not start breaking it), `NoProgress` (the body would not sneak to place the table, or the
  table could not be taken into the hand: conditions of the world, not bugs), `NoRoom` (no room for the whole result). A
  failure with a table placed leaves the table standing, remembered; the next craft that needs one finds it. Every exit, a
  completion included, goes through `BodyTask.release`.
- Why `NoPlace` gives up at once. The executor's answer to `Unreachable`, `TargetGone`, `NoProgress`, `TimedOut` is one retry
  with a fresh task and then a replan; to `MissingInput`, `MissingTool`, `NoSource`, `Protected`, `Hazard` a replan. Nothing in
  a craft leaf, in `Exclusions` or in the snapshot tells the planner where a table fits, so every one of those replans is the
  same plan again, and the goal ends after three of them (the live runs: three replans, each failing the same way, a canopy
  and then the world spawn's protected square) with the same failure. `NoPlace` (`Failure.Kind.NO_PLACE`, design 3.4) is the
  kind for "the body looked for somewhere to do this within `radius` blocks and found nowhere, and a new plan would find the
  same": the executor gives the goal up at once with it as the result's reason, no retry and no replan, like `PreemptStalled`.
  `NoRoom` gives up the same way: the inventory cannot take what the step produces, and a replan would plan the same step into
  the same full inventory. `Internal` means a bug again, and `BotSpeech` says so.

### A station left standing

The planner counts a crafting table in the inventory, never in the world (`SolverPlanner` ignores `Snapshot.stations`), so a
table this body put down and never took up (the server stopped between placing and breaking, a cancel, a failure) costs
the replanned goal four planks, or fails it when there are none to spare. `BotRuntime` therefore looks for one after a
load and whenever a goal ends (`tidyArmed`): the nearest crafting table or furnace within reach that `BotMemory` says
this body placed — a furnace only while it holds nothing (breaking one that still holds items would spill them) and
while the body carries a tool that makes it drop, under the smelt task's rule below. Found, it runs
`CraftTask.takeUpStation(level, pos)` (the public face of `StationTask.takeUp`, for the base class is package-private:
the station's own break and pickup, working nothing) before the executor is
ticked again, so the next plan sees the station in the inventory. A failure there is logged, not a goal's failure; the
station stays, remembered. A station the rules do not let the body break stays standing and remembered too (the tidy task completes,
and looks again at the next goal's end). Covered by `BotRuntimeGameTests.aTableLeftStandingByALogoutIsTakenUp`.

## `smelt`

Leaf params: the craft leaf's with an empty `layout` — `recipe` (a variant id: the game recipe's id, `#` and the fuel
class its run is sized for), `times` (runs) and `station` (`minecraft:furnace`, `minecraft:blast_furnace` or
`minecraft:smoker`).

- The recipe is found again by id and must be an `AbstractCookingRecipe` of the station's kind; the fuel class and the
  run's counts are read back from `CookingRecipeSource` (`variantOf`, `fuelOf`, `runOf`), never worked out again. One
  run takes `p` of the ingredient and `q` of the fuel and makes `p` times the recipe's result; the leaf's goal is the
  absolute count after the step, so a task started again makes only what is still missing, in whole runs.
- The furnace is a container, and block safety keeps the body out of containers it did not place, so a standing
  furnace of the right kind within reach is used only when `BotMemory` says the body placed it; otherwise the carried
  one is put down. The ingredient and the fuel are asked for before anything is put down (`MissingInput` naming the
  item and the count), so a smelt that cannot run puts nothing down; a furnace that is not carried is the same failure
  once no usable one stands. The body's own furnace is taken up again at the end only when the body carries a tool
  that makes it drop (`ItemStack.isCorrectToolForDrops`; a furnace broken by hand drops nothing): otherwise it is left
  standing, remembered as the body's, and the task completes. What is still inside is taken out through the menu
  before the furnace is broken (an unburnt rest of the fuel never is: a run is whole).
- The work, in the open `AbstractFurnaceMenu` (slot 0 ingredient, slot 1 fuel, slot 2 result), by clicks as a player
  makes them (`MenuHand`): the result is taken out whenever the result slot holds any (whole or not at all, the
  craft's rule, `NoRoom` when it cannot be), the ingredient for the runs still to do goes in — at most a stack, and
  more when the slot has emptied — then the fuel the same way, and what the slots already hold counts towards the
  runs. The task is done when the inventory holds the count the goal asks for (or, without such a goal, when the
  runs' results have been taken out). While the furnace burns and there is nothing to click the task is `waiting()`,
  its menu kept open, the furnace's own LIT block state telling "burning" from "nothing happens".
- Interruptions and exits: on pause, abort or failure the cursor is empty and the menu is closed; what the task put
  into the furnace stays there, the furnace stays remembered, and a resumed task walks back to it, opens it and
  carries on from what the slots hold (an ingredient already in the furnace counts as carried for the runs still to
  do). A body carried out of reach while the work runs closes the menu itself and is walked back.
- Failures: the station failures the base class already has; `TargetGone` for a recipe id the game does not have, one
  that is no cooking recipe of the station's kind, or one whose fuel class the game no longer knows; `MissingInput`
  when the ingredient, the fuel or the furnace is not carried; `NoRoom`; and `NoProgress` when the furnace holds the
  ingredient and the fuel and has not burned for `SmeltTask.NO_PROGRESS_TICKS` (100) ticks — vanilla lights a furnace
  within a tick or two of both slots being filled, so that is "something else is in a slot, or the game refuses the
  recipe".
- Time: a stack takes 12800 ticks in a furnace and the executor's default budget for a task is 12000, so the `smelt`
  kind has its own budget where the runtime builds its `ExecutorConfig` (`BotRuntime.SMELT_BUDGET_TICKS`, 72000 — one
  hour of game time, five and a half stacks, still bounding a smelt that has stopped for good).
- `SmeltGameTests`: the two-run sticks variant ends with 2 ingots and nothing lost; the same without a pickaxe leaves
  the furnace standing and remembered; a furnace somebody else put there is not opened, and the body puts its own
  down; no fuel is a `MissingInput` naming a fuel item with nothing put down; a pause and a resume in the middle of
  the burn still ends with the 2 ingots; and through the runtime a body asked to have 1 iron ingot ends satisfied.

## `mine`

Breaks blocks through the navigator until the body holds the leaf's count of the item (`game.task.MineTask`; the search, the
set-aside blocks and the wet-target rules belong to the navigation work). What this change adds here: carried storage must be
able to take one more of the item — `MenuHand.roomFor`, the same question the craft asks of its result — before the mine
command starts, and again whenever a block was broken and the held count has not risen (a drop that cannot be picked up). No
room ends the step `NoRoom` naming the item (`mineWithAFullInventoryFailsNoRoom`), and the executor gives the goal up at once
like `NoPlace`: no block is broken for a drop that would be lost, and a replan would plan the same step into the same full
inventory.

A failed exploration is `NoSource` only when it explored and found none: the navigator's own failure kind says which is
which. An engine error ends the step `Internal` with the exception, and a body that is no longer the navigator's (replaced,
removed, moved to another dimension) ends it `Unreachable`, the failure the reach task answers the navigator with — neither
says anything about the source, so neither has the executor exclude one
(`MoveAndMineGameTests.aBodyDespawnedWhileExploringDoesNotFailNoSource`).

## `recover_drops`

The node the executor puts in front of the new tree after a death (`RecoverDropsTask`): walk to each place the death's items
fell and pick up what still lies there. Drops move — items roll, fall and float — so at each recorded position the task looks
for item entities within a named radius (4 blocks) horizontally and down to a named depth (8 blocks) below it, and walks to
each it finds at the item's own position, as a player walks over an item; a position with nothing within that radius is done
at once. An item the body cannot get at is skipped, and so is one the pickup wait (40 ticks) ran out on; a drop that has been
picked up or despawned is never waited for. The task never fails the goal: the node is best-effort, always completing.

## `build`

Leaf params: `blueprint` (its name), `dimension`, `anchor` (`x,y,z`, the min corner of the blueprint's box). The leaf's
goal is the `Goal.Build` itself, so the executor judges it on the snapshot's structure report of it.

- **Where blueprints come from** (`game.build.Blueprints`, registered by `BuildFeature.init()` from `GameBootstrap`): a
  name is looked up first as `<config dir>/megablockbot/blueprints/<name>.nbt` (compressed structure-template NBT, read
  with the game's own `StructureTemplate.load` over the registries, and its blocks then from the lists of the template's
  own save form: the palettes are private and the public block filter answers for one named block only),
  then, when the name parses as a resource location, as one of the server's structure templates. A name is a file name
  only (no path separator, `..` or colon); at most 4096 blocks and 48 along a side, or the blueprint is refused with a
  message saying so. This brief builds plain blocks: a blueprint holding a block no item places, a block entity, or the
  second block of a two-block thing (the upper half of a door, the head of a bed) is refused naming the block. Loaded
  blueprints are cached per server and dropped on a datapack reload, on stop, and when the file's modification time
  changes. `/aiplayer blueprint save <name> <from> <to>` (permission level 2, same name and size rules) saves the blocks
  of that box of the caller's world without entities, with the game's own `fillFromWorld` and `save`;
  `/aiplayer blueprint list` lists the folder.
- **The report** (`SnapshotCollector.addGoalFacts`): a snapshot taken for a goal (or a goal in its queue) that is a
  `Goal.Build` carries a `StructureReport` under `blueprint@anchor` — `required` the blueprint's positions, `matched`
  how many hold the blueprint's block already (the block, not every state property, so a log placed along another axis
  counts), and `missing` the items that place the still-wrong blocks. `known` is false when any position's chunk is not
  loaded or the goal's dimension is not the body's; an unknown blueprint name gives no report at all.
- **The plan** (`SolverPlanner.buildPlan`): no report is unplannable (the blueprint's name is not known); a report that
  is not known plans one `navigate` leaf to the anchor (radius 8: near enough that the footprint's chunks load), as an
  unseen container is looked into first; a known report plans an item plan per missing item and count the body does not
  hold (relied on, not drawn, in a fixed order — the containers the body may take from are asked for them like any
  other item the plan relies on) and then the one `build` leaf. Nothing missing plans nothing.
- **The task** (`BuildTask`, kind `build`, registered in `TaskFeature`, budget 36000 ticks — see `BotRuntime`): arms the
  per-body break filter as the mine task does and starts the navigator's `build`, which hands the engine's
  `BuilderProcess` a static schematic of the blueprint. The engine's settings for the command: `acceptableThrowawayItems`
  emptied (so the pathing never bridges with dirt — the build places only blocks the blueprint names and only from the
  inventory), `allowInventory` on (the hotbar is filled from the main inventory), no layers, no repeats, no orientation
  flips, `buildIgnoreExisting` off, and the break filter narrowed to the blueprint's box for as long as the command runs
  (the filter reads the box live, so a command that ends opens the world back up).
- **When the builder lacks a block**, upstream pauses and says what is missing (`BuilderProcess`:
  "Missing materials ... Unable to do it. Pausing."), which would wait for ever: the navigator fails the command the
  moment it sees the pause, naming the block the body is short of (`MISSING_BLOCKS`, the count read from the world and
  the inventory). The task makes that `MissingInput` naming the item and the count short — the executor replans, and the
  planner gets what is missing from the next report. A build that cannot go on another way fails `BUILD_STUCK`.
- **A block the rules protect** does not pause the builder (it keeps trying the same spot), so the task watches itself:
  no placement, break, or step toward the anchor for 400 ticks ends the leaf. The end is `Protected` naming the position
  when a wrong, non-air position inside the blueprint may not be broken by this body's rules; `Unreachable` for the
  navigator's other failures; `NoRoom` never applies (nothing is picked up). It pauses, resumes and aborts like the mine
  task — the builder is stopped and a resumed task starts the build again, and the builder skips what stands.
- `progress()` says how many of the blueprint's blocks stand.

## `fish`

Casts a rod into water until the body holds the leaf's count of the item (`game.task.FishTask`; the source is
`Source.Fishing`, priced by the `fish` cost). Leaf params: `item`, `count` (how many to bring in; the goal's absolute
count wins, as for a mine leaf) and `table`, the loot table the source was read from.

- **The plan gets a rod first.** A `fish` leaf needs `minecraft:fishing_rod` in the inventory, kept, not consumed (as a
  crafting station is: relied on, never drawn — it lands in `relied`, so a rod lying in a container the body may take
  from is withdrawn instead of crafted). `SolverPlanner.prerequisite` plans it the way it plans a mining source's tool:
  through the ordinary item plan (three sticks and two string at a table), as a group node before the first `fish`
  leaf, once per plan however many fish leaves it has (once one is in the working stock the next fishing source plans
  nothing). A plan whose rod cannot be had makes the source unusable, and the item's other sources are tried as they are
  for a mining source whose tool cannot be had; a rod whose own ingredients would have to be fished for is a cycle, and
  the source fails that way too.
- **Water.** A standable block with a water source 3 to 6 blocks away horizontally, at or below the body's feet and
  with open air above it; the body stands on the bank, never in the water. Looked for in the chunks already loaded
  within `WATER_SCAN_RADIUS` (32) of the body; none in sight, the body explores as `MineTask` does, bounded by
  `FishTask.Search` (a `NoSource` past it, carrying the leaf's item and source id, so the executor excludes fishing).
- **Casting.** The rod is taken into the main hand (`MenuHand`, a hotbar select or a swap), the body looks at a point on
  the water's surface for two ticks (a look written this tick is only in the rotation from the next, and the game aims
  the hook between the rotation and its predecessor), and the rod is used — the same `gameMode.useItem` call the body's
  other item uses go through. The game spawns the hook; the player's `fishing` field points at it.
- **The bite** is not public on `FishingHook` (1.21.1: the `biting` flag and the `nibble` countdown are private, and
  `retrieve` awards the loot it is asked about, so it cannot be probed). What a player sees is the float dip, and the
  game makes the dip: while a fish bites, the hook's bobbing state adds a downward impulse of up to 0.1 blocks per tick
  each tick, which a settled float's bobbing never reaches. So the task watches the hook it cast, and once the hook has
  been in water long enough for its splash to settle (`SETTLE_TICKS`), a vertical speed below `BITE_DY` is the bite:
  the rod is used again at once, which reels in, and the catch flies to the body. A cast whose hook is gone, or comes
  to rest out of the water, within `LAND_WAIT_TICKS` ticks is reeled in and made again at another water cell; three bad
  casts at one stand move the body to another. A hook that caught an entity is taken up and cast again.
- **The catch** is picked up like any item and everything caught is kept; the task is `waiting()` while a hook is in the
  water and while a catch flies to the body, so the stuck watchdog leaves it alone. Fishing is random: a bite comes
  after 5 to 30 seconds and the wanted item is one of many in the table, so the `fish` kind has its own task budget
  (36000 ticks in `BotRuntime`; a salmon is a quarter of the fish, so some eighty casts cover almost every run). A full
  inventory ends the step `NoRoom` naming the item, as the mine task's does, before the first cast and after each catch.
- **Interruptions and exits.** On pause, abort, failure and completion the hook is reeled in (the rod used once more
  when a hook is out) and the input is reset. A resumed task looks again from the top. `MissingInput` naming the rod
  when none is carried (the rod is not a tool of any class the failure table knows) or when the one carried broke.
  `NoSource` naming the leaf's item and source when no water can be found. The checkpoint holds the dimension, how far
  the search has reached and the stand it last fished from — hints, checked against the world.
- `FishGameTests` (registered with the navigation work's, in `NavFeature`): a catch from a pool with open sky above it
  (open water is what gives the game's normal waiting times) and Lure III on the set-up rod (five seconds off the wait
  per level); the missing-rod failure; `NoSource` in a sealed room; a pause while the hook is out, with no hook left
  during it, and the catch afterwards.

## Supply containers, `look_inside` and `withdraw`

The owner names the containers a body may take from (design 3.5): `use chest at <x> <y> <z>` (the position in the
dimension the words were read in; the command word is always `chest`, the block there may be any container), `forget chest at ...`, `chests`. `BotTalk`
carries the three `Intent`s out through `BotSupply`, which answers in a sentence: the dimension must be the body's, the
position loaded, and a container with slots must stand there (`SupplyChests.find`: a double chest named by either half is
one inventory, its two halves recorded as one `BotMemory.SupplyContainer` together with the block that was named).

**Naming records where the container is and which block it is, and nothing of what is in it.** The body does only what a
player can, and a player cannot see into a closed chest, so what a named container holds is unknown until the body has
opened it, and the planner counts nothing from a container whose contents are unknown. `use chest at` therefore also
submits the body's own goal `look_inside` for that container (`LookInside.goal`, a `Goal.Custom`), which the executor
queues like any goal: ahead of whatever the owner says after it (`use chest at 1 2 3; make 4 stick` looks first and then
plans the sticks knowing what is there) and behind a goal already running. Naming a container again drops what was noted
of it (the owner's word is for the container as it is now) and looks again. The goal's plan is its strategy,
`LookInside` (registered with the planner in `BotPlanner`): one `look_inside` leaf. Its postcondition (registered with the
executor's `Postconditions` in `BotRuntime`) reads the snapshot: the container's sight is `remembered`, which is true once
the body has a note of it; a container nobody names any more has nothing to look at and counts as done, and in another
dimension it cannot be done.

The same leaf is the whole plan of a stockpile goal whose target the snapshot has no sight of (the body has never been
inside it): `SolverPlanner.stockpilePlan` plans the look and nothing else (`LookInside.leafBeforeDeposit`, whose goal says
`for=stockpile`: there, no sight at all means "not looked at yet", not "nobody names it"), and the container is the goal's
word for the look as it is for the deposit. With the note made the goal is judged (a chest that already holds enough ends
it), and when it is not met the tree has run out unsatisfied, so the executor replans and the deposit is planned knowing what
is in the container. A container that needs nothing moved into it plans no leaf at all: `stockpilePlan` asks for what is
left to move, and with none left it returns an empty plan rather than a `deposit` of zero, which the task would refuse (its
`count` must be positive). Without this the postcondition of a container nobody has opened is UNKNOWN for ever (the
executor's one `Reach` does not tell the body what is inside), and no deposit starts.

`BotMemory` keeps the list (saved with the body, the `supply` list of its compound, each entry with its block; an entry
with no block is skipped on load; saves from before the list load as none). Notes of what a container held are per
inventory: a double chest is one note (with its second half), whichever half the leaf named, and noting an inventory
replaces whatever was noted under any of its blocks, so naming a chest that has since become half of a double chest
leaves one note for the pair. `BlockSafety.mayUseContainer` lets a named container through the container and workstation
rules for that block, never the claim rule, and only while the block that stands there is the one that was named (the
same rule as the tasks, below); `mayBreak` never reads the list. Using a container is not subject to the
protected radius, and the same holds here: a named chest inside the radius is used, and still cannot be broken.

The snapshot shows each container the body may take from (placed by it, or named) as one `ContainerSight` with
`mayTakeFrom`. A double chest is one sight, kept at the half with the lower position hash and carrying the other half
(`ContainerSight.other`): `Snapshot.containerAt` answers for either half, so a stockpile goal that names the far half is
about the same inventory (its postcondition, its plan, the exemption of its own target from the supplies and the
fingerprint all use that one lookup). What a sight holds is the body's note, never read from the world, and it is `remembered` (in range or out of
it) until the body opens the container again. A named container the body has not opened is listed with nothing and not
`remembered`. A container whose chunk is loaded and no longer holds that block (both blocks, for a pair) is shown with
nothing and may not be taken from, and the same question is asked of a named container the body has no note of at all
(`SupplyChests.stand`, one rule for the owner's word and the note alike): named and never opened, it is one the body may
take from only while the block that was named stands there. A position that is not loaded decides nothing against a
container, so a named one there is still listed as take-able, until the body can see it. The planner (`SolverPlanner`,
"Containers the body may take from") counts what such a container holds before anything is gathered or crafted and puts one
`withdraw` leaf per container and item in front of the
plan, nearest container first, never more than the container is known to hold and never more than the plan needs after
what the body carries; a container ruled out by a `PROTECTED` failure or by a `NO_SOURCE` failure of its own leaf (its
source id, `withdraw:<container>`), and a stockpile goal's own target, are not counted. The fingerprint of a plan reads the
items it reads the stock of and the tools that could stand in for the ones it needs (a better pickaxe in a container is
taken as carried), in each container the body may take from.

`ContainerTask` is what `withdraw` and `look_inside` share: walk to within 2 blocks, look at the container, then (when it
is about to open it, since a position far away is not loaded) ask what the world says, by the one rule for every record of a
container (`SupplyChests.stand(level, memory, pos)`, the same one block safety and the snapshot use): the owner's word and the
body's note of what is inside are each of a block, and that block has to stand at the position (both halves of a pair). So a
container the body placed and has been inside is its own only while it stands as it was, not only a named one. Another
block there drops the record (`SupplyChests.forget`: the word, the note and the claim on the spot) and the task fails
`TargetGone`; a position that is not loaded is not decided (`Unreachable`, and the record stays: the executor goes there);
then block safety is asked, and the task uses the block so that the container's own rules decide whether it opens. The menu
is closed on every exit, which empties the cursor.

`withdraw` (`WithdrawTask`): leaf params `container`, `dimension`, `count`; the item and the absolute count the body should
hold afterwards are the leaf goal's. It moves items with slot clicks on the menu (a shift-click for a whole stack, one
right-click per item for part of one: lift the stack, set the items down, put the rest back). A slot that does not take the
rest back (a furnace's result slot: a named furnace can be withdrawn from) leaves it on the cursor, which the task never
leaves: it goes to the inventory as closing the menu would send it (`MenuHand.putBack`), and to the ground only when the
inventory is full too; the count a take reports is what the inventory gained, so it can be more than the leaf asked for.
Before it takes anything it counts the item in the container's slots: fewer than the leaf needs is `MissingInput` with
nothing taken. Whatever it saw is noted in `BotMemory` (before the failure, and after a
take), so the replan counts the truth. `look_inside` (`LookTask`): leaf params `container`, `dimension`; it notes what is in
the menu and closes it, and moves nothing.

- Failures: `Unreachable` (no way there, the position is not loaded, another dimension, the menu kept closing; and for
  `look_inside` a container that will not open), `NoSource` (a `withdraw` whose container is there and may be used but
  will not open: blocked above, locked; it names the leaf's source, which the executor rules out, so the next plan gathers
  or crafts instead), `TargetGone` (another block stands where the owner named one, or no container does: the owner's word
  and the note of it are dropped, nothing is opened), `Protected` (block safety, the rule in the failure), `MissingInput`
  (holds fewer; takes none), `NoProgress` (no room in the inventory; what fitted was taken).
- A goal that already holds when a `withdraw` starts (a restart after part of it was taken) completes without opening
  anything.
- `deposit` asks what stands at the position before it opens anything, by the one rule for every record of a container
  (`SupplyChests.stand(level, memory, pos)`, as `ContainerTask` above): another block where the container the goal was
  judged against was recorded drops what the body holds of the position (the word, the note, the claim on the spot:
  `SupplyChests.forget`) and fails `TargetGone`, and a position that is not loaded fails `Unreachable` and drops nothing, so
  the executor goes there again. So nothing goes into a block on the strength of a count that was made for another one
  (`MoveAndMineGameTests.depositIntoAContainerThatIsAnotherBlockNowFailsTargetGone`). The goal itself names a position,
  not a block: with the stale record gone, the retry and the next plan judge it afresh against what stands there now,
  and when that is a container the body may use, it is looked into and filled.
- `deposit` asks block safety before it opens the container too (`DepositTask`, "Block safety"): the stockpile goal's target
  is the owner's word for that goal, which reaches the check as an `Allowance` over exactly that container (both halves)
  open for the one question (`ContainerAccess.askAsGoalsWord`, which the look a stockpile plan makes of its target uses
  too). It lifts the container rule, never a claim, a bed or a job site: a barrel the body did not place is stocked once the
  owner has named it with `use chest at`. When the items are in it notes what the container holds, so what it put in is
  remembered and the goal can be judged.
- `deposit` closes any menu that is open before it uses the container, and works only in the menu its own use-click opened:
  a menu somebody left open (a container used before the step, the body's own inventory-menu state) is not the leaf's
  container's, and a use that opens no menu must not leave the task moving items in one. The menu's kind is checked, and
  where the game serves a container's menu slots from its block entity, that the menu is served from the block at the
  position (a double chest from the compound of its halves). What a click moved is counted by the fall in the body's
  carried count of the item (as a withdraw counts a take), not by what the click was meant to move, so a container that
  takes only part of a stack is credited with that part alone; a container with no room takes what it can and the task
  completes, and what did not fit stays on the body with nothing on the cursor or the floor
  (`MoveAndMineGameTests.depositDoesNotUseAMenuThatWasOpenBeforeTheContainer`,
  `depositIntoAFullChestMovesOnlyWhatFits`).
- `SupplyChestGameTests`: a named chest supplies a craft, after the body has looked inside it; nothing is known of a chest
  before; what it holds is taken and the rest gathered; a double chest is one inventory, also when a lone chest joined by a
  second half is named again; a chest emptied after the plan is not counted again; a chest that will not open is left out of
  the plan and the goal gathers instead; a chest replaced by another block before the body arrives is not opened; a chest
  nobody named, or taken back, is never opened (and stands within the body's sight, so it is the owner's word that keeps the
  body out, not distance); only the owner or the console can name one; a named chest is usable inside the protected radius
  and never breakable; another block where a named chest stood is refused by block safety too; a chest the body placed and
  was inside, replaced by another container, is not opened and is forgotten; a stockpile goal fills its own chest (one the
  body has not been inside: it looks first) and is refused by a claim; a stockpile goal naming the other half of a double
  chest than the one noted fills the one inventory; what the world says of a named container (stands, another block, not
  loaded); a container named and never opened is listed as take-able only while the block named stands, and a position that
  is not loaded decides nothing against it.
- `WithdrawGameTests` (in `game.task`, as it drives `WithdrawTask`'s clicks on a menu the test opened): a partial take
  from a furnace's result slot leaves the cursor empty, loses nothing, and counts what the inventory gained, with the
  ground getting only what a full inventory cannot hold.

## `equip`

Takes an `Equip` goal and puts each slot's item in place through `player.inventoryMenu`, one slot per tick. Armor and the
off hand: shift-click the old piece out, pick up the wanted item, right-click it into the slot (one item of a stack), put
the cursor back. Main hand: select the hotbar slot that holds it, or swap it in with a `SWAP` click.

The plan behind the goal counts a slot as one unit to hold (`SolverPlanner.equipPlan`, which asks for one of the item per
slot and reserves it against the other slots' asks): a slot that already holds the wanted item is asked for nothing, and two
slots naming the same item (planks in the main hand and in the off hand) is an ask for two planks, relied on twice over and
never for one plank held in both.

## Not done here

- A body that dies with a table standing (or is cancelled mid-craft) takes it up after the goal ends only when it is
  still within reach; a table out of reach stays in `BotMemory` and is picked up by the next craft that finds it. A
  `retrieve`-style task for the navigation work to walk back to it does not exist.
- Nothing makes the planner or the executor aware of where a table fits; `NoPlace` is the executor's answer to that (see "Why
  `NoPlace` gives up at once" above), not a way for the planner to avoid the place.
- `LanguageLayer.explain` is not called: results are worded by `BotSpeech`.
- No reflex source is registered; `ReflexSources.set` is where the reflex layer plugs in.
