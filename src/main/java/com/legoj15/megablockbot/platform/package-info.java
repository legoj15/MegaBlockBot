/**
 * The platform layer: loader-neutral contracts and the {@link com.legoj15.megablockbot.platform.Platform} binder.
 * Contracts here reference only the JDK, vanilla Minecraft and other {@code platform} types, never a loader type.
 * Loader code lives in {@code platform/<loader>/}, each file's body wrapped in a {@code //? if <loader>} block.
 * Apart from those files and the binder holders, directives are about Minecraft versions only. See
 * docs/platform-layer.md.
 */
package com.legoj15.megablockbot.platform;
