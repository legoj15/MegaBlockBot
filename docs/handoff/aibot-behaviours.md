# AIBot behaviour reference (commit a029fa6)

Source: E:/GitHub/mc_aiplayer at commit a029fa6a3760fd0f83834c104051b041d986da60 (Fabric, Yarn names, README says 1.21.3).
Base B = E:/GitHub/mc_aiplayer/src/main/java/io/github/zoyluo/aibot. Every reference below is B/<path>:<line> unless it
is written as a full path. Line numbers were read on this commit. Nothing here is copied; this is behaviour and numbers.

Verification legend: "read" = read in the code at that line. UNVERIFIED = inferred or not traced.

## 0. Premise corrections (read these first)

- There is NO librarian reroll, lectern placement, village search, job-site or POI lookup, or profession wait anywhere in
  the repo at a029fa6. A grep for lectern, librarian, reroll and profession over the whole tree returns nothing.
  The only trade code is TradeTask, which trades one simple offer with a villager already within range.
  The design doc relies on an AIBot librarian reroll in AI-PLAYER-DESIGN.md lines 229, 245, 424-426 and in the
  strategy registry (section 4.4). That behaviour must be designed from scratch, not lifted.
- The design doc (section 2.2 line 111-112) names the lava rule `ore_dig_lava_seal`. The code never logs that name.
  The seal is logged as ore_dig_fluid_seal (OreDigTask.java:1324) and ore_dig_branch_fluid_sealed (OreDigTask.java:4749).
  The only occurrence of `ore_dig_lava_seal` is a stale comment at pathfinding/NeighborEnumerator.java:194.
- The design doc (section 3.2 line 198) says the reflex layer fires on mobs "targeting the agent". The code does not.
  It fires on any HostileEntity inside a distance envelope with line of sight (see section 5). Targeting is checked only
  for Enderman anger (DangerWatcher.java:1040-1050).
- The lava rule in the design says the target cell is sealed first. The code seals the lava SOURCE cell(s) that touch
  the target ore, not the ore cell itself (OreDigTask.java:1300-1322, adjacentDangerFluidOf at 4378-4392).

## 1. Fishing (task/FishTask.java, 306 lines)

Entry: brain/ToolRegistry.java:219-223, tool "fish", max_catches default 1, max_ticks default 6000.
Constructor FishTask(int maxCatches, int maxTicks) clamps maxCatches >= 1 and maxTicks >= 200 (FishTask.java:49-52).

Phases (enum Phase, lines 24-31): FIND_WATER, MOVE_TO_WATER, CAST, WAIT_BITE, REEL, COLLECT.
Constants (lines 33-36): SEARCH_RADIUS 12, WAIT_BITE_FALLBACK_TICKS 600, COLLECT_TICKS 80, DEFAULT_MAX_TICKS 6000.

State machine (all read):
- onStart (84-90): equipRod, else fail "need_fishing_rod". Phase FIND_WATER.
- onTick (93-110): if elapsed > maxTicks, complete when catches > 0, else fail "fish_timeout". Then dispatch by phase.
- FIND_WATER (112-134): equipRod, else fail. nearestWater (232-243) scans a box of +-12 horizontal and -2..+3 vertical,
  keeps only observable water blocks (ObservableWorldQuery.canObserveBlock), and picks the water with the smallest squared
  distance that has a standable horizontal neighbour (waterChoice 245-253, Standability.isStandable). None: fail
  "no_water_nearby". If the bot is within 2.25 (squared) of the stand cell, go to CAST. Otherwise startPathTo(stand),
  falling back to startWalkTo(center), then MOVE_TO_WATER.
- MOVE_TO_WATER (136-149): arrival at 2.25 squared, then CAST. Re-issues the path when the executor is idle and
  phaseTicks > 20.
- CAST (151-168): equipRod, LookAction at water center +0.15 y. If a hook already exists go to WAIT_BITE. Otherwise
  InteractAction.useItemInAir(MAIN_HAND). Failure: fail "cast_failed:<reason>".
- WAIT_BITE (170-185): look at the hook. No hook for more than 30 ticks: back to CAST. Bite (hasBite) or phaseTicks >= 600:
  go to REEL.
- hasBite (267-273): true when hook.getHookedEntity() != null, or field caughtFish is true, or field hookCountdown > 0.
  Both fields are read by reflection (lines 275-297), trying the Yarn name then the intermediary name (field_23232 and
  field_7173). UNVERIFIED against Mojang names.
- currentHook (255-265): bot.fishHook if alive, else the nearest FishingBobberEntity owned by the bot within a 32-block box.
- REEL (187-201): equipRod. Records inventoryBeforeReel (HarvestCore.totalInventoryCount). useItemInAir(MAIN_HAND).
  Failure: fail "reel_failed:<reason>". Go to COLLECT.
