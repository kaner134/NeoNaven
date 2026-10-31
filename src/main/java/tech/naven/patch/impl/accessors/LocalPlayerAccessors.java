package tech.naven.patch.impl.accessors;

// Ported from tech/mixin/accessors/LocalPlayerAccessor.java (5 @Accessor)

import net.minecraft.client.player.LocalPlayer;
import tech.naven.patch.Reflect;

public final class LocalPlayerAccessors {
    public static boolean isWasSprinting(LocalPlayer player) {
        return Reflect.getBoolean(player, LocalPlayer.class, "wasSprinting");
    }

    public static float getYRotLast(LocalPlayer player) {
        return Reflect.getFloat(player, LocalPlayer.class, "yRotLast");
    }

    public static float getXRotLast(LocalPlayer player) {
        return Reflect.getFloat(player, LocalPlayer.class, "xRotLast");
    }

    public static void setXRotLast(LocalPlayer player, float value) {
        Reflect.setFloat(player, LocalPlayer.class, "xRotLast", value);
    }

    public static void setYRotLast(LocalPlayer player, float value) {
        Reflect.setFloat(player, LocalPlayer.class, "yRotLast", value);
    }
}
