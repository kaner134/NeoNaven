package tech.naven.patch.impl;

// Ported from tech/mixin/MixinFogRenderer.java

import net.minecraft.client.renderer.fog.environment.BlindnessFogEnvironment;
import net.minecraft.client.renderer.fog.environment.MobEffectFogEnvironment;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.material.FogType;
import tech.naven.Naven;
import tech.naven.modules.impl.render.AntiBlindness;
import tech.naven.patch.CallbackInfoReturnable;
import tech.naven.patch.api.At;
import tech.naven.patch.api.Inject;
import tech.naven.patch.api.Patch;

@Patch(MobEffectFogEnvironment.class)
public final class MobEffectFogEnvironmentPatch {
    @Inject(method = "isApplicable",
            desc = "(Lnet/minecraft/world/level/material/FogType;Lnet/minecraft/world/entity/Entity;)Z",
            at = @At(At.Type.HEAD), cancellable = true)
    public static void naven$isApplicable(MobEffectFogEnvironment self, FogType fogType, Entity entity,
                                          CallbackInfoReturnable<Boolean> cir) {
        if (self instanceof BlindnessFogEnvironment
                && Naven.getInstance().getModuleManager().getModule(AntiBlindness.class).isEnabled()) {
            cir.setReturnValue(false);
        }
    }
}