- COLLECT (203-224): 80 ticks. Each tick: HarvestCore.forcePickupNearby(bot, null) and chaseDrop(bot, null, 8.0).
  When the total inventory count rose, catches++. Otherwise one sweepPickup(bot, null, 8) if a drop is within 8.
  Then complete when catches >= maxCatches, else back to FIND_WATER.
- Progress (69-76): max of catch fraction (capped 0.95) and 0.25 * time fraction (capped 0.9).
- isWaiting (79-81): true only in WAIT_BITE. No onPause or onResume override, so AbstractTask.onPause stops all actions.

Full inventory: NOT handled. FishTask never checks inventory fullness. A catch that cannot be inserted leaves the count
unchanged, so it is not counted, and the loop repeats until maxTicks ends it with "fish_timeout" (if zero catches).
InventoryAction.giveItem reports "inventory_full" (action/InventoryAction.java:229) but FishTask does not consult it.

Broken rod: NOT handled. equipRod (226-230) only checks that some Items.FISHING_ROD exists. No durability check.
When the rod breaks, the next equipRod in FIND_WATER, CAST or REEL fails with "need_fishing_rod". Nothing re-crafts or
re-fetches a rod.

## 2. Trading (task/TradeTask.java, 225 lines)

Entry: brain/ToolRegistry.java:228-232 tool "trade" (max_distance default 16). Also assign_task at ToolRegistry.java:857.
Constants (27-28): SEARCH_RANGE 16.0, TRADE_RANGE 4.0.

- onTick (72-82): elapsed > 1200 fails "trade_timeout". Phases FIND_VILLAGER, MOVE_TO_VILLAGER, TRADE (enum 21-25).
- Finding a villager (84-100, 156-164): VillagerEntity inside bot box expanded by min(maxDistance, 16), alive, not a baby,
  ObservableWorldQuery.canObserveEntity, nearest by distance. None: fail "no_villager_nearby". There is NO village,
  POI or job-site search.
- Moving (102-120): startPathTo(villager.getBlockPos()), falling back to startWalkTo(villager.getPos()). Re-issued when the
  executor is idle and phaseTicks > 20. TRADE starts when within 4.0 blocks. Villager dead: back to FIND_VILLAGER (103-105).
- Offer choice (166-183): first offer where not disabled AND getDisplayedSecondBuyItem is empty (single input only) AND
  sell item equals targetItem (when targetItem is set) AND bot holds at least the first buy count. Offer order decides.
- TRADE (122-154), in this order:
  1. canFit (185-196): an empty main slot, or a main slot with the same item below min(maxStack). Fail "inventory_full".
  2. InventoryAction.removeItems(firstBuy item, count) (action/InventoryAction.java:140). Fail "missing_buy_item".
  3. InventoryAction.giveItem(sell copy) (action/InventoryAction.java:223). Fail "give_failed:<reason>".
  4. offer.use().
  5. afterUsing (198-219). Fail "after_using_failed".
  6. complete().
- afterUsing: mixin/MerchantEntityInvokerMixin.java (@Invoker("afterUsing") on MerchantEntity). Reflection fallback over
  names "afterUsing" and "method_18008" up the class chain (TradeTask.java:205-217).
- No merchant screen is opened. No reroll, no lectern, no profession check.
- No rollback: items are removed (step 2) before the give (step 3), and a failure after step 3 leaves the trade applied.
  See adjacent issues.

## 3. Ore digging (task/OreDigTask.java, 5160 lines; mining/*, goal/GoalPlanner.java)

### 3a. Choosing the branch Y (planner, not the task)
- The task does not choose a Y. GoalPlanner.bestMiningY (GoalPlanner.java:251-252) returns MiningChain.bestY(ores).
- MiningChain.bestY (mining/MiningChain.java:62-71) is the MINIMUM bestY across the requested ores. The table is at
  mining/MiningChain.java:24-36 (bestY column last): coal 48, copper 48, iron 16, lapis -1, gold -16, emerald -16,
  redstone -59, diamond -59. Deepslate variants share the value. An ore set with no table entry gives Integer.MAX_VALUE
  (MiningChain.java:63), which the planner then uses as a target Y. UNVERIFIED that every caller guards this.
- GoalPlanner.DESCEND_THRESHOLD = 8 (GoalPlanner.java:65). When the planned Y is more than 8 above mineY, the planner
  inserts GoalStep.descendToY(mineY) before the mine step (GoalPlanner.java:690-713). The descent torch budget uses
  DESCEND_TORCH_EVERY = 6 (GoalPlanner.java:61, 1856-1858).

