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

package com.legoj15.megablockbot.nav.engine.api;

import com.legoj15.megablockbot.nav.NavRuntime;
import com.legoj15.megablockbot.nav.engine.Baritone;
import com.legoj15.megablockbot.nav.engine.api.behavior.ILookBehavior;
import com.legoj15.megablockbot.nav.engine.api.behavior.IPathingBehavior;
import com.legoj15.megablockbot.nav.engine.api.cache.IWorldProvider;
import com.legoj15.megablockbot.nav.engine.api.component.EntityComponentKey;
import com.legoj15.megablockbot.nav.engine.api.event.listener.IEventBus;
import com.legoj15.megablockbot.nav.engine.api.pathing.calc.IPathingControlManager;
import com.legoj15.megablockbot.nav.engine.api.process.IBuilderProcess;
import com.legoj15.megablockbot.nav.engine.api.process.ICustomGoalProcess;
import com.legoj15.megablockbot.nav.engine.api.process.IExploreProcess;
import com.legoj15.megablockbot.nav.engine.api.process.IFarmProcess;
import com.legoj15.megablockbot.nav.engine.api.process.IFollowProcess;
import com.legoj15.megablockbot.nav.engine.api.process.IGetToBlockProcess;
import com.legoj15.megablockbot.nav.engine.api.process.IMineProcess;
import com.legoj15.megablockbot.nav.engine.api.utils.IEntityContext;
import com.legoj15.megablockbot.nav.engine.api.utils.IInputOverrideHandler;
import java.util.Arrays;
import java.util.stream.Stream;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

public interface IBaritone {
   EntityComponentKey<IBaritone> KEY = new EntityComponentKey<>(Baritone::new);

   IPathingBehavior getPathingBehavior();

   ILookBehavior getLookBehavior();

   IFollowProcess getFollowProcess();

   IMineProcess getMineProcess();

   IBuilderProcess getBuilderProcess();

   IExploreProcess getExploreProcess();

   IFarmProcess getFarmProcess();

   ICustomGoalProcess getCustomGoalProcess();

   IGetToBlockProcess getGetToBlockProcess();

   IWorldProvider getWorldProvider();

   IPathingControlManager getPathingControlManager();

   IInputOverrideHandler getInputOverrideHandler();

   IEntityContext getEntityContext();

   IEventBus getGameEventHandler();

   void logDebug(String var1);

   // MegaBlockBot: never to chat (upstream messaged the player, or every creative player for a non-player entity);
   // the module logger at debug. Baritone overrides this to also keep the last message.
   default void logDirect(Component... components) {
      StringBuilder text = new StringBuilder();
      Arrays.asList(components).forEach(c -> text.append(c.getString()));
      NavRuntime.LOGGER.debug(text.toString());
   }

   default void logDirect(String message, ChatFormatting color) {
      Stream.of(message.split("\n")).forEach(line -> {
         MutableComponent component = Component.literal(line.replace("\t", "    "));
         component.setStyle(component.getStyle().applyFormat(color));
         this.logDirect(component);
      });
   }

   default void logDirect(String message) {
      this.logDirect(message, ChatFormatting.GRAY);
   }

   boolean isActive();

   Settings settings();

   void serverTick();
}
