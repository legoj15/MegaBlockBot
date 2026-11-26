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

package com.legoj15.megablockbot.nav.engine.utils;

import com.legoj15.megablockbot.nav.NavRuntime;
import com.legoj15.megablockbot.nav.engine.api.Settings;
import com.legoj15.megablockbot.nav.engine.api.utils.SettingsUtil;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SettingsLoader {
   private static final Pattern SETTING_PATTERN = Pattern.compile("^(?<setting>[^ ]+) +(?<value>.+)");
   private static Path settingsPath() {
      Path configDir = DirUtil.getConfigDir();
      return configDir == null ? null : configDir.resolve("settings.txt");
   }

   public static void readAndApply(Settings settings) {
      Path settingsPath = settingsPath();
      if (settingsPath == null || !Files.isRegularFile(settingsPath)) {
         NavRuntime.LOGGER.debug("No navigation settings file at {}; using defaults", settingsPath);
         return;
      }

      try {
         Files.lines(settingsPath).filter(line -> !line.trim().isEmpty() && !isComment(line)).forEach(line -> {
            Matcher matcher = SETTING_PATTERN.matcher(line);
            if (!matcher.matches()) {
               NavRuntime.LOGGER.error("Invalid syntax in setting file: " + line);
            } else {
               String settingName = matcher.group("setting").toLowerCase();
               String settingValue = matcher.group("value");

               try {
                  SettingsUtil.parseAndApply(settings, settingName, settingValue);
               } catch (Exception var6) {
                  NavRuntime.LOGGER.error("Unable to parse line " + line, var6);
               }
            }
         });
      } catch (Exception var5) {
         NavRuntime.LOGGER.error("Exception while reading Automatone settings, some settings may be reset to default values!", var5);
      }
   }

   private static boolean isComment(String line) {
      return line.startsWith("#") || line.startsWith("//");
   }

   public static synchronized void save(Settings settings) {
      Path settingsPath = settingsPath();
      if (settingsPath == null) {
         NavRuntime.LOGGER.error("Cannot save navigation settings: no config directory was set");
         return;
      }

      try {
         Files.createDirectories(settingsPath.getParent());
      } catch (IOException var7) {
         NavRuntime.LOGGER.error("Cannot create the navigation config directory", var7);
         return;
      }

      try (BufferedWriter out = Files.newBufferedWriter(settingsPath)) {
         for (Settings.Setting<?> setting : SettingsUtil.modifiedSettings(settings)) {
            out.write(SettingsUtil.settingToString(setting) + "\n");
         }
      } catch (Exception var6) {
         NavRuntime.LOGGER.error("Exception thrown while saving Automatone settings!", var6);
      }
   }
}