### 3b. Shaft descent (task/DescendToYTask.java)
- Class javadoc describes a continuous vertical shaft: BlockMiner digs the feet column, and when obstructed it uses
  staircase and lateral detours (tryLateralDetour 1060, rotateStair 1375, tryPlaceDetourSupport 1167).
- Constants: MIN_Y -60 (52), MAX_LATERAL 32 (56), TORCH_EVERY 6 (99).
- Torch (maybePlaceTorch 1465-1480): when the last torch Y is at least 6 blocks above the feet, or none placed yet, and feet
  block light is below 8, place a torch at the feet. Place only when a torch is in inventory.
- Completion: reaching targetY only completes after a dry working face is confirmed (comment at DescendToYTask.java:452-455).
- Edge: MIN_Y checks at 476 (below.getY() <= MIN_Y) and OreDigTask.java:4093.

### 3c. Branch (strip) mining
- Four cardinal directions in order N, E, S, W (OreDigTask.java:131-132). stripDirIndex (159) is the current direction.
- Leg length STRIP_SEGMENT = 48 (129). stripStepsLeft counts down (783, 1597-1604). Each step digs the next feet and head
  cells, then steps in (digTowardStep, the TunnelIntent.BLIND_BRANCH call around 1740).
- Scan constants: SCAN_INTERVAL 10 (104), SCAN_RADIUS 24 (105), VERTICAL_SCAN 10 (108), PROSPECT_RANGE 64 (106, used only
  when the local scan finds nothing), PROSPECT_INTERVAL 40 (107).
- Reach: REACH_SQUARED = 20.25 (111), that is 4.5 blocks squared.
- Other limits: MIN_TARGET_BREAK_DY -1 and MAX_TARGET_BREAK_DY 2 (125-126). VEIN_CAP 64 (113). BONUS_CAP 8 (127).
  APPROACH_LIMIT 80 (128). STRIP_AFTER_SKIPS 3 (99). NO_PROGRESS_LIMIT 200 ticks (100), which fails the task.
  PICKUP_GRACE_TICKS 30 (114). TARGET_DROP_RECOVERY_LIMIT 200 (115). RESTORE_FACE_LIMIT 1200 (101).
- Torches on the branch (OreDigTask.java:1702-1731): when stripStepsLeft % 10 == 0 and block light at feet is below 8, place
  a torch at the feet via BuildAction.placeBlockAt(bot, feet). Only rare expeditions count placements, capped by
  MiningBudget.RARE_BATCH_TORCH_LIMIT = 40 (mining/MiningBudget.java:40). Exhaustion or no torch fails with
  resourceEpochFailureReason. Ordinary batches silently skip torches when none are carried.
- Timeout: maxElapsed set by maxElapsedForTarget (OreDigTask.java:2400 onward), with ORE_DIG_HARD_WINDOW_TICKS = 24000 per
  resource epoch (mining/MiningMissionBudget.java:15, 72). Failure string "ore_dig_timeout collected=<n>" (OreDigTask.java:903-905).

### 3d. Lava handling
Five layers, in the order they run:
1. Planner dig pre-check: pathfinding/NeighborEnumerator.java digEnterable (about lines 180-200) rejects a dig cell whose
   foot or head has lava on its top or horizontal sides (adjacentLava, lines 204-214: up plus four horizontals, NOT down).
   Exception: the path goal cell itself, which the task must then seal before mining. The data is read straight from the
   ServerWorld (world.getFluidState), not from an observability check. See adjacent issues.
2. Adjacent-target seal, the real "ore_dig_lava_seal" equivalent (OreDigTask.java:1296-1336). Runs only when the target is not
   yet being mined (!miningTarget). adjacentDangerFluidOf (4378-4392) looks at the six faces of the ore, each through
   OreScan.observeDangerFluid (mining/OreScan.java:145). On OBSERVED_PRESENT:
   - Pick a sacrificial stone block with MaterialPalette.pickSacrificialBlockSlot(bot, protectedStoneLikeReserve).
     None: log ore_dig_fluid_unsealable, excludeOre, drop the target, return.
   - Otherwise equip it and BuildAction.placeBlockAt(bot, lavaPos). Success: ore_dig_fluid_seal and noteProgress.
     Failure: ore_dig_seal_fail, then startDigPathTo(targetOre.down(), protectedStoneLikeReserve) to get closer.
   - One seal per tick. Returns, so the mine waits for the next tick.
3. Branch lateral seal (sealOneObservableLateralBranchFluid, OreDigTask.java:4696-4756, enum at 65): checks the four
   horizontal neighbours of the branch cell and of the cell above it. Returns CLEAR, SEALED or BLOCKED. Logs
   ore_dig_branch_fluid_sealed or ore_dig_branch_fluid_unsealable or ore_dig_branch_fluid_seal_failed.
