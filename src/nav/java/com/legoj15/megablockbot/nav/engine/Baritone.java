/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.legoj15.megablockbot.nav.engine;

import com.legoj15.megablockbot.nav.NavRuntime;
import com.legoj15.megablockbot.nav.engine.api.IBaritone;
import com.legoj15.megablockbot.nav.engine.api.Settings;
import com.legoj15.megablockbot.nav.engine.api.cache.IWorldProvider;
import com.legoj15.megablockbot.nav.engine.api.event.listener.IEventBus;
import com.legoj15.megablockbot.nav.engine.api.process.IBaritoneProcess;
import com.legoj15.megablockbot.nav.engine.api.utils.IEntityContext;
import com.legoj15.megablockbot.nav.engine.behavior.Behavior;
import com.legoj15.megablockbot.nav.engine.behavior.InventoryBehavior;
import com.legoj15.megablockbot.nav.engine.behavior.LookBehavior;
import com.legoj15.megablockbot.nav.engine.behavior.MemoryBehavior;
import com.legoj15.megablockbot.nav.engine.behavior.PathingBehavior;
import com.legoj15.megablockbot.nav.engine.cache.WorldProvider;
import com.legoj15.megablockbot.nav.engine.event.GameEventHandler;
import com.legoj15.megablockbot.nav.engine.process.BackfillProcess;
import com.legoj15.megablockbot.nav.engine.process.BuilderProcess;
import com.legoj15.megablockbot.nav.engine.process.CustomGoalProcess;
import com.legoj15.megablockbot.nav.engine.process.ExecControlProcess;
import com.legoj15.megablockbot.nav.engine.process.ExploreProcess;
import com.legoj15.megablockbot.nav.engine.process.FarmProcess;
import com.legoj15.megablockbot.nav.engine.process.FollowProcess;
import com.legoj15.megablockbot.nav.engine.process.GetToBlockProcess;
import com.legoj15.megablockbot.nav.engine.process.MineProcess;
import com.legoj15.megablockbot.nav.engine.utils.BlockStateInterface;
import com.legoj15.megablockbot.nav.engine.utils.InputOverrideHandler;
import com.legoj15.megablockbot.nav.engine.utils.PathingControlManager;
import com.legoj15.megablockbot.nav.engine.utils.player.EntityContext;
import net.minecraft.world.entity.LivingEntity;

public class Baritone implements IBaritone {
   private final Settings settings;
   private final GameEventHandler gameEventHandler;
   private final PathingBehavior pathingBehavior;
   private final LookBehavior lookBehavior;
   private final MemoryBehavior memoryBehavior;
   private final InventoryBehavior inventoryBehavior;
   private final InputOverrideHandler inputOverrideHandler;
   private final FollowProcess followProcess;
   private final MineProcess mineProcess;
   private final GetToBlockProcess getToBlockProcess;
   private final CustomGoalProcess customGoalProcess;
   private final BuilderProcess builderProcess;
   private final ExploreProcess exploreProcess;
   private final BackfillProcess backfillProcess;
   private final FarmProcess farmProcess;
   private final IBaritoneProcess execControlProcess;
   private final PathingControlManager pathingControlManager;
   private final IEntityContext playerContext;
   public BlockStateInterface bsi;
   public AdditionalBaritoneSettings additionalBaritoneSettings = new AdditionalBaritoneSettings();

   public Baritone(LivingEntity player) {
      this.settings = new Settings();
      this.gameEventHandler = new GameEventHandler(this);
      this.playerContext = new EntityContext(player);
      this.pathingBehavior = new PathingBehavior(this);
      this.lookBehavior = new LookBehavior(this);
      this.memoryBehavior = new MemoryBehavior(this);
      this.inventoryBehavior = new InventoryBehavior(this);
      this.inputOverrideHandler = new InputOverrideHandler(this);
      this.pathingControlManager = new PathingControlManager(this);
      this.pathingControlManager.registerProcess(this.followProcess = new FollowProcess(this));
      this.pathingControlManager.registerProcess(this.mineProcess = new MineProcess(this));
      this.pathingControlManager.registerProcess(this.customGoalProcess = new CustomGoalProcess(this));
      this.pathingControlManager.registerProcess(this.getToBlockProcess = new GetToBlockProcess(this));
      this.pathingControlManager.registerProcess(this.builderProcess = new BuilderProcess(this));
      this.pathingControlManager.registerProcess(this.exploreProcess = new ExploreProcess(this));
      this.pathingControlManager.registerProcess(this.backfillProcess = new BackfillProcess(this));
      this.pathingControlManager.registerProcess(this.farmProcess = new FarmProcess(this));
      this.execControlProcess = ExecControlProcess.registerProcess(this);
   }

   public PathingControlManager getPathingControlManager() {
      return this.pathingControlManager;
   }

   public void registerBehavior(Behavior behavior) {
      this.gameEventHandler.registerEventListener(behavior);
   }

   public InputOverrideHandler getInputOverrideHandler() {
      return this.inputOverrideHandler;
   }

   public CustomGoalProcess getCustomGoalProcess() {
      return this.customGoalProcess;
   }

   public GetToBlockProcess getGetToBlockProcess() {
      return this.getToBlockProcess;
   }

   @Override
   public IEntityContext getEntityContext() {
      return this.playerContext;
   }

   public MemoryBehavior getMemoryBehavior() {
      return this.memoryBehavior;
   }

   public FollowProcess getFollowProcess() {
      return this.followProcess;
   }

   public BuilderProcess getBuilderProcess() {
      return this.builderProcess;
   }

   public InventoryBehavior getInventoryBehavior() {
      return this.inventoryBehavior;
   }

   public LookBehavior getLookBehavior() {
      return this.lookBehavior;
   }

   public ExploreProcess getExploreProcess() {
      return this.exploreProcess;
   }

   public MineProcess getMineProcess() {
      return this.mineProcess;
   }

   public FarmProcess getFarmProcess() {
      return this.farmProcess;
   }

   public PathingBehavior getPathingBehavior() {
      return this.pathingBehavior;
   }

   public WorldProvider getWorldProvider() {
      return (WorldProvider)IWorldProvider.KEY.get(this.getEntityContext().world());
   }

   @Override
   public IEventBus getGameEventHandler() {
      return this.gameEventHandler;
   }

   public IBaritoneProcess getExecControlProcess() {
      return this.execControlProcess;
   }

   @Override
   public boolean isActive() {
      return this.pathingControlManager.isActive();
   }

   @Override
   public Settings settings() {
      return this.settings;
   }

   public AdditionalBaritoneSettings getExtraBaritoneSettings() {
      return this.additionalBaritoneSettings;
   }

   @Override
   public void logDebug(String message) {
      NavRuntime.LOGGER.debug(message);
   }

   @Override
   public void serverTick() {
      this.getGameEventHandler().onTickServer();
   }
}
