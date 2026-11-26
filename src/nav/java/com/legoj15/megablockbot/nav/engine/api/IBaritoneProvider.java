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

import com.legoj15.megablockbot.nav.engine.api.cache.IWorldScanner;
import com.legoj15.megablockbot.nav.engine.api.schematic.ISchematicSystem;
import java.util.function.Function;
import net.minecraft.world.entity.LivingEntity;

public interface IBaritoneProvider {
   IBaritone getBaritone(LivingEntity var1);

   IWorldScanner getWorldScanner();

   ISchematicSystem getSchematicSystem();

   Settings getGlobalSettings();

   <E extends LivingEntity> Function<E, IBaritone> componentFactory();
}
