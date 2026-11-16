package com.ddareungi.global.resilience;

import org.jspecify.annotations.Nullable;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * A point in time by which a whole user request (e.g. one route plan) must finish. It is attached to the current
 * thread so that provider calls deep in the stack can stop retrying once the budget is spent; it crosses threads
 * only through {@link ContextPropagation}.
 */
public final class Deadline {
    private static final ThreadLocal<@Nullable Deadline> CURRENT = new ThreadLocal<>();
    private final long expiresAtNanos;

    private Deadline(long expiresAtNanos) { this.expiresAtNanos = expiresAtNanos; }

    public static Deadline after(Duration budget) { return new Deadline(System.nanoTime() + budget.toNanos()); }

    /** The deadline of the current thread, or null when none applies. */
    public static @Nullable Deadline current() { return CURRENT.get(); }

    static void set(@Nullable Deadline deadline) {
        if (deadline == null) CURRENT.remove(); else CURRENT.set(deadline);
    }

    /** The tighter of the current thread's deadline and a new one {@code budget} from now. */
    public static Deadline within(Duration budget) {
        var fresh = after(budget);
        var current = current();
        return current != null && current.expiresAtNanos - fresh.expiresAtNanos < 0 ? current : fresh;
    }

    /** Runs {@code task} on this thread with this deadline attached, restoring the previous one afterwards. */
    public <T> T supply(Supplier<T> task) {
        var previous = current();
        set(this);
        try {
            return task.get();
        } finally {
            set(previous);
        }
    }

    public Duration remaining() {
        long nanos = expiresAtNanos - System.nanoTime();
        return nanos > 0 ? Duration.ofNanos(nanos) : Duration.ZERO;
    }

    public boolean expired() { return expiresAtNanos - System.nanoTime() <= 0; }
}
