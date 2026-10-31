package tech.naven.patch.impl;

// Ported from tech/mixin/MixinKeyboardHandler.java

import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.KeyEvent;
import tech.naven.Naven;
import tech.naven.events.impl.EventKey;
import tech.naven.patch.CallbackInfo;
import tech.naven.patch.api.At;
import tech.naven.patch.api.Inject;
import tech.naven.patch.api.Patch;

@Patch(KeyboardHandler.class)
public final class KeyboardHandlerPatch {
    @Inject(method = "keyPress", desc = "(JILnet/minecraft/client/input/KeyEvent;)V", at = @At(At.Type.HEAD))
    public static void onKeyPress(KeyboardHandler self, long pWindowPointer, int pAction, KeyEvent pEvent,
                                  CallbackInfo ci) {
        int key = pEvent.key();
        if (key != -1 && Naven.getInstance() != null && Naven.getInstance().getEventManager() != null) {
            Naven.getInstance().getEventManager().call(new EventKey(key, pAction != 0));
        }
    }
}
