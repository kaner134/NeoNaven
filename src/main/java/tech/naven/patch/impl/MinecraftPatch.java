package tech.naven.patch.impl;

// Ported from tech/mixin/MixinMinecraft.java
//
// The two @Inject("<init>") entries in the mixin both target the single constructor
// Minecraft(GameConfig); they are two anchors (TAIL and RETURN) on the same method, so
// both carry the real descriptor (Lnet/minecraft/client/main/GameConfig;)V.
//
// The @Unique instance field naven_Modern$lastFrame becomes a static field: Minecraft is
// a process-wide singleton (Minecraft.getInstance()) and the runTick anchor only ever
// touches that one instance.

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.main.GameConfig;
import net.minecraft.world.entity.Entity;
import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModFileInfo;
import net.neoforged.neoforgespi.language.IModInfo;
import tech.naven.Naven;
import tech.naven.events.api.types.EventType;
import tech.naven.events.impl.EventClick;
import tech.naven.events.impl.EventRunTicks;
import tech.naven.events.impl.EventShutdown;
import tech.naven.modules.impl.render.Glow;
import tech.naven.patch.CallbackInfo;
import tech.naven.patch.CallbackInfoReturnable;
import tech.naven.patch.api.At;
import tech.naven.patch.api.Inject;
import tech.naven.patch.api.Patch;
import tech.naven.utils.AnimationUtils;

@Patch(Minecraft.class)
public final class MinecraftPatch {
    /** Replaces the mixin's @Unique naven_Modern$lastFrame. */
    private static long navenLastFrame;

    @Inject(method = "<init>", desc = "(Lnet/minecraft/client/main/GameConfig;)V", at = @At(At.Type.TAIL))
    public static void onInit(Minecraft self, GameConfig gameConfig, CallbackInfo info) {
        Naven.modRegister();
    }

    @Inject(method = "<init>", desc = "(Lnet/minecraft/client/main/GameConfig;)V", at = @At(At.Type.RETURN))
    public static void onInitReturn(Minecraft self, GameConfig pGameConfig, CallbackInfo ci) {
        System.setProperty("java.awt.headless", "false");
        try {
            ModList.get().getMods().removeIf(modInfox -> modInfox.getModId().contains("naven"));
            List<IModFileInfo> fileInfoToRemove = new ArrayList<>();

            for (IModFileInfo fileInfo : ModList.get().getModFiles()) {
                for (IModInfo modInfo : fileInfo.getMods()) {
                    if (modInfo.getModId().contains("naven")) {
                        fileInfoToRemove.add(fileInfo);
                    }
                }
            }

            ModList.get().getModFiles().removeAll(fileInfoToRemove);
        } catch (UnsupportedOperationException ignored) {
        }
    }

    @Inject(method = "close", desc = "()V", at = @At(At.Type.HEAD))
    public static void shutdown(Minecraft self, CallbackInfo ci) {
        if (Naven.getInstance() != null && Naven.getInstance().getEventManager() != null) {
            Naven.getInstance().getEventManager().call(new EventShutdown());
        }
    }

    @Inject(method = "tick", desc = "()V", at = @At(At.Type.HEAD))
    public static void tickPre(Minecraft self, CallbackInfo ci) {
        if (Naven.getInstance() != null && Naven.getInstance().getEventManager() != null) {
            Naven.getInstance().getEventManager().call(new EventRunTicks(EventType.PRE));
        }
    }

    @Inject(method = "tick", desc = "()V", at = @At(At.Type.TAIL))
    public static void tickPost(Minecraft self, CallbackInfo ci) {
        if (Naven.getInstance() != null && Naven.getInstance().getEventManager() != null) {
            Naven.getInstance().getEventManager().call(new EventRunTicks(EventType.POST));
        }
    }

    @Inject(method = "shouldEntityAppearGlowing", desc = "(Lnet/minecraft/world/entity/Entity;)Z",
            at = @At(At.Type.RETURN), cancellable = true)
    public static void shouldEntityAppearGlowing(Minecraft self, Entity pEntity, CallbackInfoReturnable<Boolean> cir) {
        if (Glow.shouldGlow(pEntity)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "runTick", desc = "(Z)V", at = @At(At.Type.HEAD))
    public static void runTick(Minecraft self, boolean renderLevel, CallbackInfo ci) {
        long currentTime = System.nanoTime() / 1000000L;
        int deltaTime = (int) (currentTime - navenLastFrame);
        navenLastFrame = currentTime;
        AnimationUtils.delta = deltaTime;
    }

    @Inject(method = "handleKeybinds", desc = "()V",
            at = @At(value = At.Type.INVOKE, method = "net/minecraft/client/player/LocalPlayer",
                    name = "isUsingItem", desc = "()Z", ordinal = 0),
            cancellable = true)
    public static void clickEvent(Minecraft self, CallbackInfo ci) {
        if (Naven.getInstance() != null && Naven.getInstance().getEventManager() != null) {
            EventClick event = new EventClick();
            Naven.getInstance().getEventManager().call(event);
            if (event.isCancelled()) {
                ci.cancel();
            }
        }
    }
}
