package tech.naven.patch.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Replaces a target invocation with a call to the annotated static handler.
 *
 * <p>Handler signature: {@code static R h(Target self, <invocation params...>)} for a
 * non-static invocation, {@code static R h(<invocation params...>)} for a static one.
 * The handler returns the value the original invocation would have produced.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Redirect {
    String method();

    /** Enclosing target method descriptor; inferred when empty. */
    String desc() default "";

    /** Internal owner name of the redirected invocation. */
    String target();

    /** Name of the redirected invocation; empty means "any name with this owner+descriptor". */
    String targetName() default "";

    /** Descriptor of the redirected invocation. */
    String targetDesc();

    /** Zero-based occurrence among matching invocations. */
    int ordinal() default 0;
}
