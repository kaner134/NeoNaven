package tech.naven.patch.impl;

// Ported from tech/mixin/MixinPlayer.java

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import tech.naven.Naven;
import tech.naven.events.impl.EventAttackSlowdown;
import tech.naven.events.impl.EventAttackYaw;
import tech.naven.events.impl.EventStayingOnGroundSurface;
import tech.naven.patch.CallbackInfoReturnable;
import tech.naven.patch.api.At;
import tech.naven.patch.api.Inject;
import tech.naven.patch.api.Patch;
import tech.naven.patch.api.Redirect;

@Patch(Player.class)
public final class PlayerPatch {
    // Player.()F occurs 7 times in attack: getSpeed() (ordinal 0) then getYRot() x6 (ordinal 1..).
    @Redirect(method = "attack", desc = "(Lnet/minecraft/world/entity/Entity;)V",
            target = "net/minecraft/world/entity/player/Player", targetDesc = "()F", ordinal = 1)
    public static float hookFixRotation(Player self, Player instance) {
        EventAttackYaw event = new EventAttackYaw(instance.getYRot());
        Naven.getInstance().getEventManager().call(event);
        return event.getYaw();
    }

    @Redirect(method = "attack", desc = "(Lnet/minecraft/world/entity/Entity;)V",
            target = "net/minecraft/world/entity/player/Player",
            targetDesc = "(Lnet/minecraft/world/phys/Vec3;)V", ordinal = 0)
    public static void hookSetDeltaMovement(Player self, Player instance, Vec3 vec3) {
        EventAttackSlowdown event = new EventAttackSlowdown();
        Naven.getInstance().getEventManager().call(event);
        if (!event.isCancelled()) {
            instance.setDeltaMovement(vec3);
        }
    }

    @Redirect(method = "attack", desc = "(Lnet/minecraft/world/entity/Entity;)V",
            target = "net/minecraft/world/entity/player/Player", targetDesc = "(Z)V", ordinal = 0)
    public static void hookSetSprinting(Player self, Player instance, boolean sprinting) {
        EventAttackSlowdown event = new EventAttackSlowdown();
        Naven.getInstance().getEventManager().call(event);
        if (!event.isCancelled()) {
            instance.setSprinting(sprinting);
        }
    }

    @Inject(method = "isStayingOnGroundSurface", desc = "()Z", at = @At(At.Type.RETURN), cancellable = true)
    public static void isStayingOnGroundSurface(Player self, CallbackInfoReturnable<Boolean> info) {
        Object returnValue = info.getReturnValue();
        EventStayingOnGroundSurface event = new EventStayingOnGroundSurface((Boolean) returnValue);
        Naven.getInstance().getEventManager().call(event);
        info.setReturnValue(event.isStay());
    }
}
