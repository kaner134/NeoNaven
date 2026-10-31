package tech.naven.patch.impl;

// Ported from tech/mixin/MixinItem.java (@Redirect Player.getXRot)
//
// getPlayerPOVHitResult invokes Player.getXRot() (ordinal 0) and then Player.getYRot()
// (ordinal 1). ItemPatch is registered first and consumes the getYRot() invocation, so by the
// time this handler is applied the getXRot() call is the only remaining match (ordinal 0).
// If the two patch classes were applied in the other order, this one still picks getXRot() by
// ordinal 0 and the other one fails loudly instead of silently hooking the wrong call.

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import tech.naven.Naven;
import tech.naven.events.impl.EventUseItemRayTrace;
import tech.naven.patch.api.Patch;
import tech.naven.patch.api.Redirect;

@Patch(Item.class)
public final class ItemXRotPatch {
    @Redirect(method = "getPlayerPOVHitResult",
            desc = "(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/level/ClipContext$Fluid;)Lnet/minecraft/world/phys/BlockHitResult;",
            target = "net/minecraft/world/entity/player/Player", targetDesc = "()F", ordinal = 0)
    public static float hookRayTraceXRot(Player instance) {
        EventUseItemRayTrace event = new EventUseItemRayTrace(instance.getYRot(), instance.getXRot());
        Naven.getInstance().getEventManager().call(event);
        return event.getPitch();
    }
}
