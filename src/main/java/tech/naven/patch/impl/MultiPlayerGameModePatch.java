package tech.naven.patch.impl;

// Ported from tech/mixin/MixinMultiPlayerGameMode.java

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import tech.naven.Naven;
import tech.naven.events.api.types.EventType;
import tech.naven.events.impl.EventAttack;
import tech.naven.events.impl.EventDestroyBlock;
import tech.naven.events.impl.EventPositionItem;
import tech.naven.patch.CallbackInfo;
import tech.naven.patch.CallbackInfoReturnable;
import tech.naven.patch.api.At;
import tech.naven.patch.api.Inject;
import tech.naven.patch.api.Patch;
import tech.naven.patch.api.Redirect;

@Patch(MultiPlayerGameMode.class)
public final class MultiPlayerGameModePatch {
    @Redirect(method = "startPrediction",
            desc = "(Lnet/minecraft/client/multiplayer/ClientLevel;Lnet/minecraft/client/multiplayer/prediction/PredictiveAction;)V",
            target = "net/minecraft/client/multiplayer/ClientPacketListener",
            targetDesc = "(Lnet/minecraft/network/protocol/Packet;)V", ordinal = 0)
    public static void onSendPacket(MultiPlayerGameMode self, ClientPacketListener instance, Packet<?> pPacket) {
        if (pPacket instanceof ServerboundUseItemPacket) {
            EventPositionItem event = new EventPositionItem(pPacket);
            Naven.getInstance().getEventManager().call(event);
            if (event.isCancelled()) {
                return;
            }

            instance.send(event.getPacket());
        } else {
            instance.send(pPacket);
        }
    }

    @Inject(method = "startDestroyBlock",
            desc = "(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;)Z",
            at = @At(At.Type.HEAD))
    public static void onStartDestroyBlock(MultiPlayerGameMode self, BlockPos pLoc, Direction pFace,
                                           CallbackInfoReturnable<Boolean> cir) {
        Naven.getInstance().getEventManager().call(new EventDestroyBlock(pLoc, pFace));
    }

    @Inject(method = "attack", desc = "(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/entity/Entity;)V",
            at = @At(At.Type.HEAD), cancellable = true)
    public static void onAttackPre(MultiPlayerGameMode self, Player pPlayer, Entity pTarget, CallbackInfo ci) {
        EventAttack event = new EventAttack(pTarget, EventType.PRE);
        Naven.getInstance().getEventManager().call(event);
        if (event.isCancelled()) {
            ci.cancel();
        }
    }

    @Inject(method = "attack", desc = "(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/entity/Entity;)V",
            at = @At(At.Type.RETURN))
    public static void onAttackPost(MultiPlayerGameMode self, Player pPlayer, Entity pTarget, CallbackInfo ci) {
        Naven.getInstance().getEventManager().call(new EventAttack(pTarget, EventType.POST));
    }
}
