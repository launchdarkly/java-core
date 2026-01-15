package com.launchdarkly.sdk.server;

import java.util.LinkedList;
import java.util.concurrent.CompletableFuture;

class IterableAsyncQueue<T> {
    private final Object lock = new Object();
    private final LinkedList<T> queue = new LinkedList<>();

    private final LinkedList<CompletableFuture<T>> pendingFutures = new LinkedList<>();

    public void put(T item) {
        synchronized (lock) {
            CompletableFuture<T> nextFuture = pendingFutures.pollFirst();
            if(nextFuture != null) {
                nextFuture.complete(item);
                return;
            }
            queue.addLast(item);
        }
    }
    public CompletableFuture<T> take() {
        synchronized (lock) {
            if(!queue.isEmpty()) {
                return CompletableFuture.completedFuture(queue.removeFirst());
            }
            CompletableFuture<T> takeFuture = new CompletableFuture<>();
            pendingFutures.addLast(takeFuture);
            return takeFuture;
        }
    }
}
