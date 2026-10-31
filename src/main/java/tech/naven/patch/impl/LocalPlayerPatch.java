package tech.naven.patch.impl;

// Ported from tech/mixin/MixinLocalPlayer.java
//
// sendPosition is an @Overwrite: the original method body is copied verbatim with
// this -> self. Private members (xLast, yLast, zLast, yRotLast, xRotLast,
// positionReminder, lastOnGround, autoJumpEnabled, minecraft) and the private method
// sendIsSprintingIfNeeded go through Reflect; isControlledCamera is protected and lives
// in another package, so it is reflected as well. "connection" is public.

import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Pos;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.PosRot;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Rot;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.StatusOnly;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import tech.naven.Naven;
import tech.naven.events.api.types.EventType;
import tech.naven.events.impl.EventMotion;
import tech.naven.events.impl.EventSlowdown;
import tech.naven.events.impl.EventSprint;
import tech.naven.events.impl.EventUpdate;
import tech.naven.patch.CallbackInfo;
import tech.naven.patch.Reflect;
import tech.naven.patch.api.At;
import tech.naven.patch.api.Inject;
import tech.naven.patch.api.Overwrite;
import tech.naven.patch.api.Patch;
import tech.naven.patch.api.Redirect;

@Patch(LocalPlayer.class)
public final class LocalPlayerPatch {
    @Inject(method = "tick", desc = "()V",
            at = @At(value = At.Type.INVOKE, method = "net/minecraft/client/player/AbstractClientPlayer",
                    name = "tick", desc = "()V", ordinal = 0))
    public static void injectUpdateEvent(LocalPlayer self, CallbackInfo ci) {
        Naven.getInstance().getEventManager().call(new EventUpdate());
        Naven.getInstance().getEventManager().call(new EventSprint());
    }

    @Overwrite(method = "sendPosition", desc = "()V")
    public static void sendPosition(LocalPlayer self) {
        EventMotion eventPre = new EventMotion(EventType.PRE, self.getX(), self.getY(), self.getZ(), self.getYRot(),
                self.getXRot(), self.onGround());
        Naven.getInstance().getEventManager().call(eventPre);
        if (eventPre.isCancelled()) {
            Naven.getInstance().getEventManager()
                    .call(new EventMotion(EventType.POST, eventPre.getYaw(), eventPre.getPitch()));
        } else {
            Reflect.call(self, LocalPlayer.class, "sendIsSprintingIfNeeded", new Class<?>[0]);
            boolean controlledCamera = (Boolean) Reflect.call(self, LocalPlayer.class, "isControlledCamera",
                    new Class<?>[0]);
            if (controlledCamera) {
                double d4 = eventPre.getX() - Reflect.getDouble(self, LocalPlayer.class, "xLast");
                double d0 = eventPre.getY() - Reflect.getDouble(self, LocalPlayer.class, "yLast");
                double d1 = eventPre.getZ() - Reflect.getDouble(self, LocalPlayer.class, "zLast");
                double d2 = (double) (eventPre.getYaw() - Reflect.getFloat(self, LocalPlayer.class, "yRotLast"));
                double d3 = (double) (eventPre.getPitch() - Reflect.getFloat(self, LocalPlayer.class, "xRotLast"));
                int reminder = Reflect.getInt(self, LocalPlayer.class, "positionReminder") + 1;
                Reflect.setInt(self, LocalPlayer.class, "positionReminder", reminder);
                boolean flag1 = Mth.lengthSquared(d4, d0, d1) > Mth.square(2.0E-4) || reminder >= 20;
                boolean flag2 = d2 != 0.0 || d3 != 0.0;
                if (self.isPassenger()) {
                    Vec3 vec3 = self.getDeltaMovement();
                    self.connection
                            .send(new PosRot(vec3.x, -999.0, vec3.z, eventPre.getYaw(), eventPre.getPitch(),
                                    eventPre.isOnGround(), self.horizontalCollision));
                    flag1 = false;
                } else if (flag1 && flag2) {
                    self.connection
                            .send(new PosRot(
                                    eventPre.getX(),
                                    eventPre.getY(),
                                    eventPre.getZ(),
                                    eventPre.getYaw(),
                                    eventPre.getPitch(),
                                    eventPre.isOnGround(),
                                    self.horizontalCollision
                            ));
                } else if (flag1) {
                    self.connection.send(new Pos(eventPre.getX(), eventPre.getY(), eventPre.getZ(),
                            eventPre.isOnGround(), self.horizontalCollision));
                } else if (flag2) {
                    self.connection.send(new Rot(eventPre.getYaw(), eventPre.getPitch(), eventPre.isOnGround(),
                            self.horizontalCollision));
                } else if (Reflect.getBoolean(self, LocalPlayer.class, "lastOnGround") != eventPre.isOnGround()) {
                    self.connection.send(new StatusOnly(eventPre.isOnGround(), self.horizontalCollision));
                }

                if (flag1) {
                    Reflect.setDouble(self, LocalPlayer.class, "xLast", eventPre.getX());
                    Reflect.setDouble(self, LocalPlayer.class, "yLast", eventPre.getY());
                    Reflect.setDouble(self, LocalPlayer.class, "zLast", eventPre.getZ());
                    Reflect.setInt(self, LocalPlayer.class, "positionReminder", 0);
                }

                if (flag2) {
                    Reflect.setFloat(self, LocalPlayer.class, "yRotLast", eventPre.getYaw());
                    Reflect.setFloat(self, LocalPlayer.class, "xRotLast", eventPre.getPitch());
                }

                Reflect.setBoolean(self, LocalPlayer.class, "lastOnGround", eventPre.isOnGround());
                Minecraft minecraft = Reflect.get(self, LocalPlayer.class, "minecraft");
                Reflect.setBoolean(self, LocalPlayer.class, "autoJumpEnabled",
                        minecraft.options.autoJump().get());
            }

            Naven.getInstance().getEventManager()
                    .call(new EventMotion(EventType.POST, eventPre.getYaw(), eventPre.getPitch()));
        }
    }

    // Match by method NAME: aiStep contains many LocalPlayer.()Z calls (jump, shift,
    // isPassenger, isShiftKeyDown, isSleeping, isUsingItem, ...), so owner+desc+ordinal is
    // not precise enough - it previously redirected isPassenger() by mistake.
    @Redirect(method = "aiStep", desc = "()V",
            target = "net/minecraft/client/player/LocalPlayer", targetName = "isUsingItem",
            targetDesc = "()Z", ordinal = 0)
    public static boolean onSlowdown(LocalPlayer self, LocalPlayer localPlayer) {
        EventSlowdown event = new EventSlowdown(localPlayer.isUsingItem());
        Naven.getInstance().getEventManager().call(event);
        return event.isSlowdown();
    }
}
