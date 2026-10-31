package tech.naven.patch.impl;

// Ported from tech/mixin/MixinCamera.java

import net.minecraft.client.Camera;
import tech.naven.Naven;
import tech.naven.modules.impl.render.ViewClip;
import tech.naven.patch.CallbackInfoReturnable;
import tech.naven.patch.api.At;
import tech.naven.patch.api.Inject;
import tech.naven.patch.api.Patch;

@Patch(Camera.class)
public final class CameraPatch {
    @Inject(method = "getMaxZoom", desc = "(F)F", at = @At(At.Type.HEAD), cancellable = true)
    public static void getMaxZoom(Camera self, float pStartingDistance, CallbackInfoReturnable<Float> cir) {
        if (Naven.getInstance() != null && Naven.getInstance().getModuleManager() != null) {
            ViewClip module = (ViewClip) Naven.getInstance().getModuleManager().getModule(ViewClip.class);
            if (module.isEnabled()) {
                cir.setReturnValue((float) ((double) pStartingDistance * (double) module.scale.getCurrentValue()
                        * (double) module.personViewAnimation.value / 100.0));
                cir.cancel();
            }
        }
    }
}
