package tech.naven.patch;

/**
 * Cancellation handle handed to {@code @Inject} handlers.
 *
 * <p>Deliberately not a superclass of {@link CallbackInfoReturnable}: with an unbounded
 * type variable the erased signatures would clash ({@code setReturnValue(Object)} vs
 * {@code setReturnValue(T)}).</p>
 */
public class CallbackInfo {
    private boolean cancelled;

    public void cancel() {
        this.cancelled = true;
    }

    public boolean isCancelled() {
        return this.cancelled;
    }
}
