package tech.naven.patch.impl;

// Ported from tech/mixin/MixinGuiGraphics.java

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import tech.naven.modules.impl.render.NameProtect;
import tech.naven.patch.api.At;
import tech.naven.patch.api.ModifyVariable;
import tech.naven.patch.api.Patch;
import tech.naven.utils.TextReplacer;

@Patch(GuiGraphics.class)
public final class GuiGraphicsPatch {
    // desc is omitted on purpose: drawString has seven overloads, three of which take six
    // arguments. The handler parameter types select the String/Component/FormattedCharSequence
    // one; an explicit desc would only be compared by arity.
    @ModifyVariable(method = "drawString", index = 2, at = @At(At.Type.HEAD))
    public static String naven$protectDrawString(GuiGraphics self, Font font, String text, int x, int y, int color,
                                                 boolean dropShadow, String value) {
        return TextReplacer.replace(value);
    }

    @ModifyVariable(method = "drawString", index = 2, at = @At(At.Type.HEAD))
    public static Component naven$protectDrawComponent(GuiGraphics self, Font font, Component text, int x, int y,
                                                       int color, boolean dropShadow, Component value) {
        return NameProtect.rewriteComponent(value);
    }

    @ModifyVariable(method = "drawString", index = 2, at = @At(At.Type.HEAD))
    public static FormattedCharSequence naven$protectDrawSequence(GuiGraphics self, Font font,
                                                                  FormattedCharSequence text, int x, int y,
                                                                  int color, boolean dropShadow,
                                                                  FormattedCharSequence value) {
        return TextReplacer.rewriteSequence(value);
    }
}
