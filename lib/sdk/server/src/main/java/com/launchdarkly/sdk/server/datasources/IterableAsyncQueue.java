package com.launchdarkly.sdk.server.datasources;

import java.util.LinkedList;
import java.util.concurrent.CompletableFuture;

public class IterableAsyncQueue<T> {
    private final Object lock = new Object();
    private final LinkedList<T> queue = new LinkedList<>();

    private CompletableFuture<T> nextFuture = null;

    public void put(T item) {
        synchronized (lock) {
            if(nextFuture != null) {
                nextFuture.complete(item);
                nextFuture = null;
            }
            queue.addLast(item);
        }
    }
    public CompletableFuture<T> take() {
        synchronized (lock) {
            if(!queue.isEmpty()) {
                return CompletableFuture.completedFuture(queue.removeFirst());
            }
            if (nextFuture == null) {
                nextFuture = new CompletableFuture<>();
            }
            return nextFuture;
        }
    }
}
