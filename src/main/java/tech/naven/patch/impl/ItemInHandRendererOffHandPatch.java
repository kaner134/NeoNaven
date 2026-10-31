package tech.naven.patch.impl;

// Ported from tech/mixin/MixinItemInHandRenderer.java (off-hand redirect)
//
// ItemInHandRenderer.tick() calls LocalPlayer.getMainHandItem() (ordinal 0) and then
// LocalPlayer.getOffhandItem() (ordinal 1); both invocations have the same owner and descriptor
// and are only distinguishable by ordinal. This handler is registered BEFORE
// ItemInHandRendererPatch so that both calls are still present and its original ordinal 1 is
// correct. If the order were reversed this binding would fail loudly instead of hooking the
// wrong hand.

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
public final class ItemInHandRendererOffHandPatch {
    @Redirect(method = "tick", desc = "()V",
            target = "net/minecraft/client/player/LocalPlayer",
            targetDesc = "()Lnet/minecraft/world/item/ItemStack;", ordinal = 1)
    public static ItemStack hookOffHand(ItemInHandRenderer self, LocalPlayer player) {
        EventUpdateHeldItem event = new EventUpdateHeldItem(InteractionHand.OFF_HAND, player.getOffhandItem());
        if (player == Minecraft.getInstance().player) {
            Naven.getInstance().getEventManager().call(event);
        }

        return event.getItem();
    }
}