4. Lava ahead is a reroute, not an evade (avoidObservedLava, OreDigTask.java:403-458). Returns early when lava is not ahead
   of the strip direction (isAheadOfDirection). Otherwise rerouteBlindBranchAtObservedBoundary(..., "lava", pos) turns the
   branch to another direction, recording a LavaReroute with a 40-budget escape window (lines 430-436). If no reroute is
   possible, it keeps ownership and returns true, so the typed failure drives a replan (lines 440-445). Logs
   ore_dig_lava_direction_rejected.
5. Immediate and body-level:
   - hasImmediateLava (OreDigTask.java:681-701): lava at feet, centre, horizontal neighbours or below feet.
   - Body column (about 3720-3770): fluid in the head or feet cell calls rejectObservedTunnelBoundary with
     observedFluidReason, which closes the branch.
   - SurvivalGuard guard_in_lava (task/SurvivalGuard.java:51-52) and DangerWatcher LAVA threat (section 5).

### 3e. Falling blocks (sand, gravel, anvils and the like)
- Planner: NeighborEnumerator.digEnterable (about line 198-200) rejects any dig cell whose head-up block is a FallingBlock,
  regardless of goal.
- Blind branch: a FallingBlock in the head or feet cell calls rejectObservedTunnelBoundary(..., "gravity", ...)
  (OreDigTask.java:3738-3741 and 3761-3764). Also at 1529 and 1554.
- Drop catch: hasReliableObservedDropCatch (OreDigTask.java:3252-3263) rejects a FallingBlock as a drop catch.
- Timing: onTick (about 883-905) calls recoverBlockedBody(bot, world, feet) before any scan, because falling blocks update
  after the tick that opened the cell. A gravity obstruction yields one full tick (OreDigTask.java:1505-1507).
- Helper: firstFallingObstruction (OreDigTask.java:4188-4198).

### 3f. Returning to the surface
- OreDigTask has NO ascend phase. returnToSavedFace (OreDigTask.java:2901-2927) walks back to lastFace (the strip start
  face) with RESTORE_FACE_LIMIT 1200. Failure: "ore_dig_restore_face_unreachable".
- The planner inserts service boundaries between batches: GoalStep.miningService and miningHandoffService
  (GoalPlanner.java:757, 783). Rare expeditions service every 8 items (RARE_BATCH_SIZE, MiningBudget.java:49).
- How the bot gets from depth back to the surface at the end of a mission is UNVERIFIED. DigDownTask.java (1832 lines) owns
  a return-to-entry staircase (DangerWatcher.java:213-216 comment), but I did not trace the mission-end path.

## 4. Tree chopping and block collection (task/GatherQuotaTask.java, 1074 lines; action/HarvestCore.java)

No tree logic. GatherQuotaTask mines individual blocks from a set, not whole trees.
- Block set (GatherQuotaTask.java:1036-1074): any log species (acceptItemsFor, RecipeRegistry.LOGS), cobblestone maps to
  stone, wheat seeds map to short grass, tall grass, fern and large fern, forage maps to sweet berry bush and melon.
- Search (survey, GatherQuotaTask.java:620-660): HarvestCore.nearestReachableBlock(bot, blocks, radius, down 6, up 12,
  filter). Constants SEARCH_RADIUS 16 (line 24), doubling to MAX_SEARCH_RADIUS 48 (line 26), SEARCH_DOWN 6 (27), SEARCH_UP 12
  (28). Excluded positions are filtered by EpisodeMemory.
- Candidate choice (HarvestCore.java:40-70 and 514-521): candidates are every observable block of a target type in the box,
  sorted by squared distance from the bot. Only the first REACH_VERIFY_LIMIT = 8 (line 29) are verified, each by a walk
  A* (isWalkReachable, 523-530; AStarPathfinder with REACH_MAX_NODES 3000 and REACH_MAX_MILLIS 30, lines 30-31). First one
  that passes wins.
- Target choice (HarvestCore.targetChoice, 532-545): direct mine when eye-to-centre distance is at most 4.5 (canReach,
  503-505). Otherwise an adjacent standable cell (adjacentStandPos).
- Harvest (GatherQuotaTask.java:785-810): HarvestCore.startMining (HarvestCore.java:73-76, ToolSelector.equipBestTool plus
  MiningAction.startMining). Re-issued every 200 ticks when mining is idle. HARVEST_LIMIT 240 ticks per block (line 34), then
  excluded for EpisodeMemory.TTL_UNREACHABLE. Unreachable blocks go through startDigPathTo (tryDigApproach, 692-704).
