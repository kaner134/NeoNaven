package tech.naven.patch.impl.accessors;

// Ported from tech/mixin/accessors/AbstractArrowAccessor.java (@Invoker("isInGround"))

import net.minecraft.world.entity.projectile.AbstractArrow;
import tech.naven.patch.Reflect;

public final class AbstractArrowAccessors {
    public static boolean callIsInGround(AbstractArrow arrow) {
        return (Boolean) Reflect.call(arrow, AbstractArrow.class, "isInGround", new Class<?>[0]);
    }
}
