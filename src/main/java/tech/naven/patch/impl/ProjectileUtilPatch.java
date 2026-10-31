package tech.naven.patch.impl;

// Ported from tech/mixin/MixinProjectileUtil.java
//
// getEntityHitResult is static, so the handler has no target instance.

import java.util.List;
import java.util.function.Predicate;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import tech.naven.patch.api.Patch;
import tech.naven.patch.api.Redirect;
import tech.naven.utils.BlinkingPlayer;

@Patch(ProjectileUtil.class)
public final class ProjectileUtilPatch {
    @Redirect(method = "getEntityHitResult",
            desc = "(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;D)Lnet/minecraft/world/phys/EntityHitResult;",
            target = "net/minecraft/world/level/Level", targetName = "getEntities",
            targetDesc = "(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;",
            ordinal = 0)
    public static List<Entity> hook(Level instance, Entity pEntity, AABB pBoundingBox,
                                    Predicate<? super Entity> pPredicate) {
        List<Entity> entities = instance.getEntities(pEntity, pBoundingBox, pPredicate);
        entities.removeIf(entity -> entity instanceof BlinkingPlayer);
        return entities;
    }
}
