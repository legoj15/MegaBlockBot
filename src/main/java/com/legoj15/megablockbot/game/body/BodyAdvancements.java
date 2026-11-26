package com.legoj15.megablockbot.game.body;

import net.minecraft.advancements.DisplayInfo;

/** The advancement side of the "advancements are kept but not announced" decision; see the mixin that uses it. */
public final class BodyAdvancements {
    private BodyAdvancements() {}

    /**
     * The same advancement display with the chat announcement switched off. The body still earns the advancement and
     * every event around it still fires; only the "X has made the advancement" broadcast is not sent.
     */
    public static DisplayInfo withoutChatAnnouncement(DisplayInfo display) {
        if (!display.shouldAnnounceChat()) return display;
        DisplayInfo quiet = new DisplayInfo(display.getIcon(), display.getTitle(), display.getDescription(), display.getBackground(),
            display.getType(), display.shouldShowToast(), false, display.isHidden());
        quiet.setLocation(display.getX(), display.getY());
        return quiet;
    }
}
