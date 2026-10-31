package tech.naven.patch.impl.accessors;

// Ported from tech/mixin/accessors/ClientLevelAccessor.java (@Accessor getBlockStatePredictionHandler)

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
import tech.naven.patch.Reflect;

public final class ClientLevelAccessors {
    public static BlockStatePredictionHandler getBlockStatePredictionHandler(ClientLevel level) {
        return Reflect.get(level, ClientLevel.class, "blockStatePredictionHandler");
    }
}
