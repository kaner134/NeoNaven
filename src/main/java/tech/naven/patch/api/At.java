package tech.naven.patch.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Injection point descriptor. */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE_USE})
public @interface At {
    Type value();

    /** For {@link Type#INVOKE}: internal owner name, e.g. {@code net/minecraft/client/player/LocalPlayer}. */
    String method() default "";

    /**
     * For {@link Type#INVOKE}: invoked method name. Strongly recommended whenever the owner
     * declares several methods with the same descriptor - matching on owner+desc alone picks
     * the wrong call site (that is how {@code isPassenger()} was once mistaken for
     * {@code isUsingItem()}).
     */
    String name() default "";

    /** For {@link Type#INVOKE}: invocation descriptor, e.g. {@code ()Z}. */
    String desc() default "";

    /** For {@link Type#INVOKE}: zero-based occurrence index of the matching invocation. */
    int ordinal() default 0;

    enum Type {
        /** Right after the target frame is created, before any original instruction. */
        HEAD,
        /** Immediately before every RETURN opcode. */
        RETURN,
        /** Immediately before the last RETURN opcode. */
        TAIL,
        /** Immediately before the Nth matching method invocation. */
        INVOKE
    }
}
