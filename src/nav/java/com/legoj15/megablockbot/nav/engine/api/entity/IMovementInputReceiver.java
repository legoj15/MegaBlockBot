// SPDX-License-Identifier: LGPL-3.0-or-later
// MegaBlockBot addition to the ported engine (see UPSTREAM.md). Upstream writes movement impulses and rotation
// straight into the entity it drives. An entity that implements this interface takes them as inputs instead, the way
// a client's keyboard and mouse would give them, and the engine then writes nothing into the entity itself.
package com.legoj15.megablockbot.nav.engine.api.entity;

public interface IMovementInputReceiver {
   /**
    * This tick's movement keys. {@code forward} is -1 (back) to 1 (forward), {@code strafe} -1 (right) to 1 (left),
    * the signs vanilla's {@code zza} and {@code xxa} use. Full keys are 1, as for a player.
    */
   void setMovementInput(float forward, float strafe, boolean jump, boolean sneak, boolean sprint);

   /** Where to face, in degrees, vanilla's yaw and pitch conventions. Held until changed. */
   void setLook(float yaw, float pitch);
}
