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
   paragraph, the blueprint folder's names, the game's enchantment ids). A layer that already answered is applied at once; one that
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
for its air to come back" (that wait is part of the mine command, not a walk of the navigator's own: the navigator
holds the body afloat with jump until the air is full again, one game-time deadline bounding the whole wait, and gives
the wet targets up when it passes), "waiting to look again", "exploring for oak_log" and how many blocks it
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
returns its own `Failed`** (the executor does not abort a task that failed itself; `StationTask.stop` does this).
`waiting()` is true while it waits on the game (a break, a pickup) so the stuck watchdog leaves it alone; `checkpoint()`
returns hints for where to look after a restart, never facts.

Registered here: `craft`, `smelt`, `stonecut`, `smith` and `equip`. `navigate`, `reach`, `mine`, `fish`, `kill`,
`harvest`, `recover_drops`, `deposit`, `withdraw`, `look_inside` and `build` are registered by the navigation work
(`TaskFeature`), not here.

What the navigation tasks need from this runtime:

- `BotNavigator.of(body)` is ticked by whoever owns it: a `navigate`/`mine` task calls into it from its own `onTick`, and
  must `stop()` it in `onAbort`/`onPause` (the runtime only resets `BodyInput`, it does not stop the navigator).
- The runtime resets `body.input()` on the tick after the last active task ends, so a task need not clear the input of a
  task that ended; it still clears its own on pause and abort.
- `BlockSafety.of(server).mayBreak/ noteBotPlaced` and `BotMemory.notePlaced/forgetPlaced` are the contract for any block
  the bot places or breaks (see `StationTask.place`).
- The snapshot reads `BotMemory` for stations and containers; a `deposit` task notes what it saw (`ContainerAccess.note`), as `withdraw` and `look_inside` do.
- `Failure.Died` is reported through `onDeath`; a task whose body died does not also fail with `Died`.

## `craft`

Leaf params: `recipe` (the game recipe id), `times`, `station` (empty or `minecraft:crafting_table`), optional `layout`.

Everything about the station block — a table found standing or put down, walked to, opened, broken and taken up again,
with the walks, the wide search out of a protected area and the interruptions — is `game.task.StationTask`, the base
class `CraftTask`, `SmeltTask`, `StonecutTask` and `SmithTask` share; `CraftTask` itself is the crafting grid. What the
base class asks of its subclass: the station's item and block and what its failures call it, whether a standing one may
be used where it is (any crafting table, but only a furnace, stonecutter or smithing table the body's memory says it
placed, for those are containers or job sites block safety keeps a stranger out of) and whether it may be taken up now
(a furnace or a stonecutter only with a tool that makes it drop), whether the station's menu is open, whether the work
is done, what must still be carried before anything is put down, and the work itself, which runs with the menu open.

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
  `StationTask.WALKS_ALLOWED` (4) walks; a walk that fails (`NO_PATH`, `STUCK`, or a navigator that stops three times) makes
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
  body goes to a table item within `StationTask.DROP_SEARCH_RADIUS` (12) blocks of where the table stood: through
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
load and whenever a goal ends (`tidyArmed`): the nearest crafting table, furnace, stonecutter or smithing table within
reach that `BotMemory` says this body placed — a furnace or a stonecutter while the body carries a tool that makes it
drop, a furnace under the smelt task's rule below, whether or not it holds anything, for breaking one drops what it
holds beside it and the tidy task picks that up too (`DropPickup`, the one walk to dropped items, over any item within
`StationTask.SPILL_RADIUS` (3) blocks of where the furnace stood). Found, it runs
`CraftTask.takeUpStation(level, pos)` (the public face of `StationTask.takeUp`, for the base class is package-private:
the station's own break and pickup, working nothing) before the executor is
ticked again, so the next plan sees the station in the inventory. A failure there is logged, naming the block that
stood there, not a goal's failure; the station stays, remembered. A station the rules do not let the body break stays
standing and remembered too (the tidy task completes, and looks again at the next goal's end). Covered by
`BotRuntimeGameTests.aTableLeftStandingByALogoutIsTakenUp`.

## `smelt`

Leaf params: the craft leaf's with an empty `layout` — `recipe` (a variant id: the game recipe's id, `#` and the fuel
class its run is sized for), `times` (runs) and `station` (`minecraft:furnace`, `minecraft:blast_furnace` or
`minecraft:smoker`).

- The recipe is found again by id and must be an `AbstractCookingRecipe` of the station's kind; the fuel class and the
  run's counts are read back from `CookingRecipeSource` (`variantOf`, `fuelOf`, `runOf`), never worked out again. One
  run takes `p` of the ingredient and `q` of the fuel and makes `p` times the recipe's result; the leaf's goal is the
  absolute count after the step, so a task started again makes only what is still missing, in whole runs — and never
  more than the leaf's `times` runs on either branch: the count worked to is what is carried plus the clamped runs'
  results, as the craft task works to its clamped `remaining`, so a goal above what the leaf budgeted is the planner's
  to top up rather than this task's to spend another node's material on.
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
  do). A body carried out of reach while the work runs closes the menu itself and is walked back. An end that is not
  the completion — an abort, a failure, the kind's budget — leaves the body's ingredient, fuel and result inside a
  block nothing else would open, so it is said: the log names what is inside and where the furnace stands, a failure
  the work itself made carries the position in its detail, and the tidy step is what gets the items back (it takes a
  furnace up whether or not it holds anything; see "A station left standing").
