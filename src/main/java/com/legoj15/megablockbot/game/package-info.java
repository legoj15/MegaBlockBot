/**
 * The bot's Minecraft-facing code. Compiled only on a node whose {@code bot_enabled} property is true; every other
 * node builds the skeleton without it. Reached from common code through one {@code //? if bot} call to
 * {@link com.legoj15.megablockbot.game.GameBootstrap#init()}. Like all common code it names no loader type: loader
 * services come from {@link com.legoj15.megablockbot.platform.Platform}. See docs/platform-layer.md.
 */
package com.legoj15.megablockbot.game;
