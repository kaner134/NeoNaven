package tech.naven.patch.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Rewrites a local variable at an injection point.
 *
 * <p>Handler signature: {@code static V h(Target self, <original params...>, V value)} and must
 * return the replacement value.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ModifyVariable {
    String method();

    String desc() default "";

    /** Local variable slot to rewrite; -1 lets the engine find the matching parameter slot. */
    int index() default -1;

    /** Zero-based index among parameters of the handler's value type (only used when index < 0). */
    int ordinal() default 0;

    At at() default @At(At.Type.HEAD);
}
