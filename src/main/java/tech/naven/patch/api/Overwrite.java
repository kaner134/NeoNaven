package tech.naven.patch.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Replaces the whole body of a target method with a delegation to the handler.
 *
 * <p>Handler signature: {@code static R h(Target self, <original params...>)}.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Overwrite {
    String method();

    String desc() default "";
}