- Single-block mining: BlockMiner.MINE_TIMEOUT_TICKS = 200 (action/BlockMiner.java:32, 95-97), fails "mine_timeout".
- Pickup (pickup, GatherQuotaTask.java:812-837 onward):
  1. HarvestCore.forcePickupNearbyAnyOf (HarvestCore.java:91-126). Radii from config Pickup(2.75 horizontal, 2.5 vertical)
     (AIBotConfig.java:157). Gated by PrivilegedCapability.FORCED_PICKUP, which the default STRICT_SURVIVAL profile denies
     (mode/CapabilityPolicy.java STRICT branch). When allowed, items go straight into the inventory with giveItem and the
     entity is discarded or shrunk. No vanilla pickup.
  2. Otherwise physical: nearestDropAnyOf radius 8 (HarvestCore.java:82-89), then approachDropPhysically (183) or
     approachKnownPickupCell(pickupOrigin) (250).
  3. Misses: MAX_PICKUP_MISSES 5 (GatherQuotaTask.java:30). PICKUP_STALL_SWEEP_TICKS 30 (119) triggers the sweep offsets.
- Full inventory: survey (GatherQuotaTask.java:625-627) switches to DEPOSIT when HarvestCore.isInventoryFull (414) and
  quota is not met. Failure reason "inventory_full" at line 996.

## 5. Reflex layer numbers

Config defaults (AIBotConfig.java; default profile STRICT_SURVIVAL at line 131):
- Perception(16, 20, 10, 10, false) at line 137.
- Watchdog(stuckWindowTicks 200) at line 139.
- Survival(hungerEatThreshold 14, hungerCriticalThreshold 6) at line 151 (record at 289).
- Combat(retreatHp 10, maxEnemiesToFight 2) at line 152 (record at 297). retreatHp is in HP points, so 10 is five hearts.
- Night(autoSleep true, torchLightThreshold 8) at line 153.
- Nav(1.0, 12, 60, 30, 4, 2, 3.0, 3) at line 156. Goal(maxPlanDepth 24, replanOnFailure true, autoToolFill true) at line 155.
- Pickup(2.75, 2.5, 8.0) at line 157.

SurvivalGuard (task/SurvivalGuard.java). Exempt task classes (33-37): EvadeTask, CreeperDefenseTask, CombatTask,
EmergencyShelterTask, MiningBarricadeTask, EatTask, LavaEscapeTask. Checks in order:
- Drowning: submerged AND air < 100 (about 5 seconds), returns guard_drowning (41-48). OreDigTask is exempt there because it
  has its own drowning handling.
- In lava: guard_in_lava (51-53).
- On fire AND HP < 10: guard_on_fire (55-57).
- HP <= 6 AND hurtTime > 0: guard_low_hp_under_attack (59-61).

DangerWatcher constants (task/DangerWatcher.java:47-57): TRAP_REPEAT_LIMIT 4, TRAP_BACKOFF_TICKS 600, TRAP_HELP_INTERVAL 1200,
HUNT_FOOD_TARGET 3, DARK_STUCK_TICKS 160, EMERGENCY_SHELTER_HP 8.0F, DROP_RECOVERY_MAX_DISTANCE 80.0, DROP_RECOVERY_MAX_VERTICAL_DELTA 24,
SHELTER_RETRY_COOLDOWN 100, SHELTER_EPISODE_RADIUS 4.0, CLOSE_DEFENSIVE_HOSTILE_RADIUS = ATTACK_RANGE + 2 = 5.0.

Threat detection:
- Envelope (CombatCore.java:24-28, 62-76): ordinary HostileEntity within 10 blocks; Creeper within 16; ranged mobs within 20
  only with line of sight. ATTACK_RANGE = 3.0F (line 24).
- Candidate set (DangerWatcher.java:1024-1032): HostileEntity, alive, canObserveEntity, inside the envelope. Targeting is NOT
  required. Enderman is the exception: active only once angry at this bot (1040-1050).
- A mob is a real threat only with line of sight (canReachThreat, CombatCore.hasLineOfSight at 114-121, raycast on COLLIDER).
- collectTopThreat (DangerWatcher.java:1161-1193): creepers sort first, then by distance. LOW_HP when HP < 6.0F (line 1181,
  hard-coded, not from config). HOSTILE otherwise. DROWNING when submerged and air < 50 (1189). LAVA when a lava block is in
  the 5x3x5 observable box (1190 onward). FALLING when fallDistance > 5 and airborne.

Response choice (defensive path, about lines 655-690):
- Creeper or Enderman: EvadeTask (655-657).
- canFight (DangerWatcher.java:993-1005): threat is HOSTILE, HP > retreatHp (10), not creeper or enderman, visible hostiles
  <= maxEnemiesToFight (2), and EquipAction.bestWeaponSlot present. Then CombatTask.defensive(entity, retreatHp, anchor)
  (677-681).
