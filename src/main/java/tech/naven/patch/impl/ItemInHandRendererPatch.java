package tech.naven.patch.impl;

// Ported from tech/mixin/MixinItemInHandRenderer.java (main-hand redirect)

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import tech.naven.Naven;
import tech.naven.events.impl.EventUpdateHeldItem;
import tech.naven.patch.api.Patch;
import tech.naven.patch.api.Redirect;

@Patch(ItemInHandRenderer.class)
public final class ItemInHandRendererPatch {
    // tick() calls LocalPlayer.getMainHandItem() (ordinal 0) then getOffhandItem() (ordinal 1).
    // ItemInHandRendererOffHandPatch is registered first and consumes the second call, so this
    // handler sees a single remaining match (ordinal 0).
    @Redirect(method = "tick", desc = "()V",
            target = "net/minecraft/client/player/LocalPlayer",
            targetName = "getMainHandItem",
            targetDesc = "()Lnet/minecraft/world/item/ItemStack;", ordinal = 0)
    public static ItemStack hookMainHand(ItemInHandRenderer self, LocalPlayer player) {
        EventUpdateHeldItem event = new EventUpdateHeldItem(InteractionHand.MAIN_HAND, player.getMainHandItem());
        if (player == Minecraft.getInstance().player) {
            Naven.getInstance().getEventManager().call(event);
        }

        return event.getItem();
    }
}
