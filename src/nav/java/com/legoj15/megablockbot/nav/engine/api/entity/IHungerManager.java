// SPDX-License-Identifier: LGPL-3.0-or-later
// MegaBlockBot addition to the ported engine (see UPSTREAM.md). Upstream returned its own LivingEntityHungerManager;
// a player body has vanilla FoodData instead, so the engine reads hunger through this interface.
package com.legoj15.megablockbot.nav.engine.api.entity;

public interface IHungerManager {
   int getFoodLevel();

   float getSaturationLevel();
}