- Failures: the station failures the base class already has; `TargetGone` for a recipe id the game does not have, one
  that is no cooking recipe of the station's kind, or one whose fuel class the game no longer knows; `MissingInput`
  when the ingredient, the fuel or the furnace is not carried; `NoRoom`; and `NoProgress` when the furnace holds the
  ingredient and the fuel and has not burned for `SmeltTask.NO_PROGRESS_TICKS` (100) ticks — counted only over ticks in
  which nothing happened, for the count starts again on any tick in which the task clicked or the furnace's own slots
  changed, so a slow feed or an emptying slot is not a stall — vanilla lights a furnace
  within a tick or two of both slots being filled, so that is "something else is in a slot, or the game refuses the
  recipe".
- Time: a stack takes 12800 ticks in a furnace and the executor's default budget for a task is 12000, so the `smelt`
  kind has its own budget where the runtime builds its `ExecutorConfig` (`BotRuntime.SMELT_BUDGET_TICKS`, 72000 — one
  hour of game time, five and a half stacks, still bounding a smelt that has stopped for good).
- `SmeltGameTests`: the two-run sticks variant ends with 2 ingots and nothing lost; the same with the only pickaxe in
  the main storage, off the hotbar, still takes the furnace up; the same without a pickaxe leaves
  the furnace standing and remembered; a furnace somebody else put there is not opened, and the body puts its own
  down; no fuel is a `MissingInput` naming a fuel item with nothing put down; a pause and a resume in the middle of
  the burn still ends with the 2 ingots; a cancel in the middle of the burn and the tidy step after it end with the
  furnace, all the iron and nothing on the floor; and through the runtime a body asked to have 1 iron ingot ends
  satisfied.

## `stonecut`

Leaf params: the craft leaf's with an empty `layout` — `recipe` (the game recipe's id), `times` (cuts) and `station`
(`minecraft:stonecutter`). The station and everything about it is `StationTask`'s; `StonecutTask` is the work in the
open `StonecutterMenu` (slot 0 the input, slot 1 the result).

- A stonecutter offers several results for one input, so filling the slot is not the work: the menu lists the recipes
  for what the slot holds (the order `RecipeManager.getRecipesFor` gives), the task finds the leaf's recipe among them
  and presses it — `clickMenuButton`, the call a client's click on the button row ends in, for a stonecutter selection
  is a button, not a slot click. A menu that does not list the leaf's recipe, or whose result slot then offers another
  item, is `Internal` naming the recipe.
