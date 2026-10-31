package tech.naven.patch.impl;

// Ported from tech/mixin/MixinItem.java (@Redirect Player.getYRot)

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import tech.naven.Naven;
import tech.naven.events.impl.EventUseItemRayTrace;
import tech.naven.patch.api.Patch;
import tech.naven.patch.api.Redirect;

@Patch(Item.class)
public final class ItemPatch {
    // getPlayerPOVHitResult invokes two Player.()F methods: getXRot() (ordinal 0) and getYRot()
    // (ordinal 1). This handler runs first, while both invocations are still present, so it keeps
    // its original ordinal. ItemXRotPatch is registered afterwards and takes the remaining call.
    @Redirect(method = "getPlayerPOVHitResult",
            desc = "(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/level/ClipContext$Fluid;)Lnet/minecraft/world/phys/BlockHitResult;",
            target = "net/minecraft/world/entity/player/Player", targetDesc = "()F", ordinal = 1)
    public static float hookRayTraceYRot(Player instance) {
        EventUseItemRayTrace event = new EventUseItemRayTrace(instance.getYRot(), instance.getXRot());
        Naven.getInstance().getEventManager().call(event);
        return event.getYaw();
    }
}
