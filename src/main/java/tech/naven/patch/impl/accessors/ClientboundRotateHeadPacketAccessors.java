package tech.naven.patch.impl.accessors;

// Ported from tech/mixin/accessors/ClientboundRotateHeadPacketAccessor.java (2 @Accessor)

import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import tech.naven.patch.Reflect;

public final class ClientboundRotateHeadPacketAccessors {
    public static int getEntityId(ClientboundRotateHeadPacket packet) {
        return Reflect.getInt(packet, ClientboundRotateHeadPacket.class, "entityId");
    }

    public static byte getYHeadRot(ClientboundRotateHeadPacket packet) {
        return (Byte) Reflect.get(packet, ClientboundRotateHeadPacket.class, "yHeadRot");
    }
}
