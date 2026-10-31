package tech.naven.patch.impl.accessors;

// Ported from tech/mixin/accessors/ServerboundMovePlayerPacketAccessor.java (@Accessor setYRot)

import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import tech.naven.patch.Reflect;

public final class ServerboundMovePlayerPacketAccessors {
    public static void setYRot(ServerboundMovePlayerPacket packet, float value) {
        Reflect.setFloat(packet, ServerboundMovePlayerPacket.class, "yRot", value);
    }
}
