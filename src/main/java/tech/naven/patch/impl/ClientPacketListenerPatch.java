package tech.naven.patch.impl;

// Ported from tech/mixin/MixinClientPacketListener.java
//
// NOTE: the second inject used At.Shift.AFTER on the telemetry call. The hot-injection
// API has no shift, so the anchor is the same INVOKE with the default BEFORE position.
// The handler only performs an unrelated HTTP request and touches no locals, so the
// ordering change is not observable.

import java.io.IOException;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import tech.naven.Naven;
import tech.naven.events.impl.EventServerSetPosition;
import tech.naven.patch.CallbackInfo;
import tech.naven.patch.api.At;
import tech.naven.patch.api.Inject;
import tech.naven.patch.api.Patch;
import tech.naven.patch.api.Redirect;
import tech.naven.utils.HttpUtils;

@Patch(ClientPacketListener.class)
public final class ClientPacketListenerPatch {
    @Redirect(method = "handleMovePlayer",
            desc = "(Lnet/minecraft/network/protocol/game/ClientboundPlayerPositionPacket;)V",
            target = "net/minecraft/network/Connection",
            targetDesc = "(Lnet/minecraft/network/protocol/Packet;)V", ordinal = 1)
    public static void onSendPacket(ClientPacketListener self, Connection instance, Packet<?> pPacket) {
        EventServerSetPosition event = new EventServerSetPosition(pPacket);
        Naven.getInstance().getEventManager().call(event);
        instance.send(event.getPacket());
    }

    @Inject(method = "handleLogin", desc = "(Lnet/minecraft/network/protocol/game/ClientboundLoginPacket;)V",
            at = @At(value = At.Type.INVOKE,
                    method = "net/minecraft/client/telemetry/WorldSessionTelemetryManager",
                    desc = "(Lnet/minecraft/world/level/GameType;Z)V", ordinal = 0),
            cancellable = true)
    public static void onLogin(ClientPacketListener self, ClientboundLoginPacket p_105030_, CallbackInfo ci) {
        try {
            HttpUtils.get("http://127.0.0.1:23233/api/setHook?hook=0");
        } catch (IOException var4) {
        }
    }
}
