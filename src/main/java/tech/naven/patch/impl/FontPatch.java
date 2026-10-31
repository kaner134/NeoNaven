package tech.naven.patch.impl;

// Ported from tech/mixin/MixinFont.java

import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import tech.naven.patch.api.At;
import tech.naven.patch.api.ModifyVariable;
import tech.naven.patch.api.Patch;
import tech.naven.utils.TextReplacer;

@Patch(Font.class)
public final class FontPatch {
    // desc is omitted on purpose: width(String), width(FormattedText) and
    // width(FormattedCharSequence) all take one argument, and an explicit desc would be matched
    // on arity only. The handler parameter types select the right overload.
    @ModifyVariable(method = "width", index = 1, at = @At(At.Type.HEAD))
    public static String naven$protectWidthString(Font self, String text, String value) {
        return TextReplacer.replace(value);
    }

    @ModifyVariable(method = "width", index = 1, at = @At(At.Type.HEAD))
    public static FormattedText naven$protectWidthText(Font self, FormattedText text, FormattedText value) {
        return TextReplacer.rewriteText(value);
    }

    @ModifyVariable(method = "width", index = 1, at = @At(At.Type.HEAD))
    public static FormattedCharSequence naven$protectWidthSequence(Font self, FormattedCharSequence text,
                                                                   FormattedCharSequence value) {
        return TextReplacer.rewriteSequence(value);
    }
}
