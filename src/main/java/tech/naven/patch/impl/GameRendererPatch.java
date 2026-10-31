package tech.naven.patch.impl;

// Ported from tech/mixin/MixinGameRenderer.java
//
// The @Unique field navenGuiProjection (CachedOrthoProjectionMatrixBuffer) was declared
// but never read or written by the mixin; it carried no state and is not ported.

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import tech.naven.Naven;
import tech.naven.events.impl.EventRender;
import tech.naven.events.impl.EventRender2D;
import tech.naven.events.impl.EventRenderAfterWorld;
import tech.naven.modules.impl.render.FullBright;
import tech.naven.modules.impl.render.MotionBlur;
import tech.naven.modules.impl.render.NoHurtCam;
import tech.naven.modules.impl.render.PostProcess;
import tech.naven.patch.CallbackInfo;
import tech.naven.patch.CallbackInfoReturnable;
import tech.naven.patch.Reflect;
import tech.naven.patch.api.At;
import tech.naven.patch.api.Inject;
import tech.naven.patch.api.Patch;

@Patch(GameRenderer.class)
public final class GameRendererPatch {
    @Inject(method = "renderLevel", desc = "(Lnet/minecraft/client/DeltaTracker;)V", at = @At(At.Type.TAIL))
    public static void onRenderWorldTail(GameRenderer self, DeltaTracker deltaTracker, CallbackInfo ci) {
        PoseStack stack = new PoseStack();
        Camera mainCamera = Reflect.get(self, GameRenderer.class, "mainCamera");
        Quaternionf rotation = mainCamera.rotation().conjugate(new Quaternionf());
        stack.mulPose(new Matrix4f().rotation(rotation));
        Naven.getInstance().getEventManager()
                .call(new EventRender(deltaTracker.getGameTimeDeltaPartialTick(true), stack));
        Naven.getInstance().getEventManager().call(new EventRenderAfterWorld());
    }

    /** getNightVisionScale is static: no target instance. */
    @Inject(method = "getNightVisionScale", desc = "(Lnet/minecraft/world/entity/LivingEntity;F)F",
            at = @At(At.Type.HEAD), cancellable = true)
    public static void getNightVisionScale(LivingEntity pLivingEntity, float pNanoTime,
                                           CallbackInfoReturnable<Float> cir) {
        FullBright module = (FullBright) Naven.getInstance().getModuleManager().getModule(FullBright.class);
        if (module.isEnabled()) {
            cir.setReturnValue(module.brightness.getCurrentValue());
            cir.cancel();
        }
    }

    @Inject(method = "render", desc = "(Lnet/minecraft/client/DeltaTracker;Z)V",
            at = @At(value = At.Type.INVOKE, method = "net/minecraft/client/gui/Gui",
                    name = "render",
                    desc = "(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V", ordinal = 0))
    public static void injectRender2DEvent(GameRenderer self, DeltaTracker deltaTracker, boolean renderLevel,
                                           CallbackInfo ci) {
        Minecraft minecraft = Reflect.get(self, GameRenderer.class, "minecraft");
        GuiRenderState guiRenderState = Reflect.get(self, GameRenderer.class, "guiRenderState");
        GuiGraphics guiGraphics = new GuiGraphics(minecraft, guiRenderState);
        PoseStack stack = new PoseStack();
        Naven.getInstance().getEventManager().call(new EventRender2D(stack, guiGraphics));

        if (renderLevel) {
            PostProcess postProcess = (PostProcess) Naven.getInstance().getModuleManager().getModule(PostProcess.class);
            if (postProcess != null && postProcess.isEnabled()) {
                postProcess.applyHudPostProcess(stack);
            }
        }
    }

    @Inject(method = "render", desc = "(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At(At.Type.TAIL))
    public static void navenMotionBlurTail(GameRenderer self, DeltaTracker deltaTracker, boolean renderLevel,
                                           CallbackInfo ci) {
        MotionBlur module = (MotionBlur) Naven.getInstance().getModuleManager().getModule(MotionBlur.class);
        if (module != null) {
            module.applyMotionBlur(renderLevel);
        }
    }

    @Inject(method = "bobHurt", desc = "(Lcom/mojang/blaze3d/vertex/PoseStack;F)V",
            at = @At(At.Type.HEAD), cancellable = true)
    public static void bobHurt(GameRenderer self, PoseStack pMatrixStack, float pPartialTicks, CallbackInfo ci) {
        NoHurtCam module = (NoHurtCam) Naven.getInstance().getModuleManager().getModule(NoHurtCam.class);
        if (module.isEnabled()) {
            ci.cancel();
        }
    }
}
