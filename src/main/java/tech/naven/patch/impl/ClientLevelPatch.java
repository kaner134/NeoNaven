package tech.naven.patch.impl;

// Ported from tech/mixin/MixinClientLevel.java

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import tech.naven.Naven;
import tech.naven.patch.api.Patch;
import tech.naven.patch.api.Redirect;

@Patch(ClientLevel.class)
public final class ClientLevelPatch {
    // Entity.()V occurs twice in the method body: setOldPosAndRot() then tick().
    // The injected invocation is selected by owner + descriptor only, so tick() is ordinal 1.
    @Redirect(method = "tickNonPassenger", desc = "(Lnet/minecraft/world/entity/Entity;)V",
            target = "net/minecraft/world/entity/Entity", targetDesc = "()V", ordinal = 1)
    public static void hookSkipTicks(ClientLevel self, Entity instance) {
        if (Naven.skipTicks > 0 && instance == Minecraft.getInstance().player) {
            Naven.skipTicks--;
            return;
        }

        if (!Naven.skipTasks.isEmpty() && instance == Minecraft.getInstance().player) {
            Runnable task = Naven.skipTasks.poll();
            if (task != null) {
                task.run();
            }
        } else {
            instance.tick();
        }
    }
}
