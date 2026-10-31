package tech.naven.patch.impl.accessors;

// Ported from tech/mixin/accessors/CrossbowItemAccessor.java (@Invoker("getShootingPower"))

import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.component.ChargedProjectiles;
import tech.naven.patch.Reflect;

public final class CrossbowItemAccessors {
    public static float getShootingPower(ChargedProjectiles projectiles) {
        return (Float) Reflect.call(null, CrossbowItem.class, "getShootingPower",
                new Class<?>[]{ChargedProjectiles.class}, projectiles);
    }
}