- Otherwise night shelter, else EvadeTask.

Pause and resume:
- pauseFor (task/TaskManager.java:236) pushes the current task onto the LIFO pause stack.
- Resume happens in DangerWatcher.java:452-455. Conditions: no active task, no active actions, has paused task, and
  canResumePausedWork (DangerWatcher.java:460-464), which is: no threat AND hurtTime == 0 AND HP > retreatHp (10).
  There is NO tick-count gate on resume.
- Creeper path (CreeperDefenseTask): RISK_MEMORY_TICKS 100, ESCAPE_DISTANCE 12, SAFE_LAST_SEEN 10 blocks, WALL_BUILD_LIMIT 20
  (CreeperDefenseTask.java:44-55). Its own grace rule, not traced further.

Hunger and healing:
- Eat when food <= 14 (DangerWatcher.java:558-566, early return when food > hungerEatThreshold and not healing).
- Critical when food <= 6 (line 564). Critical starvation overrides a user pause (maybeResupply, about 466-468).
- Healing eat: HP <= 10 AND food < 20 AND food item present (isHealingEatTransaction, 1124-1127).

CombatTask (task/CombatTask.java) retreat and heal:
- Retreat when HP <= retreatHp (onTick 138-142; DangerWatcher passes combat.retreatHp()).
- RETREAT uses EvadeTask.admitBestSurfaceEscapePath (354-357). No route: fail "combat_no_valid_retreat_route" (358).
- HEAL (371-395): eat when a food slot exists. Back to ACQUIRE once HP > retreatHp + 4. HEAL_WAIT_TICKS 200 (line 37), then
  RETREAT if still low.

StuckWatcher (task/StuckWatcher.java):
- Window = watchdog.stuckWindowTicks, default 200 (line 33).
- Sample = (block position, task.progress(), total count of main and offhand stacks). Any change resets the sample (39-53 and
  the Sample.changed method, which also compares progress). Note the design doc says position or inventory only; progress is
  compared too.
- Skipped when the task is not RUNNING or isWaiting() (lines 39-41). OreDigTask.isWaiting() is always true
  (OreDigTask.java:382-386), so StuckWatcher never fires for ore digging.
- On expiry: TaskManager.abort, recordFailure("stuck:<name>"), warn log "task_stuck_aborted".

GoalExecutor replan and retry budgets (goal/GoalExecutor.java):
- BASE_LIFETIME_REPLAN_LIMIT = 12 (line 70). MAX_CONSECUTIVE_REPLANS = 3 (line 71). MAX_POSTCONDITION_REPLANS = 3 (line 72).
- lifetimeReplanLimit (3336-3346): 12 for ordinary goals. For a rare-ore goal at or above MiningBudget.EXPEDITION_THRESHOLD (8),
  max(12, 3 * batchCount) where batchCount comes from MiningBudget.forQuota(target, true, IRON).
- withinReplanBudget (3353-3357): consecutiveReplans < 3 AND lifetimeReplans < limit.
- Replan site (3832-3838): if budget is exhausted or replanOnFailure is off, finishActive(failed). Otherwise both counters
  increment, then GoalPlanner.plan runs fresh.
- Progress amnesty (3820): madeReplanProgress resets replanCount to 0.
- withinPostconditionRepairBudget (3237-3239): replans < 3.

Retry budgets inside tasks: BlockMiner MINE_TIMEOUT_TICKS 200; CombatTask combat_timeout 2400 (CombatTask.java:113);
TradeTask 1200; FishTask maxTicks 6000 default.

## 6. Crafting, stations and smelting

- No menu, no screen. There is no ScreenHandler, clickSlot or openHandledScreen anywhere under src/main. Everything edits
  inventories and block entities directly.
- CraftTask (task/CraftTask.java, 349 lines): plan from CraftingHelper.plan. craftNext (140-163): needsCraftingTable with no
  table nearby and none carried sends the phase to ENSURING_TABLE. prepareCraft (172 onward) copies main and offhand lists,
  removes ingredients and adds the output. commitPreparedCraft (279-288) writes every slot back into PlayerInventory.main and
  offHand and calls markDirty. This is copy-then-commit, and it is atomic.
- Output capacity check: fail "craft_output_capacity:..." (CraftTask.java about 152-156).
- Table placement (ensureTable, 114-131): finds CRAFTING_TABLE in inventory, adjacentAir spot, equips it, then
  BuildAction.placeBlockAt (action/BuildAction.java:80). Fail "place_crafting_table_failed".
