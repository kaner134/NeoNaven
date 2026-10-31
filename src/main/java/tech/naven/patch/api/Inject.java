package tech.naven.patch.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Injects a call to the annotated static handler.
 *
 * <p>Handler signature: {@code static void h(Target self, <original params...>, CallbackInfo ci)}.
 * For non-void targets use {@code CallbackInfoReturnable<T>} instead of {@code CallbackInfo}; the
 * engine emits the early-return sequence when the callback is cancelled.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Inject {
    String method();

    /** Target descriptor; inferred from the handler signature when empty. */
    String desc() default "";

    At at() default @At(At.Type.HEAD);

    boolean cancellable() default false;
}
