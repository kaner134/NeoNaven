package tech.naven.patch.impl;

// Ported from tech/mixin/MixinAbstractClientPlayer.java

import net.minecraft.client.player.AbstractClientPlayer;
import tech.naven.Naven;
import tech.naven.events.impl.EventUpdateFoV;
import tech.naven.patch.CallbackInfoReturnable;
import tech.naven.patch.api.At;
import tech.naven.patch.api.Inject;
import tech.naven.patch.api.Patch;

@Patch(AbstractClientPlayer.class)
public final class AbstractClientPlayerPatch {
    @Inject(method = "getFieldOfViewModifier", desc = "(ZF)F", at = @At(At.Type.RETURN), cancellable = true)
    public static void hookFoV(AbstractClientPlayer self, boolean useFovSetting, float partialTick,
                               CallbackInfoReturnable<Float> cir) {
        Float returnValue = cir.getReturnValue();
        EventUpdateFoV event = new EventUpdateFoV(returnValue);
        Naven.getInstance().getEventManager().call(event);
        cir.setReturnValue(event.getFov());
    }
}
