package tech.naven.patch.impl.accessors;

// Ported from tech/mixin/accessors/GameRendererAccessor.java (@Invoker invokeGetFov)

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import tech.naven.patch.Reflect;

public final class GameRendererAccessors {
    public static float invokeGetFov(GameRenderer renderer, Camera camera, float partialTicks, boolean useFovSetting) {
        return (Float) Reflect.call(renderer, GameRenderer.class, "getFov",
                new Class<?>[]{Camera.class, float.class, boolean.class}, camera, partialTicks, useFovSetting);
    }
}
