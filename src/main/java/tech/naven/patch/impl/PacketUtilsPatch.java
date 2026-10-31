package tech.naven.patch.impl;

// Ported from tech/mixin/MixinPacketThreadUtils.java
//
// ensureRunningOnSameThread is static and generic, so the handler has no target instance.
// The mixin's @Shadow private static LOGGER is read through Reflect.

import net.minecraft.network.PacketListener;
import net.minecraft.network.PacketProcessor;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketUtils;
import net.minecraft.server.RunningOnDifferentThreadException;
import org.slf4j.Logger;
import tech.naven.Naven;
import tech.naven.events.impl.EventHandlePacket;
import tech.naven.patch.CallbackInfo;
import tech.naven.patch.Reflect;
import tech.naven.patch.api.At;
import tech.naven.patch.api.Inject;
import tech.naven.patch.api.Patch;

@Patch(PacketUtils.class)
public final class PacketUtilsPatch {
    // desc is omitted on purpose: ensureRunningOnSameThread has a (Packet, PacketListener,
    // ServerLevel) overload with the same arity. The handler's third parameter
    // (PacketProcessor) selects the client one.
    @Inject(method = "ensureRunningOnSameThread", at = @At(At.Type.HEAD), cancellable = true)
    public static <T extends PacketListener> void onEnsureRunningOnSameThread(
            Packet<T> packet, T listener, PacketProcessor packetProcessor, CallbackInfo ci)
            throws RunningOnDifferentThreadException {
        if (!packetProcessor.isSameThread()) {
            packetProcessor.scheduleIfPossible(() -> {
                if (listener.isAcceptingMessages()) {
                    try {
                        EventHandlePacket event = new EventHandlePacket((Packet) packet);
                        Naven.getInstance().getEventManager().call(event);
                        if (!event.isCancelled()) {
                            packet.handle(listener);
                        }
                    } catch (Exception exception) {
                        Logger logger = Reflect.get(null, PacketUtils.class, "LOGGER");
                        logger.error("Failed to handle packet {}, suppressing error", packet, exception);
                    }
                } else {
                    Logger logger = Reflect.get(null, PacketUtils.class, "LOGGER");
                    logger.debug("Ignoring packet due to disconnection: {}", packet);
                }
            });
            throw RunningOnDifferentThreadException.RUNNING_ON_DIFFERENT_THREAD;
        }
    }
}
