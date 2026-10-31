package tech.naven.patch.engine;

/**
 * Internal, non-fatal failure raised while binding or applying a single handler.
 *
 * <p>The engine catches it, records the message in {@link PatchEngine#failures()} and continues
 * with the remaining handlers, so a broken patch can never abort a transformation.</p>
 */
final class PatchException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    PatchException(String message) {
        super(message);
    }

    PatchException(String message, Throwable cause) {
        super(message, cause);
    }
}
