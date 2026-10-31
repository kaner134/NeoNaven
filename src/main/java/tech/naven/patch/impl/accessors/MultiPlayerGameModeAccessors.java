package tech.naven.patch.impl.accessors;

// Ported from tech/mixin/accessors/MultiPlayerGameModeAccessor.java

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.level.GameType;
import tech.naven.patch.Reflect;

public final class MultiPlayerGameModeAccessors {
    public static void invokeEnsureHasSentCarriedItem(MultiPlayerGameMode gameMode) {
        Reflect.call(gameMode, MultiPlayerGameMode.class, "ensureHasSentCarriedItem", new Class<?>[0]);
    }

    public static GameType getLocalPlayerMode(MultiPlayerGameMode gameMode) {
        return Reflect.get(gameMode, MultiPlayerGameMode.class, "localPlayerMode");
    }
}
