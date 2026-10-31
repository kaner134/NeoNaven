package tech.naven.patch.impl.accessors;

// Ported from tech/mixin/accessors/LivingEntityAccessor.java (@Accessor setNoJumpDelay)

import net.minecraft.world.entity.LivingEntity;
import tech.naven.patch.Reflect;

public final class LivingEntityAccessors {
    public static void setNoJumpDelay(LivingEntity entity, int value) {
        Reflect.setInt(entity, LivingEntity.class, "noJumpDelay", value);
    }
}
