package tech.naven.patch.impl.accessors;

// Ported from tech/mixin/accessors/MinecraftAccessor.java (setRightClickDelay, setMissTime)

import net.minecraft.client.Minecraft;
import tech.naven.patch.Reflect;

public final class MinecraftAccessors {
    public static void setRightClickDelay(Minecraft minecraft, int value) {
        Reflect.setInt(minecraft, Minecraft.class, "rightClickDelay", value);
    }

    public static void setMissTime(Minecraft minecraft, int value) {
        Reflect.setInt(minecraft, Minecraft.class, "missTime", value);
    }
}
