package tech.naven.patch.impl;

// Ported from tech/mixin/MixinLivingEntityRenderer.java

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import tech.naven.Naven;
import tech.naven.events.impl.EventRotationAnimation;
import tech.naven.patch.CallbackInfo;
import tech.naven.patch.api.At;
import tech.naven.patch.api.Inject;
import tech.naven.patch.api.Patch;
import tech.naven.patch.api.Redirect;

@Patch(LivingEntityRenderer.class)
public final class LivingEntityRendererPatch {
    // desc is omitted on purpose: the class also declares the erased bridge
    // extractRenderState(Entity, EntityRenderState, float). The handler parameter types select
    // the LivingEntity overload.
    @Inject(method = "extractRenderState", at = @At(At.Type.HEAD))
    public static void renderHead(LivingEntityRenderer<?, ?, ?> self, LivingEntity pEntity,
                                  LivingEntityRenderState pState, float pPartialTicks, CallbackInfo ci) {
        EventRotationAnimation.currentEntity = pEntity;
    }

    @Redirect(method = "extractRenderState",
            desc = "(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V",
            target = "net/minecraft/util/Mth", targetName = "rotLerp", targetDesc = "(FFF)F", ordinal = 0)
    public static float rotAnimationYaw(LivingEntityRenderer<?, ?, ?> self, float pDelta, float pStart, float pEnd) {
        if (EventRotationAnimation.currentEntity != Minecraft.getInstance().player) {
            return Mth.rotLerp(pDelta, pStart, pEnd);
        }

        EventRotationAnimation event = new EventRotationAnimation(pEnd, pStart, 0.0F, 0.0F);
        Naven.getInstance().getEventManager().call(event);
        return Mth.rotLerp(pDelta, event.getLastYaw(), event.getYaw());
    }

    @Redirect(method = "extractRenderState",
            desc = "(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V",
            target = "net/minecraft/world/entity/LivingEntity", targetName = "getXRot",
            targetDesc = "(F)F", ordinal = 0)
    public static float rotAnimationPitch(LivingEntityRenderer<?, ?, ?> self, LivingEntity instance,
                                          float pPartialTicks) {
        float pitch = instance.getXRot(pPartialTicks);
        if (EventRotationAnimation.currentEntity != Minecraft.getInstance().player) {
            return pitch;
        }

        EventRotationAnimation event = new EventRotationAnimation(0.0F, 0.0F, pitch, pitch);
        Naven.getInstance().getEventManager().call(event);
        return Mth.lerp(pPartialTicks, event.getLastPitch(), event.getPitch());
    }
}
