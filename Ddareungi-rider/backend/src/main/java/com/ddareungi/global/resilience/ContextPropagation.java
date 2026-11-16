package com.ddareungi.global.resilience;

import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;

/**
 * Runs work on a new virtual thread while carrying the caller's logging context (MDC, e.g. {@code requestId}) and
 * its {@link Deadline}. Virtual threads do not inherit the MDC, so without this, provider logs lose the request id.
 */
public final class ContextPropagation {
    private ContextPropagation() {}

    /** Wraps {@code task} so that it runs with the MDC and deadline of the thread calling this method. */
    public static <T> Callable<T> wrap(Callable<T> task) {
        @Nullable Map<String, String> mdc = MDC.getCopyOfContextMap();
        @Nullable Deadline deadline = Deadline.current();
        return () -> {
            @Nullable Map<String, String> previousMdc = MDC.getCopyOfContextMap();
            @Nullable Deadline previousDeadline = Deadline.current();
            if (mdc == null) MDC.clear(); else MDC.setContextMap(mdc);
            Deadline.set(deadline);
            try {
                return task.call();
            } finally {
                if (previousMdc == null) MDC.clear(); else MDC.setContextMap(previousMdc);
                Deadline.set(previousDeadline);
            }
        };
    }

    /**
     * Starts {@code task} on a new virtual thread with the caller's context. The returned future can be cancelled
     * (interrupting the thread) without waiting for it, unlike a closed executor.
     */
    public static <T> Future<T> start(String name, Callable<T> task) {
        var future = new FutureTask<>(wrap(task));
        Thread.ofVirtual().name(name).start(future);
        return future;
    }

    /** Unwraps an {@link ExecutionException} so that callers see the original unchecked failure. */
    public static RuntimeException unwrap(ExecutionException error) {
        Throwable cause = error.getCause();
        if (cause instanceof RuntimeException runtime) return runtime;
        if (cause instanceof Error fatal) throw fatal;
        return new IllegalStateException("Task failed", cause);
    }
}
