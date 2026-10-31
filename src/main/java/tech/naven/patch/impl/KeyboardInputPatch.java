package tech.naven.patch.impl;

// Ported from tech/mixin/MixinKeyboardInput.java
//
// ClientInput.keyPresses is public (direct access); ClientInput.moveVector is protected
// in another package, so it is written through Reflect.

import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import tech.naven.Naven;
import tech.naven.events.impl.EventMoveInput;
import tech.naven.patch.CallbackInfo;
import tech.naven.patch.Reflect;
import tech.naven.patch.api.At;
import tech.naven.patch.api.Inject;
import tech.naven.patch.api.Patch;

@Patch(KeyboardInput.class)
public final class KeyboardInputPatch {
    @Inject(method = "tick", desc = "()V", at = @At(At.Type.TAIL))
    public static void onTickTail(KeyboardInput self, CallbackInfo ci) {
        Input keyPresses = self.keyPresses;
        float forward = keyPresses.forward() == keyPresses.backward() ? 0.0F : (keyPresses.forward() ? 1.0F : -1.0F);
        float strafe = keyPresses.left() == keyPresses.right() ? 0.0F : (keyPresses.left() ? 1.0F : -1.0F);
        EventMoveInput eventMoveInput = new EventMoveInput(forward, strafe, keyPresses.jump(), keyPresses.shift(), 0.3);
        Naven.getInstance().getEventManager().call(eventMoveInput);
        forward = eventMoveInput.getForward();
        strafe = eventMoveInput.getStrafe();
        self.keyPresses = new Input(
                forward > 0.0F,
                forward < 0.0F,
                strafe > 0.0F,
                strafe < 0.0F,
                eventMoveInput.isJump(),
                eventMoveInput.isSneak(),
                keyPresses.sprint()
        );
        Reflect.set(self, ClientInput.class, "moveVector", new Vec2(strafe, forward));
    }
}
