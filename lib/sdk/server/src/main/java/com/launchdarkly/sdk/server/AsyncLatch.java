package com.launchdarkly.sdk.server;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;

/**
 * A latch that is released once, and that callers wait on through futures.
 * <p>
 * This exists so that a loop can wait for "a result or the latch, whichever comes first" on every iteration without
 * a memory leak. The obvious way to write that wait is {@code CompletableFuture.anyOf(result, latchFuture)}. But
 * {@code anyOf} attaches a completion node to each future it is given, and a pending {@link CompletableFuture} keeps
 * those nodes until it completes. If the latch used one shared future, every iteration that ended with a result would
 * leave one more node on that future. The latch can stay pending for the life of the process, so the nodes would
 * accumulate without limit.
 * <p>
 * To prevent this, {@link #newFuture()} returns a new future for each wait. The latch holds these futures weakly, so a
 * future that nobody waits on any more is garbage collected, together with the nodes that {@code anyOf} attached to
 * it. When the latch is released, it completes each future that is still waiting. After the release,
 * {@link #newFuture()} returns a future that is already complete.
 */
final class AsyncLatch {
    private final Object lock = new Object();

    // The futures that wait for the release, or null after the release.
    private Set<CompletableFuture<Void>> pending = Collections.newSetFromMap(new WeakHashMap<>());

    /**
     * A new future that completes when the latch is released.
     *
     * @return the future, which is already complete if the latch has been released
     */
    CompletableFuture<Void> newFuture() {
        CompletableFuture<Void> future = new CompletableFuture<>();
        synchronized (lock) {
            if (pending != null) {
                pending.add(future);
                return future;
            }
        }
        future.complete(null);
        return future;
    }

    /**
     * Releases the latch, which completes every future from {@link #newFuture()}. Releasing it again has no effect.
     */
    void release() {
        List<CompletableFuture<Void>> waiting;
        synchronized (lock) {
            if (pending == null) {
                return;
            }
            // The list holds the futures strongly, so none is garbage collected before it completes.
            waiting = new ArrayList<>(pending);
            pending = null;
        }
        for (CompletableFuture<Void> future : waiting) {
            future.complete(null);
        }
    }
}