- A table carried in inventory counts as present (CraftTask.java:141-143 and 329-331), so it is not re-placed.
- Nearby table check: nearbyCraftingTable (CraftTask.java:302) scans for a Blocks.CRAFTING_TABLE state.
- PlaceStationsTask (task/PlaceStationsTask.java:26): STATIONS = CRAFTING_TABLE, FURNACE, CHEST, one each. Equip the stack,
  place at findFreeSpot, record in placedPositions (69-70). GoalExecutor takes the first recorded position as the plan
  origin (goal/GoalExecutor.java:5105-5106).
- Pickup of stations: NONE. A grep for Blocks.CRAFTING_TABLE, FURNACE and CHEST finds only queries
  (goal/GoalSnapshotCollector.java:129-133, perception/PerceptionCollector.java:181-190). Placed stations stay in the world.
- SmeltTask (task/SmeltTask.java, 732 lines): phases FINDING_FURNACE, WALKING_TO_FURNACE, CRAFTING_FURNACE, PLACING_FURNACE,
  LOADING, COLLECTING (enum 34-40+). LOCAL_FURNACE_DISTANCE_SQUARED = 576 (24 squared, line 51). CRAFTING_FURNACE spawns a
  sub-CraftTask(FURNACE, 1) (line 357). Placement clears space with its own BlockMiner (clearMiner, line 85). A walk that
  cannot path falls back to DigNav.digStep (lines 340-345).
- Furnace I/O is direct block-entity access: AbstractFurnaceBlockEntity getStack and setStack, slots 0 input, 1 fuel, 2 output
  (SmeltTask.java:480-531 for loading, 541-580 for collecting). markDirty at 531 and 580. Burning is read from the block
  state (isBurning, about 504). Errors: "furnace_input_occupied", "unexpected_output", "missing <item> x<n>".

## 7. Combat

Entry: CombatTask (task/CombatTask.java, 602 lines). Created by ToolRegistry (lines 439 and 841), by
DangerWatcher.defensive (667, 680) and for trapped fight-back (817, threshold 0.0F).

- Phases (switch 143-152): ACQUIRE, APPROACH, RANGED, STRIKE, BLOCK, REPOSITION, RETREAT, HEAL.
- Constants (33-44): SEARCH_RANGE 20, RANGED_MIN_DISTANCE 7.0F, BOW_CHARGE_TICKS 20, BLOCK_TICKS 12, HEAL_WAIT_TICKS 200,
  HEAL_SAFE_DISTANCE = ATTACK_RANGE + 2 = 5.0, CREEPER_HEAL_SAFE_DISTANCE 8.0, RETREAT_STEP_DISTANCE 6, LOST_SIGHT_LIMIT 50,
  DEFENSIVE_MAX_VERTICAL_DROP 2, DEFENSIVE_MAX_HORIZONTAL_DISTANCE 8.0.
- onStart (105-109): CombatCore.equipMelee (EquipAction.equipBestArmor, then ensureMeleeWeapon), then
  EquipAction.equipShieldOffhand.
- onTick (112-153): elapsed > 2400 fails "combat_timeout". Target out of sight for more than 50 ticks: complete (129-134).
  HP <= retreatHp and not already retreating or healing: beginRetreat (138-142).

Weapon choice (action/EquipAction.java):
- bestWeaponSlot (73-100): only SwordItem or AxeItem with remaining durability >= 2 (isQualifiedMeleeWeapon 103-107,
  MIN_MELEE_RAW_DURABILITY = 2 at line 21). Highest attack damage (MAINHAND ATTACK_DAMAGE attribute). Ties: sword over axe,
  then more remaining durability. Starting best damage is 1.0, so bare hands never qualify.
- Bow: bestRangedSlot (about 116-129) needs at least one ARROW in inventory and a BOW in main.
- Re-equip around each swing: CombatCore.ensureMeleeWeapon is called before and after the strike (CombatTask.java:262-266).
  A broken weapon is replaced in the same tick.

Attack cooldown:
- CombatCore.strikeIfReady (CombatCore.java:135-142): lookAt, then if getAttackCooldownProgress(0.5F) < 0.95 do nothing.
  Otherwise InteractAction.attackEntity (action/InteractAction.java:13-22): lookAt, player.attack, swingHand MAIN_HAND,
  resetLastAttackedTicks, updateLastActionTime.
- After a hit: REPOSITION for 8 ticks (repositionTicks = 8 at CombatTask.java:267). Strafe at +-0.45, alternating every 20
  ticks (290-304), then STRIKE.
- Strike range: approach until within ATTACK_RANGE (3.0). Back to APPROACH if more than 3.75 away (CombatTask.java:257-260).

Shield:
- equipShieldOffhand moves a shield into the offhand at start (EquipAction.java:132-147).
- shouldBlock (CombatTask.java:578-583): offhand is a SHIELD, target within 4.0, and HP <= retreatHp + 6 (16 at defaults).
- BLOCK (272-288): useItemInAir(OFF_HAND), hold for BLOCK_TICKS 12, or end early if the target moves beyond 4.5. Then STRIKE.

