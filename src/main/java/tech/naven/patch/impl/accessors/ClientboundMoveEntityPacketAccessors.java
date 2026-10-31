package tech.naven.patch.impl.accessors;

// Ported from tech/mixin/accessors/ClientboundMoveEntityPacketAccessor.java (9 @Accessor)

import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import tech.naven.patch.Reflect;

public final class ClientboundMoveEntityPacketAccessors {
    public static int getEntityId(ClientboundMoveEntityPacket packet) {
        return Reflect.getInt(packet, ClientboundMoveEntityPacket.class, "entityId");
    }

    public static short getXa(ClientboundMoveEntityPacket packet) {
        return (Short) Reflect.get(packet, ClientboundMoveEntityPacket.class, "xa");
    }

    public static short getYa(ClientboundMoveEntityPacket packet) {
        return (Short) Reflect.get(packet, ClientboundMoveEntityPacket.class, "ya");
    }

    public static short getZa(ClientboundMoveEntityPacket packet) {
        return (Short) Reflect.get(packet, ClientboundMoveEntityPacket.class, "za");
    }

    public static byte getYRot(ClientboundMoveEntityPacket packet) {
        return (Byte) Reflect.get(packet, ClientboundMoveEntityPacket.class, "yRot");
    }

    public static byte getXRot(ClientboundMoveEntityPacket packet) {
        return (Byte) Reflect.get(packet, ClientboundMoveEntityPacket.class, "xRot");
    }

    public static boolean getOnGround(ClientboundMoveEntityPacket packet) {
        return Reflect.getBoolean(packet, ClientboundMoveEntityPacket.class, "onGround");
    }

    public static boolean getHasRot(ClientboundMoveEntityPacket packet) {
        return Reflect.getBoolean(packet, ClientboundMoveEntityPacket.class, "hasRot");
    }

    public static boolean getHasPos(ClientboundMoveEntityPacket packet) {
        return Reflect.getBoolean(packet, ClientboundMoveEntityPacket.class, "hasPos");
    }
}