- One cut is: the first alternative carried (the craft task's rule without a layout) goes into the input slot as one
  right-clicked item, the recipe is pressed, the result slot must offer the recipe's result, and it is taken into the
  inventory whole or not at all with room asked for before anything is put in. Each cut consumes one input and gives
  the recipe's result count (2 for slabs), and the leaf's goal is the absolute count after the step, so a task started
  again makes only what is still missing.
- The input slot never holds more than one cut's worth. On every exit — done, a failure, a pause, an abort — the
  menu's close hands what is still in it back to the inventory (`AbstractContainerMenu.removed`), which is the base
  class's way of emptying what a menu holds; the station is taken up again when the task placed it, as the base class
  does.
- A stonecutter is a mason's job site, which block safety keeps the body out of when it did not place it: a standing
  one is used only when the body's memory says the body placed it, and otherwise the carried one is put down (the
  furnace's rule). A stonecutter is stone, so it is taken up only with a tool that makes it drop — one broken by hand
  drops nothing — and without one it is left standing, remembered as the body's, and the task completes all the same.
- Failures: `TargetGone` for a recipe id the game does not have or one that is no stonecutting recipe; `MissingInput`
  when the ingredient or the stonecutter is not carried; `NoRoom` before anything is put in; `Internal` as above; the
  station failures are the base class's. The kind runs on the craft task's budget (the executor's default), for a cut
  is as quick as a craft.
- `StationGameTests`: three stone make six slabs and the stonecutter comes back; a missing input is a `MissingInput`
  that puts nothing down.

## `smith`

Leaf params: the craft leaf's with an empty `layout` — `recipe` (the game recipe's id), `times` (upgrades) and
`station` (`minecraft:smithing_table`). The station and everything about it is `StationTask`'s; `SmithTask` is the work
in the open `SmithingMenu` (slot 0 template, 1 base, 2 addition, 3 result).

- A smithing table needs no recipe chosen: with the three inputs in their slots the result slot shows the first
  matching recipe's result, and taking it uses up one of each. The task puts the first alternative carried into each
  slot in menu order (the craft task's rule without a layout; the alternatives come from the recipe's own predicates
  through `StationRecipeSource.acceptedItems`, the same list the book was built with), then checks the result slot
  offers the leaf's recipe's result — the menu matches against every smithing recipe there is, so a mod's recipe that
  matches the same inputs would show first — and takes it whole or not at all with room asked for before anything is
  put in.
- The three slots never hold more than one upgrade's worth, and every exit hands what is still in them back to the
  inventory through the menu's close, as the stonecut task's does. A smithing table is wood, so it is always taken up
  again; the leaf's goal is the absolute count after the step, so a task started again makes only what is missing.
- A smithing table is a toolsmith's job site, with the furnace's rule for using a standing one (only the body's own,
  by memory). Failures: `TargetGone` for a recipe id the game does not have, one that is no smithing recipe or one of
  the armour-trim kind; `MissingInput` naming the first input (in menu order) or the table not carried; `NoRoom`
  before anything is put in; `Internal` when the result slot offers another item; the station failures are the base
  class's. The kind runs on the craft task's budget.
- `StationGameTests`: a template, a diamond pickaxe and a netherite ingot become a netherite pickaxe, the inputs gone
  and the table back in the inventory.

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
(`MoveAndMineGameTests.aBodyDespawnedWhileExploringDoesNotFailNoSource`). The rule is one method,
`BodyTask.exploreFailure`, and every task that explores for a source asks it: `mine`, `fish`, `kill` and `harvest`.

What makes an explore move: the engine heads for the centre of the nearest chunk that is not marked as looked over, and
`BotNavigator` does the marking (the engine this was ported from left it to a tracker outside it, which the port did not
bring; until 2026-11-27 nothing marked anything, so every explore walked to the centre of the chunk it began in and stood
there). While an explore runs, the body's chunk and its eight neighbours are marked each time it steps into a new chunk;
the marks last ten minutes of game time (`SEEN_CHUNK_TICKS`) and are written into the engine afresh when an explore
begins, so a later search comes back over old ground. A walk now passes things a look only at the end of a round would
miss: the kill task looks for a quarry every second while it walks, and the harvest task ends a round after 24 blocks.

Blocks the body would have to stand in or under water to break are held back while its air is not full, and the wait
for that air is part of the mine command: the navigator keeps the command's owner (a pause, an abort, a release of the
task stops the body), and holds jump through the body's input — what a player does to rise in a water column and float
at the surface, forward toward the place to breathe when it is not straight above — until the air is full again, when
the engine's mine process restarts under the same command. One game-time deadline bounds the whole wait (150 ticks:
filling the 300 air at 4 a tick with the eyes out is 75, the rest is room to get the head out); when it passes with
the air not full, or the head stays under for 60 ticks, or there is nowhere to breathe within the four blocks up and
around the navigator searches — no way up — the wet targets are set aside like any block that cannot be got at, so
the step ends `NoSource` naming the source when nothing else is left, never a failure that would be retried into the
same dive. While the navigator waits, the task's own no-gain clock stands still
(`MoveAndMineGameTests.aBodyInTheWaterGetsItsHeadOutWithoutLeavingTheMineCommand`,
`aBodyWithNoWayUpGivesUpTheWetTargets`, `abortingAMineWhileItBringsTheBodyUpForAirStopsTheBody`).

### Danger zones (issue 29)

