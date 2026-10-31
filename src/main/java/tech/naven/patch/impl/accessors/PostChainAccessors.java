package tech.naven.patch.impl.accessors;

// Ported from tech/mixin/accessors/PostChainAccessor.java (@Accessor("passes"))

import java.util.List;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostPass;
import tech.naven.patch.Reflect;

public final class PostChainAccessors {
    public static List<PostPass> getPasses(PostChain chain) {
        return Reflect.get(chain, PostChain.class, "passes");
    }
}
