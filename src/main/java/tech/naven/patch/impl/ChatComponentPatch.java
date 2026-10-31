package tech.naven.patch.impl;

// Ported from tech/mixin/MixinChatComponent.java

import net.minecraft.client.GuiMessageTag;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import tech.naven.modules.impl.render.NameProtect;
import tech.naven.patch.api.At;
import tech.naven.patch.api.ModifyVariable;
import tech.naven.patch.api.Patch;

@Patch(ChatComponent.class)
public final class ChatComponentPatch {
    /** Replaces the argsOnly HEAD ModifyVariable: parameter slot 1 (slot 0 is {@code this}). */
    @ModifyVariable(method = "addMessage",
            desc = "(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/GuiMessageTag;)V",
            index = 1, at = @At(At.Type.HEAD))
    public static Component naven$protectChatMessage(ChatComponent self, Component message, MessageSignature signature,
                                                     GuiMessageTag tag, Component value) {
        return NameProtect.rewriteComponent(value);
    }
}
