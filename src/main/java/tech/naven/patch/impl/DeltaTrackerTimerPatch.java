package tech.naven.patch.impl;

// Ported from tech/mixin/MixinTimer.java
//
// Every shadowed field is per-Timer state, so the handler reads and writes the fields of
// the Timer instance it is handed; nothing is cached in a static.

import net.minecraft.client.DeltaTracker;
import tech.naven.Naven;
import tech.naven.patch.CallbackInfoReturnable;
import tech.naven.patch.Reflect;
import tech.naven.patch.api.At;
import tech.naven.patch.api.Inject;
import tech.naven.patch.api.Patch;

@Patch(DeltaTracker.Timer.class)
public final class DeltaTrackerTimerPatch {
    @Inject(method = "advanceTime", desc = "(JZ)I", at = @At(At.Type.HEAD), cancellable = true)
    public static void beginRenderTickHook(DeltaTracker.Timer self, long timeMillis, boolean advanceGameTime,
                                           CallbackInfoReturnable<Integer> cir) {
        Class<?> type = DeltaTracker.Timer.class;
        if (Naven.TICK_TIMER != 1.0F) {
            Reflect.setFloat(self, type, "realtimeDeltaTicks",
                    (float) (timeMillis - Reflect.getLong(self, type, "lastUiMs"))
                            / Reflect.getFloat(self, type, "msPerTick"));
            Reflect.setLong(self, type, "lastUiMs", timeMillis);
            if (!advanceGameTime) {
                cir.setReturnValue(0);
            } else {
                Reflect.setFloat(self, type, "deltaTicks",
                        (float) (timeMillis - Reflect.getLong(self, type, "lastMs"))
                                / Reflect.getFloat(self, type, "msPerTick") * Naven.TICK_TIMER);
                Reflect.setLong(self, type, "lastMs", timeMillis);
                float residual = Reflect.getFloat(self, type, "deltaTickResidual")
                        + Reflect.getFloat(self, type, "deltaTicks");
                Reflect.setFloat(self, type, "deltaTickResidual", residual);
                int i = (int) residual;
                Reflect.setFloat(self, type, "deltaTickResidual", residual - (float) i);
                cir.setReturnValue(i);
            }
        }
    }
}
