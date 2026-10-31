package tech.naven.patch.impl;

// Ported from tech/mixin/MixinEntity.java
//
// The original used @ModifyArg(index = 2) on Entity.getInputVector(Vec3, float, float)
// inside moveRelative. ModifyArg does not exist in the hot-injection API, so it is
// expressed as a @Redirect of the whole static call: the handler re-invokes
// getInputVector with the other two arguments unchanged and only the yaw replaced.
// getInputVector is protected static, hence Reflect.

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import tech.naven.Naven;
import tech.naven.events.impl.EventRayTrace;
import tech.naven.events.impl.EventStrafe;
import tech.naven.events.impl.EventStuckInBlock;
import tech.naven.patch.CallbackInfo;
import tech.naven.patch.CallbackInfoReturnable;
import tech.naven.patch.Reflect;
import tech.naven.patch.api.At;
import tech.naven.patch.api.Inject;
import tech.naven.patch.api.Patch;
import tech.naven.patch.api.Redirect;
import tech.naven.utils.BlinkingPlayer;

@Patch(Entity.class)
public final class EntityPatch {
    @Inject(method = "getViewVector", desc = "(F)Lnet/minecraft/world/phys/Vec3;",
            at = @At(At.Type.RETURN), cancellable = true)
    public static void hookViewVector(Entity self, float p_20253_, CallbackInfoReturnable<Vec3> cir) {
        float pitch = self.getViewXRot(p_20253_);
        float yaw = self.getViewYRot(p_20253_);
        if (self == Minecraft.getInstance().player) {
            EventRayTrace lookEvent = new EventRayTrace(self, yaw, pitch);
            Naven.getInstance().getEventManager().call(lookEvent);
            yaw = lookEvent.yaw;
            pitch = lookEvent.pitch;
            cir.setReturnValue(self.calculateViewVector(pitch, yaw));
        }
    }

    /** Replaces @ModifyArg(index = 2) on getInputVector. */
    @Redirect(method = "moveRelative", desc = "(FLnet/minecraft/world/phys/Vec3;)V",
            target = "net/minecraft/world/entity/Entity",
            targetDesc = "(Lnet/minecraft/world/phys/Vec3;FF)Lnet/minecraft/world/phys/Vec3;", ordinal = 0)
    public static Vec3 modifyYaw(Entity self, Vec3 pos, float speed, float yaw) {
        EventStrafe strafe = new EventStrafe(yaw);
        Naven.getInstance().getEventManager().call(strafe);
        return (Vec3) Reflect.call(self, Entity.class, "getInputVector",
                new Class<?>[]{Vec3.class, float.class, float.class}, pos, speed, strafe.getYaw());
    }

    @Inject(method = "makeStuckInBlock",
            desc = "(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/phys/Vec3;)V",
            at = @At(At.Type.RETURN))
    public static void makeStuckInBlock(Entity self, BlockState pState, Vec3 pMotionMultiplier, CallbackInfo ci) {
        if (Minecraft.getInstance().player == self) {
            EventStuckInBlock event = new EventStuckInBlock(pState, pMotionMultiplier);
            Naven.getInstance().getEventManager().call(event);
            if (event.isCancelled()) {
                Reflect.set(self, Entity.class, "stuckSpeedMultiplier", Vec3.ZERO);
                return;
            }

            Reflect.set(self, Entity.class, "stuckSpeedMultiplier", event.getStuckSpeedMultiplier());
        }
    }

    // desc is deliberately omitted: push(Entity), push(Vec3) and push(double,double,double)
    // all exist, and with an explicit desc the engine matches on arity only. Without it the
    // handler parameter types pick push(Entity) unambiguously.
    @Inject(method = "push", at = @At(At.Type.HEAD), cancellable = true)
    public static void push(Entity self, Entity pEntity, CallbackInfo ci) {
        if (pEntity instanceof BlinkingPlayer) {
            ci.cancel();
        }
    }
}
