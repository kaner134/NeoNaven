package tech.naven.patch.impl;

// Ported from tech/mixin/MixinEntityRenderer.java

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import tech.naven.Naven;
import tech.naven.modules.impl.render.NameProtect;
import tech.naven.modules.impl.render.NameTags;
import tech.naven.patch.CallbackInfo;
import tech.naven.patch.api.At;
import tech.naven.patch.api.Inject;
import tech.naven.patch.api.Patch;

@Patch(EntityRenderer.class)
public final class EntityRendererPatch {
    @Inject(method = "extractRenderState",
            desc = "(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/entity/state/EntityRenderState;F)V",
            at = @At(At.Type.RETURN))
    public static void naven$hideOrProtectNameTag(EntityRenderer<?, ?> self, Entity pEntity, EntityRenderState pState,
                                                  float pPartialTick, CallbackInfo ci) {
        if (pEntity instanceof Player && Naven.getInstance().getModuleManager().getModule(NameTags.class).isEnabled()) {
            pState.nameTag = null;
            return;
        }

        pState.nameTag = NameProtect.hookNameTag(pEntity, pState.nameTag);
    }
}
