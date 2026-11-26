package com.legoj15.megablockbot.game.mixin;

import com.legoj15.megablockbot.game.body.BodyAdvancements;
import com.legoj15.megablockbot.game.body.BotPlayer;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(PlayerAdvancements.class)
abstract class PlayerAdvancementsBodyMixin {
    @Shadow
    private ServerPlayer player;

    /**
     * Without it every advancement a body earns is announced in chat ("Bot1 has made the advancement ..."), which
     * a bot working through a long goal would do all day. The announcement is made inside the callback this call
     * hands the display to, and the callback also fires NeoForge's earned event, so the callback has to run: the
     * body's copy of the display just says "do not announce".
     */
    @Redirect(method = "award", at = @At(value = "INVOKE", target = "Ljava/util/Optional;ifPresent(Ljava/util/function/Consumer;)V"))
    private void megablockbot$awardQuietly(Optional<DisplayInfo> display, Consumer<DisplayInfo> action) {
        if (player instanceof BotPlayer) display = display.map(BodyAdvancements::withoutChatAnnouncement);
        display.ifPresent(action);
    }
}
