package tech.naven.patch.impl;

// Ported from tech/mixin/MixinPlayerTabOverlay.java (footer)
//
// render() calls Font.split twice — header (ordinal 0) then footer (ordinal 1) — and both calls
// have the same owner and descriptor. The hot injection API can only tell them apart by ordinal,
// and every applied redirect removes its invocation, so this handler is registered BEFORE
// PlayerTabOverlayPatch: it still sees both calls and keeps its original ordinal 1. If the order
// were reversed this binding would fail loudly instead of hooking the header.

import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import tech.naven.Naven;
import tech.naven.events.api.types.EventType;
import tech.naven.events.impl.EventRenderTabOverlay;
import tech.naven.patch.api.Patch;
import tech.naven.patch.api.Redirect;

@Patch(PlayerTabOverlay.class)
public final class PlayerTabOverlayFooterPatch {
    @Redirect(method = "render",
            desc = "(Lnet/minecraft/client/gui/GuiGraphics;ILnet/minecraft/world/scores/Scoreboard;Lnet/minecraft/world/scores/Objective;)V",
            target = "net/minecraft/client/gui/Font",
            targetName = "split",
            targetDesc = "(Lnet/minecraft/network/chat/FormattedText;I)Ljava/util/List;", ordinal = 1)
    public static List<FormattedCharSequence> hookFooter(PlayerTabOverlay self, Font instance, FormattedText pText,
                                                         int pMaxWidth) {
        Component component = (Component) pText;
        EventRenderTabOverlay event = new EventRenderTabOverlay(EventType.FOOTER, component);
        Naven.getInstance().getEventManager().call(event);
        return instance.split(event.getComponent(), pMaxWidth);
    }
}
