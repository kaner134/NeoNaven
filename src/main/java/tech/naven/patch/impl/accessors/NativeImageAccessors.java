package tech.naven.patch.impl.accessors;

// Ported from tech/mixin/accessors/NativeImageAccessor.java (@Accessor("pixels"))

import com.mojang.blaze3d.platform.NativeImage;
import tech.naven.patch.Reflect;

public final class NativeImageAccessors {
    public static long getPixels(NativeImage image) {
        return Reflect.getLong(image, NativeImage.class, "pixels");
    }
}
