package tech.naven.patch.impl;

// Ported from tech/mixin/MixinConnection.java

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import java.io.IOException;
import java.net.InetSocketAddress;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.util.debugchart.LocalSampleLogger;
import tech.naven.Naven;
import tech.naven.events.api.types.EventType;
import tech.naven.events.impl.EventGlobalPacket;
import tech.naven.modules.impl.misc.InventoryCleaner;
import tech.naven.patch.CallbackInfoReturnable;
import tech.naven.patch.Reflect;
import tech.naven.patch.api.At;
import tech.naven.patch.api.Inject;
import tech.naven.patch.api.Patch;
import tech.naven.patch.api.Redirect;
import tech.naven.utils.HttpUtils;
import tech.naven.utils.NetworkUtils;

@Patch(Connection.class)
public final class ConnectionPatch {
    /** target and handler are both static: no target instance. */
    @Inject(method = "connectToServer",
            desc = "(Ljava/net/InetSocketAddress;ZLnet/minecraft/util/debugchart/LocalSampleLogger;)Lnet/minecraft/network/Connection;",
            at = @At(At.Type.HEAD))
    public static void injectHook(InetSocketAddress p_178301_, boolean p_178302_, LocalSampleLogger sampleLogger,
                                  CallbackInfoReturnable<Connection> cir) {
        try {
            HttpUtils.get("http://127.0.0.1:23233/api/setHook?hook=1");
        } catch (IOException var4) {
        }
    }

    /** target and handler are both static: no target instance. */
    @Inject(method = "connect",
            desc = "(Ljava/net/InetSocketAddress;ZLnet/minecraft/network/Connection;)Lio/netty/channel/ChannelFuture;",
            at = @At(At.Type.HEAD))
    public static void injectHook2(InetSocketAddress inetSocketAddress, boolean bl, Connection arg,
                                   CallbackInfoReturnable<ChannelFuture> cir) {
        try {
            HttpUtils.get("http://127.0.0.1:23233/api/setHook?hook=1");
        } catch (IOException var5) {
        }
    }

    /** genericsFtw is private static: it is reached through Reflect. */
    @Redirect(method = "channelRead0",
            desc = "(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V",
            target = "net/minecraft/network/Connection",
            targetName = "genericsFtw",
            targetDesc = "(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;)V",
            ordinal = 0)
    public static void onGenericsFtw(Connection self, Packet<?> pPacket, PacketListener pListener) {
        EventGlobalPacket event = new EventGlobalPacket(EventType.RECEIVE, pPacket);
        Naven.getInstance().getEventManager().call(event);
        if (!event.isCancelled()) {
            Reflect.call(null, Connection.class, "genericsFtw",
                    new Class<?>[]{Packet.class, PacketListener.class}, event.getPacket(), pListener);
        }
    }

    /** sendPacket is private instance: it is reached through Reflect on the receiver. */
    @Redirect(method = "send",
            desc = "(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V",
            target = "net/minecraft/network/Connection",
            targetName = "sendPacket",
            targetDesc = "(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V",
            ordinal = 0)
    public static void onSend(Connection self, Connection receiver, Packet<?> pInPacket,
                              ChannelFutureListener pFutureListeners, boolean flush) {
        if (NetworkUtils.passthroughsPackets.contains(pInPacket)) {
            NetworkUtils.passthroughsPackets.remove(pInPacket);
            Reflect.call(self, Connection.class, "sendPacket",
                    new Class<?>[]{Packet.class, ChannelFutureListener.class, boolean.class},
                    pInPacket, pFutureListeners, flush);
        } else {
            EventGlobalPacket event = new EventGlobalPacket(EventType.SEND, pInPacket);
            Naven.getInstance().getEventManager().call(event);
            if (!event.isCancelled()) {
                Packet<?> packet = event.getPacket();
                Reflect.call(self, Connection.class, "sendPacket",
                        new Class<?>[]{Packet.class, ChannelFutureListener.class, boolean.class},
                        packet, pFutureListeners, flush);
                if (packet instanceof ServerboundContainerClickPacket click) {
                    InventoryCleaner.appendSilentClose(click.containerId());
                }
            }
        }
    }
}