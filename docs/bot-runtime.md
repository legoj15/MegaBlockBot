# The bot runtime (`game.bot`, `game.task`)

What turns a goal the owner states into crafting a body does. Design: `AI-PLAYER-DESIGN.md` 3 (3.4 and its
amendments are the contract), 4.2 and 6. Bot node only (1.21.1). Server thread throughout, except the planner thread.

## How a goal flows

1. Words arrive: `/aiplayer goal <name> <text...>` or a chat line from the body's owner that starts with its name
   (`Bot1, make 4 planks`, `Bot1 make 4 planks`). Both go to `BotTalk.interpret`; the other commands (`cancel [all]`,
   `status`, `pause`, `resume`) skip the language layer and go to `BotTalk.apply` as the matching `Intent`. Commands need
   `body.mayCommand(source)`; chat is the owner's voice only (an op's chat line is not a command).
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
`Step i of n: <kind> (<goal>)`, replans, queue; an idle bot repeats its last result.

## `BotRuntime` (one per body, registered with `BotBrains.setFactory` in `BotFeature.init`)

- `tick`: asks `ReflexSources.current()` for this tick's `ReflexHold` (default: none; the reflex layer sets its own),
  ticks the executor, and resets `body.input()` once when the task the executor last created stops being RUNNING or PAUSED.
  A hold a command put on a body that never had a task (`/aiplayer input`) is never touched.
- `progressSignature`: hash of block position and every inventory stack. `profile`: `STRICT_SURVIVAL`.
  `dropsRecoverable`: the death's dimension is loaded and the death is under 5 minutes (6000 ticks) of game time old.
- `onDeath` / `onRespawn` map to the executor's (`Failure.Died` from the `DeathRecord`); `wantsToStayLoaded` is
  `executor.active()`.
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

Registered here: `craft` and `equip`. `navigate`, `reach`, `mine`, `recover_drops` and `deposit` are registered by the
navigation work, not here.

What the navigation tasks need from this runtime:

- `BotNavigator.of(body)` is ticked by whoever owns it: a `navigate`/`mine` task calls into it from its own `onTick`, and
  must `stop()` it in `onAbort`/`onPause` (the runtime only resets `BodyInput`, it does not stop the navigator).
- The runtime resets `body.input()` on the tick after the last active task ends, so a task need not clear the input of a
  task that ended; it still clears its own on pause and abort.
- `BlockSafety.of(server).mayBreak/ noteBotPlaced` and `BotMemory.notePlaced/forgetPlaced` are the contract for any block
  the bot places or breaks (see `CraftTask.placeTable`).
- The snapshot reads `BotMemory` for stations and containers; a `deposit` task must `noteContainer` what it saw.
- `Failure.Died` is reported through `onDeath`; a task whose body died does not also fail with `Died`.

## `craft`

Leaf params: `recipe` (the game recipe id), `times`, `station` (empty or `minecraft:crafting_table`), optional `layout`.

- The recipe is found again in the game by id (`RecipeManager.byKey`), because a shaped recipe's grid positions are not in
  the planner's book. Cells are the recipe's non-blank ingredients in order (= the planner's slot order); a shaped recipe
  keeps its shape at the grid's top left, any other crafting recipe fills cells in order.
- `layout` (`CraftLayouts`): runs `crafts*item,item,...` separated by `;`, one item per slot in slot order, the runs'
  crafts adding up to `times`. Followed exactly when present; a malformed layout, an item the ingredient does not accept,
  or a count that is not `times` fails with `Internal` rather than re-deciding. Without a layout each slot takes the first
  alternative (the ingredient's own item order) the body carries.
- A craft is: pick up a stack with a click, right-click one item into each cell that wants it, put the cursor back, check
  the result slot offers the recipe's result, shift-click the result out, empty the grid. All through
  `AbstractContainerMenu.clicked` (`MenuHand`), one craft every 3 ticks. The crafts still owed are worked out from the leaf's
  goal (an absolute count), so a task started again after a restart makes only what is missing.
- No station: the inventory's 2x2 grid (`player.inventoryMenu`, grid slots 1 to 4). Table: use one within reach
  (`canInteractWithBlock`); otherwise put the table item on a free spot beside the body (`interactBlock`, i.e. reach and
  `mayInteract` then `ServerPlayerGameMode.useItemOn`; spots at the body's feet level, nearest first, each checked with
  `BlockSafety.mayBreak` so the body can pick the table up again), open it with a use, craft, close, and break it the way
  a client does (with the hotbar slot that breaks it fastest, else one that cannot wear out, so the pickaxe just made keeps its
  durability; `START_DESTROY_BLOCK`, per-tick progress, `STOP_DESTROY_BLOCK` at 1.0) and wait up to 100 ticks for the
  item. The item hops about and now and then rests outside the body's pickup box (a block on each side), so from 20 ticks
  after the break the body walks toward a table item within 4 blocks by its movement input (not navigation; reset when
  the item is in or the task lets go). A table the body did not place is left standing; a table is the body's when this task placed it or `BotMemory`
  says an earlier one did. The task records what it placed in `BotMemory` (and `BlockSafety.noteBotPlaced`).
- Failures: `MissingInput` (an ingredient or the table item is not carried), `TargetGone` (unknown recipe id; a table that
  would not open), `Protected` (every candidate spot is one the body may not break again), `Unreachable` (no free
  spot, or the game would not start breaking), `NoProgress` (no room for the result), `TimedOut` (the break or the pickup).
  A failure with a table placed leaves the table standing, remembered; the next craft that needs one finds it.

### A table left standing

The planner counts a crafting table in the inventory, never in the world (`SolverPlanner` ignores `Snapshot.stations`), so a
table this body put down and never took up (the server stopped between placing and breaking, a cancel, a failure) costs
the replanned goal four planks, or fails it when there are none to spare. `BotRuntime` therefore looks for one after a
load and whenever a goal ends (`tidyArmed`): the nearest crafting table within reach that `BotMemory` says this body
placed. Found, it runs `CraftTask.takeUp(pos)` (the craft's own break and pickup, crafting nothing) before the executor is
ticked again, so the next plan sees the table in the inventory. A failure there is logged, not a goal's failure; the table
stays, remembered. Covered by `BotRuntimeGameTests.aTableLeftStandingByALogoutIsTakenUp`.

## `equip`

Takes an `Equip` goal and puts each slot's item in place through `player.inventoryMenu`, one slot per tick. Armor and the
off hand: shift-click the old piece out, pick up the wanted item, right-click it into the slot (one item of a stack), put
the cursor back. Main hand: select the hotbar slot that holds it, or swap it in with a `SWAP` click.

## Not done here

- A body that dies with a table standing (or is cancelled mid-craft) takes it up after the goal ends only when it is
  still within reach; a table out of reach stays in `BotMemory` and is picked up by the next craft that finds it. A
  `retrieve`-style task for the navigation work to walk back to it does not exist.
- The in-tree `SolverPlanner` does not write `layout` yet. The format `CraftLayouts` reads is the one in the unmerged
  planner work (`CraftLayout` in the p12 worktree); when it lands, check the two agree.
- `LanguageLayer.explain` is not called: results are worded by `BotSpeech`.
- No reflex source is registered; `ReflexSources.set` is where the reflex layer plugs in.
