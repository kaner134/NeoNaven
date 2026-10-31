package tech.naven.patch;

import tech.naven.patch.impl.AbstractClientPlayerPatch;
import tech.naven.patch.impl.CameraPatch;
import tech.naven.patch.impl.ChatComponentPatch;
import tech.naven.patch.impl.ClientLevelPatch;
import tech.naven.patch.impl.ClientPacketListenerPatch;
import tech.naven.patch.impl.ConnectionPatch;
import tech.naven.patch.impl.DeltaTrackerTimerPatch;
import tech.naven.patch.impl.EntityPatch;
import tech.naven.patch.impl.EntityRendererPatch;
import tech.naven.patch.impl.FontPatch;
import tech.naven.patch.impl.GameRendererPatch;
import tech.naven.patch.impl.GuiGraphicsPatch;
import tech.naven.patch.impl.GuiPatch;
import tech.naven.patch.impl.ItemInHandRendererOffHandPatch;
import tech.naven.patch.impl.ItemInHandRendererPatch;
import tech.naven.patch.impl.ItemPatch;
import tech.naven.patch.impl.ItemXRotPatch;
import tech.naven.patch.impl.KeyboardHandlerPatch;
import tech.naven.patch.impl.KeyboardInputPatch;
import tech.naven.patch.impl.LivingEntityPatch;
import tech.naven.patch.impl.LivingEntityRendererPatch;
import tech.naven.patch.impl.LocalPlayerPatch;
import tech.naven.patch.impl.MinecraftPatch;
import tech.naven.patch.impl.MobEffectFogEnvironmentPatch;
import tech.naven.patch.impl.MouseHandlerPatch;
import tech.naven.patch.impl.MultiPlayerGameModePatch;
import tech.naven.patch.impl.PacketUtilsPatch;
import tech.naven.patch.impl.PlayerPatch;
import tech.naven.patch.impl.PlayerTabOverlayFooterPatch;
import tech.naven.patch.impl.PlayerTabOverlayPatch;
import tech.naven.patch.impl.ProjectileUtilPatch;

/**
 * Registry of every runtime patch class, in application order.
 *
 * <p>The order matters for the three targets that have two redirects on the same owner and
 * descriptor (ItemInHandRenderer.tick, PlayerTabOverlay.render, Item.getPlayerPOVHitResult):
 * a redirect removes the invocation it replaces, so the handler that keeps the higher ordinal
 * has to be applied while both invocations are still there. Those pairs are listed explicit
 * first, primary second, and each of the two classes documents why.</p>
 */
public final class Patches {
    private Patches() {
    }

    public static Class<?>[] all() {
        return new Class<?>[] {
                // Minecraft
                MinecraftPatch.class,
                // client.player
                AbstractClientPlayerPatch.class,
                LocalPlayerPatch.class,
                KeyboardInputPatch.class,
                KeyboardHandlerPatch.class,
                MouseHandlerPatch.class,
                // client.renderer
                CameraPatch.class,
                GameRendererPatch.class,
                EntityRendererPatch.class,
                LivingEntityRendererPatch.class,
                ItemInHandRendererOffHandPatch.class,
                ItemInHandRendererPatch.class,
                MobEffectFogEnvironmentPatch.class,
                // client.gui
                GuiPatch.class,
                GuiGraphicsPatch.class,
                ChatComponentPatch.class,
                PlayerTabOverlayFooterPatch.class,
                PlayerTabOverlayPatch.class,
                // client / networking
                ClientLevelPatch.class,
                ClientPacketListenerPatch.class,
                ConnectionPatch.class,
                MultiPlayerGameModePatch.class,
                PacketUtilsPatch.class,
                // world
                EntityPatch.class,
                LivingEntityPatch.class,
                PlayerPatch.class,
                ItemPatch.class,
                ItemXRotPatch.class,
                ProjectileUtilPatch.class,
                // rendering utilities
                FontPatch.class,
                DeltaTrackerTimerPatch.class
        };
    }
}