The body remembers where it was driven off or killed: `BotMemory` keeps danger zones — dimension, centre (where the
threats stood, or where the body fell), radius, the game time it lapses and a short reason. One is written by the reflex
layer when a hold runs from a shooter the body cannot answer or from a mob far stronger than a player, and when a body
dies to a mob (radius: the threats' follow range plus a margin; lapses after 12000 ticks, half a Minecraft day); the
executor writes one where the body stands when the reflex layer has held it past the preemption limit (reason "held too
long by mobs here", through `ExecutorHost.noteDangerZone`). Zones are saved with the brain and dropped when they lapse;
overlapping ones are kept as they are. While one lives:

- The navigator prices its cells through the engine's own `Avoidance` (a coefficient of 4 on every cell of the sphere),
  so a path goes round a zone when there is a way round and through it when there is not — a cost, never a wall: a goal
  inside a zone that the owner asked for by position is still walked to. Exploring is priced the same way, so a search
  passes over a zone's chunks while others are left.
- The mine command's target selection withholds the blocks inside one, as it withholds wet ones, but without the
  fallback: when only zoned blocks are known the command ends with nothing to mine and the task explores. Nothing is set
  aside for it, so the blocks come back the moment the zone lapses. A step that ends for want of a source because of a
  zone says so in its failure's detail (how many blocks stood inside one).
- The `kill`, `harvest` and `fish` tasks skip candidates inside one the same way (a quarry, a grown block, a place to
  stand and cast), and their `NoSource` detail says how many were left for it.
- The recover node skips a death position inside one, with a log line, and completes as it does for a position it cannot
  reach.
- `status()` (where the reflex line is) lists the live ones: centre, radius, seconds left, reason.
- The reflex layer itself is not held back by them: a retreat may cross one, and a fight it must have is had.

## `recover_drops`

The node the executor puts in front of the new tree after a death (`RecoverDropsTask`): walk to each place the death's items
fell and pick up what still lies there. Drops move — items roll, fall and float — so at each recorded position the task looks
for item entities within a named radius (4 blocks) horizontally and down to a named depth (8 blocks) below it, and picks
each up through `DropPickup`, the one walk to dropped items (see `kill`); a position with nothing within that radius is done
at once. The walk to the record comes first, however far: what lies there cannot be seen before its chunks are loaded
around the body. An item the body cannot get at is given up, and so is one that lies in reach and is not taken; a drop that
has been picked up or despawned is never waited for. A position inside a live danger zone is skipped with a log line
(issue 29: the zone is what the body was killed there for, and it is not walked back into for a dropped stack), exactly as
one the body cannot reach is. The task never fails the goal: the node is best-effort, always completing.

## `build`

Leaf params: `blueprint` (its name), `dimension`, `anchor` (`x,y,z`, the min corner of the blueprint's box). The leaf's
goal is the `Goal.Build` itself, so the executor judges it on the snapshot's structure report of it.

- **Where blueprints come from** (`game.build.Blueprints`, registered by `BuildFeature.init()` from `GameBootstrap`): a
  name is looked up first as `<config dir>/megablockbot/blueprints/<name>.nbt` (compressed structure-template NBT, read
  with the game's own `StructureTemplate.load` over the registries, and its blocks then from the lists of the template's
  own save form: the palettes are private and the public block filter answers for one named block only), and then, when
  the name has a colon and parses as a resource location, as one of the server's structure templates
  (`build minecraft:igloo/top at ...`; the grammar and the model layer carry an id-shaped name as typed, since no list
  of the server's templates is to hand where words are read — an id no template answers to ends "not known"). A file
  name is a file name only (no path separator, `..` or colon); at most 4096 blocks and 48 along a side, or the blueprint
  is refused with a message saying so. A file that is not a structure this brief can build from — valid NBT with no
  palette or no block, a block outside the size the file declares — is refused with a logged reason, never loaded as an
  empty blueprint that would read "already built". This brief builds plain blocks: a blueprint holding a block no item
  places, a block entity, the second block of a two-block thing (the upper half of a door, the head of a bed), or a
  state that is more than one item's worth of the block (a double slab, snow of more than one layer, candles, sea
  pickles or turtle eggs of more than one — one item can never finish them) is refused naming the block; which way a
  block faces is left free. A blueprint keeps the box it was saved from: its size is the template's declared one, air
  margin included, so a box saved with an empty margin builds back where the owner pointed. Loaded blueprints are cached
  per server and dropped on a datapack reload, on stop, and when the file's modification time changes.
  `/aiplayer blueprint save <name> <from> <to>` (permission level 2, same name and size rules) saves the blocks of that
  box of the caller's world without entities, with the game's own `fillFromWorld` and `save`, and refuses a box the
  loader would refuse — nothing to build, or one of those unbuildable blocks — naming the block and where it stands,
  writing no file; `/aiplayer blueprint list` lists the folder.
- **The report** (`SnapshotCollector.addGoalFacts`): a snapshot taken for a goal that is a `Goal.Build` (the one being
  planned or judged), and for the first build goal queued behind it, carries a `StructureReport` under
  `blueprint@anchor` — `required` the blueprint's positions, `matched` how many hold the blueprint's block already (the
  block, not every state property, so a log placed along another axis counts), and `missing` the items that place the
  still-wrong blocks. `known` is false when any position's chunk is not loaded or the goal's dimension is not the body's;
  an unknown blueprint name gives no report at all. Only the first queued build goal is read, not every one: each report
  stats the file and walks the footprint, and an owner who queues many build goals at nearby sites must not multiply
  that over the whole queue on every snapshot.
- **The plan** (`SolverPlanner.buildPlan`): no report is unplannable (the blueprint's name is not known); a report that
  is not known plans one `navigate` leaf to the anchor (radius 8: near enough that the footprint's chunks load), as an
  unseen container is looked into first; a known report plans an item plan per missing item and count the body does not
  hold (relied on, not drawn, in a fixed order — the containers the body may take from are asked for them like any
  other item the plan relies on) and then the one `build` leaf. Nothing missing plans nothing. When the missing blocks
  need more inventory slots at once than the main inventory holds (27, stacks of 64, the hotbar left for tools), the
  plan is unplannable saying the count and the limit — staged builds are not built yet, and the gather would only end
  `NoRoom` when the last slot filled. The bound is taken on the report of each plan, so a structure half standing
  (fewer blocks missing) still finishes.
- **The task** (`BuildTask`, kind `build`, registered in `TaskFeature`, budget 36000 ticks — see `BotRuntime`): arms the
  per-body break filter as the mine task does and starts the navigator's `build`, which hands the engine's
  `BuilderProcess` a static schematic of the blueprint. The engine's settings for the command: `acceptableThrowawayItems`
  emptied (so the pathing never bridges with dirt — the build places only blocks the blueprint names and only from the
  inventory), `allowInventory` on (the hotbar is filled from the main inventory), no layers, no repeats, no orientation
  flips, `buildIgnoreExisting` off, and the break filter narrowed to the blueprint's box for as long as the command runs
  (the filter reads the box live, so a command that ends opens the world back up). Every command the task gives — the
  start and every resume — starts its watches again, as the mine task's do: the walk back after a pause is measured as
  its own approach, not held against the build as no progress.
- **When the builder lacks a block**, upstream pauses and says what is missing (`BuilderProcess`:
  "Missing materials ... Unable to do it. Pausing."), which would wait for ever: the navigator fails the command the
  moment it sees the pause, naming the block the body is short of (`MISSING_BLOCKS`, the count read from the world and
  the inventory). The task makes that `MissingInput` naming the item and the count short — the executor replans, and the
  planner gets what is missing from the next report. A build that cannot go on another way fails `BUILD_STUCK`, and an
  engine error ends the leaf `Internal` with the exception. The navigator's state, failure text, kind and cause are read
  before the task releases it (release stops and clears the navigator), so none of that is lost.
- **A block the rules protect** does not pause the builder (it keeps trying the same spot), so the task watches itself:
  no placement, break, or step toward the anchor for 400 ticks ends the leaf. The end is `Protected` naming the position
  when a wrong, non-air position inside the blueprint may not be broken by this body's rules; `Unreachable` for the
  navigator's other failures; `NoRoom` never applies (nothing is picked up). It pauses, resumes and aborts like the mine
  task — the builder is stopped and a resumed task starts the build again, and the builder skips what stands.
- **A build never stands still without saying why.** The engine's builder waits without saying so when its goal cannot
  be acted on (its tick assembles the same goal again): a position with nothing to place against and no scaffolding
  carried (air all round — a block can only be placed against a block, and this command's throwaway list is empty), a
  goal the body already stands in (the builder will not place a block into the body), or a place it can find no path to.
  A build that holds every block it needs and has neither placed, broken nor moved for 100 ticks ends with that reason,
  read from the world by the task, instead of waiting out the 400-tick watch; the same reason is what a stalled build's
  failure says wherever it ends.
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
## `kill`

Leaf params: `item`, `count` (how many more; the goal's `HaveItem` count is absolute and wins), `entity` and
`playerKillOnly`. A drop that needs a player kill is satisfied by this body's own kill: the body is a player, so the flag
asks nothing of the task. The leaf's source id (`kill:minecraft:cow`) rides along in `NoSource` so a replan excludes exactly
that source.

- Targets are living entities of the leaf's type within `KillTask.SEARCH_RADIUS` (24) blocks, loaded, nearest first. Never a
  mob with a custom name, a tamed or leashed animal, or a baby: those belong to someone or drop nothing. The scan does not ask
  for a line of sight — a quarry behind a hedge is a quarry still; the navigator says how to get there.
- The body goes to the target through `BotNavigator.follow` (it follows a target that moves) and fights when within reach of
  an attack, as a player does: the best weapon the body carries brought to the main hand by an inventory swap — the same
  choice the reflex layer makes (`game.task.Weapons`, moved out of `game.reflex.Hands` for both), fists when none — facing the
  mob, a swing through `Player.attack` only once the attack strength has recovered (attack before swing: a swing first resets
  the cooldown and makes every hit a weak one). No shield, no retreat: defence is the reflex layer's, and a hold pauses the
  task like any task. `waiting()` is true while it fights, so the stuck watchdog does not cut a kill short.
- After a kill it picks up what dropped (`DropPickup`, shared with `harvest`): the drops of the leaf's item within
  `LOOT_RADIUS` blocks of where the mob fell. The death is asked about before whether the quarry may still be fought: a
  mob that has just died is no longer a quarry, and the other order sent the body after the next one with the leather
  left lying. Other drops (beef) come along by standing there.
- `DropPickup` is the one walk to dropped items. The game takes an item once it lies in the body's pick-up box (the
  body's box, a block wider each way, half a block taller) and its delay has run, so the helper gets that box onto the
  nearest drop and stands. The nearest drop is looked for anew each tick, because a fresh drop hops, rolls off its block
  and floats: one that leaves the box is walked after, it does not end the pick-up. Further than `WALK` (3.5, flat) the
  navigator brings the body to within `APPROACH` (2) blocks of the drop's cell — two, since nothing a body can stand in
  is within one block of a bush on a raised block — then the body's own steps (`STEPS_ALLOWED`, 40), which break
  nothing, then the drop's cell exactly; a navigator command another owner is running is waited for, not walked over.
  A drop the navigator cannot reach, or one in the box and not taken for `WAIT_TICKS` (60), is given up; the whole
  pick-up has `BUDGET_TICKS` (400) counted from the last drop that came in, and a hard bound that nothing starts again
  (`HARD_BOUND_TICKS`, 2000), so a drop the water carries off — or one that bobs across the edge of the search box,
  starting the budget anew each time it leaves — cannot hold the task. However the pick-up ends, the body's movement
  input is stood still and its look let go.
- A target the body cannot get at is set aside, as the mine task sets a block aside, and another is taken. A follow
  command never fails for want of a path (it keeps trying), so the task watches the chase itself: a quarry neither
  closer nor within reach for `KillTask.CHASE_STALL_TICKS` ticks (the navigator's own stall bound for one block) is set
  aside. The set is kept for the task's life and in its checkpoint. Within `KillTask.CLOSE_ON` (4) blocks of the quarry
  the body closes on it by its own movement input (running: a hurt cow flees about as fast as a player walks) rather
  than through the navigator, because a follow command holds the body at its follow radius -- where the engine is
  content to stand, which is not the same as the reach an attack needs. When no target is known the task explores for a
  bounded number of rounds (`KillTask.Search`), then fails `NoSource` with the leaf's item and source id, so the executor
  excludes the source and the next plan gathers the item another way. A round is a look, a stretch of exploring and
  another look; an exploring command that fails at once ends the round and never the search, since one tick is not a
  search and a mob may walk into range a moment later, and an exploring command that is still working when the round
  ends is stopped with it — the hunt gives its follow only when nothing of the task's is running, and an explore left
  running would walk the body on under it while the quarry the next look finds is set aside unapproached. No room for
  the item before it starts or after a kill's drops are gathered is `NoRoom`, as in the mine task.
- `progress()`: looking for a cow, going to one at x y z, fighting one, picking up what fell — and how many of the item it
  holds.

## `harvest`

Leaf params: `item`, `count`, `block`. The leaf's source id (`harvest:minecraft:wheat`) rides along in `NoSource`.

- **Grown** is decided from the block's own state properties in one place (`HarvestTask.grown`): a berry bush or cave vine is
  grown when it carries its berries (the vine's `berries` property; a bush from age 2, where its own loot table's two pools
  start); sugar cane, cactus, bamboo and kelp when another of the plant stands below — so the lowest block stays and the plant
  regrows; a crop with an age at its last age. A kind with no rule of its own is read as grown in any non-default state, and
  the kind is named in the report (the `NoSource` detail says which went that way).
- A block the break rules would not let the body break is never taken (the per-body rules, as the mine task's scan asks
  them; the same gate for what is used rather than broken). A forbidden grown block is skipped, and when only forbidden ones
  are known the task fails `Protected` naming one.
- The engine's own farm process is not driven: it is a whole farm in one command (every crop kind, replanting open farmland,
  bonemeal, and a left-click on everything, which a berry bush must not be), while this leaf is one block kind whose taking
  must ask the rules per target and go through the body's inputs. The task scans the loaded chunks itself (as the mine task's
  protected scan does), walks with `BotNavigator.goTo`, breaks what a player breaks (crops, cane) through the body's own game
  mode, and uses what a player uses without breaking (a berry bush, a cave vine) with an empty hand where there is one.
- A crop that was broken is replanted when the body holds the plant's item (the seed, the carrot — read from the block as the
  resolver reads it: the block's own item, or what pick-block gives) and the block below is farmland: a use-item click puts
  the same plant back. The replant is finished before the step can end, so a harvest that took the crop owes the field its
  seed back even when the count is reached; once the replant is done the count is asked first, so a harvest that holds
  its goal completes instead of scanning again — a scan that could only fail, the one ripe block there was being taken.
  Not replanting is not a failure.
- Drops are picked up as in `kill` (`DropPickup`). What a picked bush drops lies in the bush's own cell, the engine
  counts a berry bush as a block never to be stood in, and a goal of that cell would have it break the bush to stand
  there: so the navigator is never asked into the drop's cell first, and its break rules refuse that block for as long
  as the task owes it. When the owe ends, the standard rules go back only if the navigator still holds the filter the
  preserve installed: a filter someone else put in in the meantime (the reflex layer's "break nothing", on taking the
  body over before the task was paused) is not this task's to replace.
- A harvest paused while it works a block resumes at that block's pick-up: what the taking left — drops in its cell, a
  replant owed to the spot — is kept and paid before the search goes on, and a use-harvested block still standing is
  preserved again for the walk back to its drops. A block the task was only going to is let go and looked for again.
- Blocks it cannot get at are set aside (kept for the task's life and its checkpoint); when
  no grown block is known it explores for a bounded number of rounds (`HarvestTask.Search`), then fails `NoSource` with the
  leaf's item and source id — the planner has no wait-for-it-to-grow yet, so an unripe field is a source to exclude and
  replan around, not something to stand and wait by. No room is `NoRoom`, as in the mine task.
- `progress()` as in `kill`, against the block it heads for.

## `trade`

Leaf params: `item`, `count` (the trades: one item each), `profession`, `level`, `cost` (what one trade charges, as
`item=count,item=count` in id order — the charge of an offer the body has noted, where it has noted one), `chance` and
`jobSite` (the item of the block that gives a villager the profession, or empty). The plan for the payment comes before
the leaf, and so does the plan for the job-site item: held for the whole plan, once per profession, and never drawn, as
a fishing rod is held. A component-checked `HaveItem` (a book of mending) is planned the same way when a trade's result
satisfies the check — the source's own sampled enchantments of an enchanted book, or an offer the body has noted in a
real window — with the check riding on the leaf's goal (`SolverPlanner.checkedPlan`), the payment the charge of the
cheapest noted offer that satisfies it (a note of the item that fails the check is another book's price), and a strategy
that undercuts the trade still winning as it always did. The leaf's source id rides along in `NoSource`.

- **`TradeTask`** finds a villager (adults within `SEARCH_RADIUS` = 48 blocks, loaded; of the leaf's profession first,
  nearest first), walks to it, opens its trade window with a use-click (the villager's own rules decide whether one
  opens; a villager whose window will not open is skipped for a while, never failed) and **notes its offers in
  `BotMemory`**, saved with the brain — the next plan prices and plans the payment by a noted charge in place of the
  index's sample, because a real villager's price differs (an enchanted book costs 5 to 64 emeralds). Offers of a
  villager that is gone, or noted longer ago than `SnapshotCollector.OFFER_AGE_TICKS` (three in-game days), are
  dropped from the snapshot.
- **Trading**: at a villager with an offer whose result satisfies the leaf's goal (the goal's component check applied
  to the result stack through the same facts the snapshot reads — `SnapshotCollector.factsOf`), the task selects the
  offer the way the client's select-trade packet does (`MerchantMenu.setSelectionHint` then `tryMoveItems`, which moves
  the payment from the inventory into the payment slots) and takes the result out of the result slot (menu slot 2) by a
  click through `MenuHand`, once per trade, until the inventory holds the goal's count. The price is the one the
  window asks now (`MerchantOffer.getCostA`: the base price with the villager's demand and any discount), which is
  what is noted and what the body is checked against. A villager whose charge the body cannot pay is passed by while
  another is still to be looked at; when none is left the leaf ends `MissingInput` with the cheapest charge seen
  (the first missing item and its count), and no roll is begun for an item somebody already offers. An offer that
  is out of stock ends it `NoProgress` saying so; no room for the result is the no-room failure.
- **A noted villager**: a leaf whose price the plan took from a noted offer carries `villager` and `at` (see
  docs/plan-inputs.md). The task walks back to that place first (`goToXZ`, over once within 24 blocks) and looks at
  that villager before any other, so the price the plan gathered is the price it is asked. A noted villager that is
  not in the world when its place is loaded is forgotten (`BotMemory.forgetOffers`).
- **Rerolling** (a librarian for a book of mending): when no villager within the radius offers the item and the body
  carries the job-site item, the task puts it down within 3 blocks of an adult villager with no profession (not a
  nitwit), at a place found the way a crafting table's place is found (`TableSites`, asked with the job-site block),
  sneaking, and notes it as the body's (so block safety lets the body take it up again). It waits up to
  `PROFESSION_WAIT_TICKS` (600) ticks for the villager to take the profession — visible on it; the villager scans for a
  job site every 20-39 ticks at first, backing off to 400, so 600 is past the second scan — then opens the window and
  notes the offers. Not the wanted one: the site comes up (broken and picked up again), the profession is waited away,
  and the site goes back down: a new roll, since a villager that has never traded gets new offers each time it takes
  the job. The loop goes straight back to the next roll with the same villager — it never returns to the search for its
  own villager (the villager it is rolling keeps its place in the roll until it trades, is lost, or the roll's own
  bound passes), and a job-site block this body placed that still stands is taken up before a new one is asked for.
  One INFO line per roll says what the window showed, one says the site going down and one its coming up. Bounded by
  `MAX_ROLLS` (200) and the kind's own task budget (72000 ticks: a roll takes about 15 seconds and a book of mending
  about 40 rolls on average); out of rolls is `NoProgress` with the count. Every villager within the radius has been
  looked at, none offers the item and no job-site item is carried (and none of the body's stands): `MissingInput`
  naming the job-site item, without exploring first — the missing thing is an item, not a villager further away.
- Nobody within the radius: in the `ASSISTED` profile the game is asked for the nearest village
  (`findNearestMapStructure` with the village structure tag, within 100 chunks, the locate command's own bound) and
  the body walks there. The call holds the server thread for a second or two, so it is made once for a goal: the
  answer rides in the task's checkpoint, and a village with nobody to trade with is not asked for again. After
  that, and in `STRICT_SURVIVAL` from the start, the task explores for a bounded number of rounds
  (`TradeTask.Search`, six rounds of 100 ticks by default, as the mine and kill tasks take their search bounds — a
  test can ask for a short one through the task's constructor). Then `NoSource` with the leaf's item and source.
  **The profile is a setting**: `/aiplayer profile <name> strict|assisted` (whoever may command the body), saved
  with the brain; the default stays strict survival.
- Other villagers' job sites are never touched (only one the body put down itself is taken up), and a villager is never
  moved, hurt, cured or bred. On every exit the window is closed and the cursor emptied. A job site put down for a
  villager that then showed the offer the goal asks for stays where it is and stays remembered, whether the body
  could pay at once or not: the offer is what the rolls were for, and a villager that has not traded loses it with
  its job site (before 2026-11-27 a body that could not pay took the lectern up and threw the offer away). One put
  down for a villager that never showed the offer is taken up again first, on every exit including the search's own
  failures. A later task never takes up a job site of this body that a villager who has traded works at (the
  villager's own `JOB_SITE` memory says which block that is), nor one this task already failed to take up; a second
  trade of the same profession that needs a roll of its own therefore ends `MissingInput` for the job-site item and
  the next plan brings one.
- `progress()` says what it is doing: looking for a villager, looking at offers, the roll it is on, paying.

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
