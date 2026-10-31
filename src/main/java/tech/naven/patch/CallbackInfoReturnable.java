package tech.naven.patch;

/** Cancellation handle handed to {@code @Inject} handlers of non-void methods. */
public final class CallbackInfoReturnable<T> {
    private boolean cancelled;
    private T returnValue;

    public void cancel() {
        this.cancelled = true;
    }

    public boolean isCancelled() {
        return this.cancelled;
    }

    public T getReturnValue() {
        return this.returnValue;
    }

    public void setReturnValue(T value) {
        this.returnValue = value;
    }
}
