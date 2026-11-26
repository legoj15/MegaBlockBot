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

package com.legoj15.megablockbot.nav.engine.process;

import com.legoj15.megablockbot.nav.engine.api.IBaritone;
import com.legoj15.megablockbot.nav.engine.api.process.IBaritoneProcess;
import com.legoj15.megablockbot.nav.engine.api.process.PathingCommand;
import com.legoj15.megablockbot.nav.engine.api.process.PathingCommandType;

public class ExecControlProcess implements IBaritoneProcess {
   boolean paused;

   public static IBaritoneProcess registerProcess(IBaritone baritone) {
      ExecControlProcess proc = new ExecControlProcess();
      baritone.getPathingControlManager().registerProcess(proc);
      return proc;
   }

   public boolean isPaused() {
      return this.paused;
   }

   public void setPaused(boolean paused) {
      this.paused = paused;
   }

   @Override
   public boolean isActive() {
      return this.paused;
   }

   @Override
   public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
      return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
   }

   @Override
   public boolean isTemporary() {
      return true;
   }

   @Override
   public void onLostControl() {
   }

   @Override
   public double priority() {
      return 0.0;
   }

   @Override
   public String displayName0() {
      return "Pause/Resume Commands";
   }
}