Ranged:
- shouldUseBow (564-569): target more than 7.0 away AND bestRangedSlot present. beginRanged (570-575). Charge up to 20 ticks,
  then APPROACH (ranged, 210-237).

Flee:
- Retreat when HP <= retreatHp (config, default 10).
- Never melee Creeper or Enderman: CombatCore.isMeleeForbiddenThreat (84-86). ACQUIRE and STRIKE go to beginRetreat at once
  (CombatTask.java:170-173, 247-250).
- HEAL and then back to ACQUIRE at HP > retreatHp + 4 (line 379), as in section 5.
- Defensive mode: fixed anchor. Disengage (endDefensiveEngagement, complete) when the target moves beyond 8 horizontal or 2
  vertical from the anchor (CombatTask.java about 530-545).
- Kill count: finishOrAcquire (about 583-595) completes when kills >= targetKills, else back to ACQUIRE.

## Adjacent issues

1. Stale and wrong names. Design section 2.2 and NeighborEnumerator.java:194 say ore_dig_lava_seal. Code logs
   ore_dig_fluid_seal (OreDigTask.java:1324). AIBotVerifySubcommand.java:2696 also says ore_dig_fluid_seal.
2. Missing trade behaviour. Design section 2.1, section 3.4 and section 4.4 presume a librarian reroll. The code has none.
   Only a single in-range trade exists.
3. Trigger mismatch. Design section 3.2 says "targeting the agent". Design section 3.4 (line 371) says "no mob targeting
   the body". Code uses an envelope plus line of sight (DangerWatcher.java:1024-1032).
4. Mixed HP thresholds. DangerWatcher.java:1181 uses hard-coded 6.0F. SurvivalGuard.java:59 uses 6. DangerWatcher.java:52 uses
   8.0F. Config retreatHp (AIBotConfig.java:152) is 10. Resume and canFight use 10. So there are at least three
   thresholds that do not follow config.
5. StuckWatcher blind spot. OreDigTask.isWaiting() is always true (OreDigTask.java:382-386), so StuckWatcher is disabled for
   the mission. The only watchdog left is OreDig's own NO_PROGRESS_LIMIT 200.
6. Fishing gaps. No full-inventory handling (FishTask.java:203-224). No rod-durability handling (FishTask.java:226-230).
   Reflection on the Yarn fields caughtFish and hookCountdown (FishTask.java:267-297) breaks if the field names change.
7. Trade ordering. removeItems then giveItem then offer.use then afterUsing (TradeTask.java:139-152). A give failure after
   removeItems loses the buy items. A failure at afterUsing happens after the items already changed hands. Nothing refunds.
   canFit ignores the offhand (TradeTask.java:185-196).
8. Planner reads the world directly. NeighborEnumerator.adjacentLava (lines 204-214) and digEnterable use
   world.getFluidState and getBlockState with no observability gate, unlike the task layer. UNVERIFIED whether a strict
   profile can reach this path in practice.
9. bestMiningY sentinel. MiningChain.bestY returns Integer.MAX_VALUE for an ore set with no table entry (MiningChain.java:63).
   GoalPlanner.bestMiningY (GoalPlanner.java:251-252) passes that through unchecked.
10. Trade code is version-sensitive. afterUsing is protected in 1.21.3 (WORKORDERS.md:210). The mixin at
    mixin/MerchantEntityInvokerMixin.java must be re-checked on 1.21.1.
11. Forced pickup is a capability, not a rule. HarvestCore.forcePickupNearbyAnyOf inserts items directly and discards
    entities (HarvestCore.java:91-126). It is denied in strict profile but the call site still runs. Any port should keep the
    gate explicit, not rely on the call being skipped.

## Open questions

1. Is there an AIBot commit after a029fa6, or another repo, that has a librarian reroll or lectern logic? None exists at
   a029fa6, so the design's section 3.4 and 4.4 references need a source or a decision to design it fresh.
2. How does the mission return to the surface at the end of a deep ore batch? OreDigTask has no ascend phase, and I did not
   trace DigDownTask.java (1832 lines) or the GoalPlanner service steps far enough to answer.
3. Mojang names for FishingBobberEntity fields caughtFish and hookCountdown, and for TradeOffer afterUsing and MerchantEntity,
   on 1.21.1. UNVERIFIED without the mapping jar.
4. Does any strict-profile code path reach NeighborEnumerator's world reads (adjacentLava and digEnterable) without an
   observation check? UNVERIFIED.
5. The intent of the 2.25 squared arrival radius for FishTask and the 8-block horizontal drop-pickup window in COLLECT are
   taken from the code as written. Their tuning source is not checked.
