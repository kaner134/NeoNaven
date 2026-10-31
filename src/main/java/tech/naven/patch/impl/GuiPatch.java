package tech.naven.patch.impl;

// Ported from tech/mixin/MixinGui.java
//
// The six @Shadow private fields (title, titleTime, titleFadeInTime, titleStayTime,
// titleFadeOutTime, subtitle) are read/written through Reflect.

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Team;
import tech.naven.Naven;
import tech.naven.events.api.types.EventType;
import tech.naven.events.impl.EventRenderScoreboard;
import tech.naven.events.impl.EventSetTitle;
import tech.naven.modules.impl.render.NoRender;
import tech.naven.modules.impl.render.Scoreboard;
import tech.naven.patch.CallbackInfo;
import tech.naven.patch.Reflect;
import tech.naven.patch.api.At;
import tech.naven.patch.api.Inject;
import tech.naven.patch.api.Patch;
import tech.naven.patch.api.Redirect;

@Patch(Gui.class)
public final class GuiPatch {
    @Inject(method = "displayScoreboardSidebar",
            desc = "(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/world/scores/Objective;)V",
            at = @At(At.Type.HEAD))
    public static void hookScoreboardHead(Gui self, GuiGraphics pPoseStack, Objective pObjective, CallbackInfo ci) {
        pPoseStack.pose().pushMatrix();
        Scoreboard module = (Scoreboard) Naven.getInstance().getModuleManager().getModule(Scoreboard.class);
        if (module.isEnabled()) {
            pPoseStack.pose().translate(0.0F, module.down.getCurrentValue());
        }
    }

    @Inject(method = "displayScoreboardSidebar",
            desc = "(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/world/scores/Objective;)V",
            at = @At(At.Type.RETURN))
    public static void hookScoreboardReturn(Gui self, GuiGraphics pPoseStack, Objective pObjective, CallbackInfo ci) {
        pPoseStack.pose().popMatrix();
    }

    @Redirect(method = "displayScoreboardSidebar",
            desc = "(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/world/scores/Objective;)V",
            target = "net/minecraft/client/gui/GuiGraphics",
            targetName = "drawString",
            targetDesc = "(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)V", ordinal = 2)
    public static void hookRenderScore(Gui self, GuiGraphics instance, Font pFont, Component pText, int pX, int pY,
                                       int pColor, boolean pDropShadow) {
        Scoreboard module = (Scoreboard) Naven.getInstance().getModuleManager().getModule(Scoreboard.class);
        if (!module.isEnabled() || !module.hideScore.getCurrentValue()) {
            instance.drawString(pFont, pText, pX, pY, pColor, pDropShadow);
        }
    }

    @Redirect(method = "lambda$displayScoreboardSidebar$17",
            desc = "(Lnet/minecraft/world/scores/Scoreboard;Lnet/minecraft/network/chat/numbers/NumberFormat;Lnet/minecraft/world/scores/PlayerScoreEntry;)Lnet/minecraft/client/gui/Gui$1DisplayEntry;",
            target = "net/minecraft/world/scores/PlayerTeam",
            targetName = "formatNameForTeam",
            targetDesc = "(Lnet/minecraft/world/scores/Team;Lnet/minecraft/network/chat/Component;)Lnet/minecraft/network/chat/MutableComponent;",
            ordinal = 0)
    public static MutableComponent hookScoreboardName(Gui self, Team pPlayerTeam, Component pPlayerName) {
        MutableComponent mutableComponent = PlayerTeam.formatNameForTeam(pPlayerTeam, pPlayerName);
        EventRenderScoreboard event = new EventRenderScoreboard(mutableComponent);
        Naven.getInstance().getEventManager().call(event);
        return (MutableComponent) event.getComponent();
    }

    @Redirect(method = "displayScoreboardSidebar",
            desc = "(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/world/scores/Objective;)V",
            target = "net/minecraft/world/scores/Objective", targetName = "getDisplayName",
            targetDesc = "()Lnet/minecraft/network/chat/Component;", ordinal = 0)
    public static Component hookScoreboardTitle(Gui self, Objective instance) {
        Component component = instance.getDisplayName();
        EventRenderScoreboard event = new EventRenderScoreboard(component);
        Naven.getInstance().getEventManager().call(event);
        return event.getComponent();
    }

    @Inject(method = "setTitle", desc = "(Lnet/minecraft/network/chat/Component;)V",
            at = @At(At.Type.HEAD), cancellable = true)
    public static void hookTitle(Gui self, Component pTitle, CallbackInfo ci) {
        EventSetTitle event = new EventSetTitle(EventType.TITLE, pTitle);
        Naven.getInstance().getEventManager().call(event);
        if (!event.isCancelled()) {
            Reflect.set(self, Gui.class, "title", event.getTitle());
            Reflect.setInt(self, Gui.class, "titleTime",
                    Reflect.getInt(self, Gui.class, "titleFadeInTime")
                            + Reflect.getInt(self, Gui.class, "titleStayTime")
                            + Reflect.getInt(self, Gui.class, "titleFadeOutTime"));
            ci.cancel();
        }
    }

    @Inject(method = "setSubtitle", desc = "(Lnet/minecraft/network/chat/Component;)V",
            at = @At(At.Type.RETURN), cancellable = true)
    public static void hookSubtitle(Gui self, Component pSubtitle, CallbackInfo ci) {
        EventSetTitle event = new EventSetTitle(EventType.SUBTITLE, pSubtitle);
        Naven.getInstance().getEventManager().call(event);
        if (!event.isCancelled()) {
            Reflect.set(self, Gui.class, "subtitle", event.getTitle());
            ci.cancel();
        }
    }

    @Inject(method = "renderEffects",
            desc = "(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V",
            at = @At(At.Type.HEAD), cancellable = true)
    public static void hookRenderEffects(Gui self, GuiGraphics pPoseStack, DeltaTracker pDeltaTracker, CallbackInfo ci) {
        NoRender noRender = (NoRender) Naven.getInstance().getModuleManager().getModule(NoRender.class);
        if (noRender.isEnabled() && noRender.disableEffects.getCurrentValue()) {
            ci.cancel();
        }
    }
}
