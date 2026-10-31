package tech.naven.patch.impl;

// Ported from tech/mixin/MixinLivingEntity.java

import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import tech.naven.Naven;
import tech.naven.events.impl.EventFallFlying;
import tech.naven.events.impl.EventJump;
import tech.naven.events.impl.EventRotationAnimation;
import tech.naven.modules.impl.render.AntiNausea;
import tech.naven.modules.impl.render.FullBright;
import tech.naven.patch.CallbackInfoReturnable;
import tech.naven.patch.api.At;
import tech.naven.patch.api.Inject;
import tech.naven.patch.api.Patch;
import tech.naven.patch.api.Redirect;

@Patch(LivingEntity.class)
public final class LivingEntityPatch {
    // LivingEntity.()F occurs twice in jumpFromGround: getJumpPower() (ordinal 0) then getYRot() (ordinal 1).
    @Redirect(method = "jumpFromGround", desc = "()V",
            target = "net/minecraft/world/entity/LivingEntity", targetName = "getYRot",
            targetDesc = "()F", ordinal = 0)
    public static float modifyJumpYaw(LivingEntity self, LivingEntity entity) {
        EventJump event = new EventJump(entity.getYRot());
        Naven.getInstance().getEventManager().call(event);
        return event.getYaw();
    }

    @Redirect(method = "updateFallFlyingMovement",
            desc = "(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;",
            target = "net/minecraft/world/entity/LivingEntity", targetName = "getXRot",
            targetDesc = "()F", ordinal = 0)
    public static float hookModifyFallingPitch(LivingEntity self, LivingEntity instance) {
        EventFallFlying event = new EventFallFlying(instance.getXRot());
        Naven.getInstance().getEventManager().call(event);
        return event.getPitch();
    }

    @Inject(method = "hasEffect", desc = "(Lnet/minecraft/core/Holder;)Z",
            at = @At(At.Type.HEAD), cancellable = true)
    public static void hasEffect(LivingEntity self, Holder<MobEffect> pEffect, CallbackInfoReturnable<Boolean> cir) {
        if (self == Minecraft.getInstance().player) {
            FullBright fullBright = (FullBright) Naven.getInstance().getModuleManager().getModule(FullBright.class);
            if (pEffect.value() == MobEffects.NIGHT_VISION.value() && fullBright.isEnabled()) {
                cir.setReturnValue(true);
                cir.cancel();
            }

            AntiNausea antiNausea = (AntiNausea) Naven.getInstance().getModuleManager().getModule(AntiNausea.class);
            if (pEffect.value() == MobEffects.NAUSEA.value() && antiNausea.isEnabled()) {
                cir.setReturnValue(false);
                cir.cancel();
            }
        }
    }

    @Redirect(method = "tickHeadTurn", desc = "(F)V",
            target = "net/minecraft/world/entity/LivingEntity", targetName = "getYRot",
            targetDesc = "()F", ordinal = 0)
    public static float modifyHeadYaw(LivingEntity self, LivingEntity entity) {
        if (entity == Minecraft.getInstance().player) {
            EventRotationAnimation event = new EventRotationAnimation(entity.getYRot(), 0.0F, 0.0F, 0.0F);
            Naven.getInstance().getEventManager().call(event);
            return event.getYaw();
        } else {
            return entity.getYRot();
        }
    }
}
