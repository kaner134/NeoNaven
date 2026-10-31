package tech.naven.patch.impl;

// Ported from tech/mixin/MixinPlayerTabOverlay.java (header + name)

import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import tech.naven.Naven;
import tech.naven.events.api.types.EventType;
import tech.naven.events.impl.EventRenderTabOverlay;
import tech.naven.patch.api.Patch;
import tech.naven.patch.api.Redirect;

@Patch(PlayerTabOverlay.class)
public final class PlayerTabOverlayPatch {
    // render() calls Font.split twice: the header (ordinal 0) and the footer (ordinal 1).
    // PlayerTabOverlayFooterPatch is registered first and consumes the footer call, so this
    // handler sees a single remaining match (ordinal 0).
    @Redirect(method = "render",
            desc = "(Lnet/minecraft/client/gui/GuiGraphics;ILnet/minecraft/world/scores/Scoreboard;Lnet/minecraft/world/scores/Objective;)V",
            target = "net/minecraft/client/gui/Font",
            targetDesc = "(Lnet/minecraft/network/chat/FormattedText;I)Ljava/util/List;", ordinal = 0)
    public static List<FormattedCharSequence> hookHeader(PlayerTabOverlay self, Font instance, FormattedText pText,
                                                         int pMaxWidth) {
        Component component = (Component) pText;
        EventRenderTabOverlay event = new EventRenderTabOverlay(EventType.HEADER, component);
        Naven.getInstance().getEventManager().call(event);
        return instance.split(event.getComponent(), pMaxWidth);
    }

    @Redirect(method = "render",
            desc = "(Lnet/minecraft/client/gui/GuiGraphics;ILnet/minecraft/world/scores/Scoreboard;Lnet/minecraft/world/scores/Objective;)V",
            target = "net/minecraft/client/gui/components/PlayerTabOverlay",
            targetDesc = "(Lnet/minecraft/client/multiplayer/PlayerInfo;)Lnet/minecraft/network/chat/Component;",
            ordinal = 0)
    public static Component hookName(PlayerTabOverlay self, PlayerTabOverlay instance, PlayerInfo pPlayerInfo) {
        Component nameForDisplay = instance.getNameForDisplay(pPlayerInfo);
        EventRenderTabOverlay event = new EventRenderTabOverlay(EventType.NAME, nameForDisplay);
        Naven.getInstance().getEventManager().call(event);
        return event.getComponent();
    }
}
