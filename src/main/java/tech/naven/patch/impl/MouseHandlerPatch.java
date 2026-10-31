package tech.naven.patch.impl;

// Ported from tech/mixin/MixinMouseHandler.java
//
// KeyMapping.set(InputConstants.Key, boolean) is static, so the injection point has no
// receiver; the handler keeps the target instance of onButton only.

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import tech.naven.Naven;
import tech.naven.events.impl.EventMouseClick;
import tech.naven.patch.CallbackInfo;
import tech.naven.patch.api.At;
import tech.naven.patch.api.Inject;
import tech.naven.patch.api.Patch;

@Patch(MouseHandler.class)
public final class MouseHandlerPatch {
    @Inject(method = "onButton", desc = "(JLnet/minecraft/client/input/MouseButtonInfo;I)V",
            at = @At(value = At.Type.INVOKE, method = "net/minecraft/client/KeyMapping",
                    desc = "(Lcom/mojang/blaze3d/platform/InputConstants$Key;Z)V", ordinal = 0))
    public static void onPress(MouseHandler self, long window, MouseButtonInfo buttonInfo, int action,
                               CallbackInfo ci) {
        EventMouseClick event = new EventMouseClick(buttonInfo.button(), action == 0);
        Naven.getInstance().getEventManager().call(event);
    }
}
