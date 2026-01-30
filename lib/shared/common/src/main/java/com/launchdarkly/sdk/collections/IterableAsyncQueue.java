package com.launchdarkly.sdk.collections;

import java.util.LinkedList;
import java.util.concurrent.CompletableFuture;

/**
 * A thread-safe unbounded queue that provides asynchronous consumption via {@link CompletableFuture}.
 * <p>
 * This queue supports multiple concurrent producers and consumers. Items are delivered in FIFO order.
 * The {@link #take()} method returns a {@link CompletableFuture} that either completes immediately
 * if an item is available, or completes later when an item is added via {@link #put(Object)}.
 * <p>
 * When multiple consumers are waiting (i.e., multiple pending {@link #take()} calls), they are
 * satisfied in FIFO order as items become available.
 * <p>
 * Null values are supported.
 *
 * @param <T> the type of elements held in this queue
 */
public class IterableAsyncQueue<T> {
    private final Object lock = new Object();
    private final LinkedList<T> queue = new LinkedList<>();

    private final LinkedList<CompletableFuture<T>> pendingFutures = new LinkedList<>();

    /**
     * Adds an item to the queue.
     * <p>
     * If there is a consumer is waiting (a pending {@link #take()} call), the item is delivered
     * directly to the oldest waiting consumer's future. Otherwise, the item is added to the
     * queue for later consumption.
     * <p>
     * If a future returned by this method is completed or canceled by the caller, then the item associated
     * with that call will not be delivered. It is recommended not to complete or cancel the future
     * returned by {@link #take()} unless you are finished using the queue.
     *
     * @param item the item to add (maybe null)
     */
    public void put(T item) {
        CompletableFuture<T> pendingFuture = null;
        synchronized (lock) {
            CompletableFuture<T> nextFuture = pendingFutures.pollFirst();
            if(nextFuture != null) {
                pendingFuture = nextFuture;
            } else {
                queue.addLast(item);
                return;
            }
        }
        // Execute callback outside the lock.
        pendingFuture.complete(item);
    }
    /**
     * Retrieves and removes an item from the queue, returning a future that completes with the item.
     * <p>
     * If the queue contains items, returns an already-completed future with the oldest item.
     * If the queue is empty, returns a future that will complete when an item becomes available
     * via {@link #put(Object)}.
     *
     * @return a {@link CompletableFuture} that completes with the next item
     */
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
