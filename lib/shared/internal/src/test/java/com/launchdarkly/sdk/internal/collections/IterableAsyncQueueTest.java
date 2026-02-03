package com.launchdarkly.sdk.internal.collections;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

@SuppressWarnings("javadoc")
public class IterableAsyncQueueTest {

    @Test
    public void putThenTakeReturnsImmediately() throws Exception {
        IterableAsyncQueue<String> queue = new IterableAsyncQueue<>();

        queue.put("item1");

        CompletableFuture<String> future = queue.take();
        assertTrue("Future should be completed immediately", future.isDone());
        assertEquals("item1", future.get());
    }

    @Test
    public void takeThenPutCompletesWaitingFuture() throws Exception {
        IterableAsyncQueue<String> queue = new IterableAsyncQueue<>();

        CompletableFuture<String> future = queue.take();
        assertFalse("Future should not be completed yet", future.isDone());

        queue.put("item1");

        assertTrue("Future should be completed after put", future.isDone());
        assertEquals("item1", future.get());
    }

    @Test
    public void multiplePutsThenMultipleTakesPreservesOrder() throws Exception {
        IterableAsyncQueue<Integer> queue = new IterableAsyncQueue<>();

        // Put multiple items
        queue.put(1);
        queue.put(2);
        queue.put(3);

        // Take them in order
        assertEquals(Integer.valueOf(1), queue.take().get());
        assertEquals(Integer.valueOf(2), queue.take().get());
        assertEquals(Integer.valueOf(3), queue.take().get());
    }

    @Test
    public void multipleTakesThenMultiplePutsCompletesInOrder() throws Exception {
        IterableAsyncQueue<Integer> queue = new IterableAsyncQueue<>();

        // Multiple takes when queue is empty
        CompletableFuture<Integer> future1 = queue.take();
        CompletableFuture<Integer> future2 = queue.take();
        CompletableFuture<Integer> future3 = queue.take();

        assertFalse(future1.isDone());
        assertFalse(future2.isDone());
        assertFalse(future3.isDone());

        // Put items - should complete futures in FIFO order
        queue.put(1);
        assertTrue("First future should be completed", future1.isDone());
        assertFalse("Second future should not be completed yet", future2.isDone());
        assertFalse("Third future should not be completed yet", future3.isDone());
        assertEquals(Integer.valueOf(1), future1.get());

        queue.put(2);
        assertTrue("Second future should be completed", future2.isDone());
        assertFalse("Third future should not be completed yet", future3.isDone());
        assertEquals(Integer.valueOf(2), future2.get());

        queue.put(3);
        assertTrue("Third future should be completed", future3.isDone());
        assertEquals(Integer.valueOf(3), future3.get());
    }

    @Test
    public void interleavedPutAndTakeOperations() throws Exception {
        IterableAsyncQueue<String> queue = new IterableAsyncQueue<>();

        // Put one
        queue.put("a");
        assertEquals("a", queue.take().get());

        // Take when empty, then put
        CompletableFuture<String> future = queue.take();
        assertFalse(future.isDone());
        queue.put("b");
        assertEquals("b", future.get());

        // Put multiple, take one, put one more, take remaining
        queue.put("c");
        queue.put("d");
        assertEquals("c", queue.take().get());
        queue.put("e");
        assertEquals("d", queue.take().get());
        assertEquals("e", queue.take().get());
    }

    @Test
    public void concurrentProducersAndConsumers() throws Exception {
        IterableAsyncQueue<Integer> queue = new IterableAsyncQueue<>();
        int itemCount = 1000;
        int producerThreads = 5;
        int consumerThreads = 5;

        ExecutorService executor = Executors.newFixedThreadPool(producerThreads + consumerThreads);
        CountDownLatch producerLatch = new CountDownLatch(producerThreads);
        CountDownLatch consumerLatch = new CountDownLatch(consumerThreads);

        List<Integer> consumedItems = new ArrayList<>();
        Object consumedLock = new Object();

        // Start producers
        for (int t = 0; t < producerThreads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < itemCount / producerThreads; i++) {
                        queue.put(threadId * 1000 + i);
                        Thread.sleep(1); // Small delay to encourage interleaving
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    producerLatch.countDown();
                }
            });
        }

        // Start consumers
        for (int t = 0; t < consumerThreads; t++) {
            executor.submit(() -> {
                try {
                    for (int i = 0; i < itemCount / consumerThreads; i++) {
                        Integer item = queue.take().get(5, TimeUnit.SECONDS);
                        synchronized (consumedLock) {
                            consumedItems.add(item);
                        }
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    consumerLatch.countDown();
                }
            });
        }

        // Wait for completion
        assertTrue("Producers should complete", producerLatch.await(10, TimeUnit.SECONDS));
        assertTrue("Consumers should complete", consumerLatch.await(10, TimeUnit.SECONDS));

        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        // Verify all items were consumed
        assertEquals("All items should be consumed", itemCount, consumedItems.size());
    }

    @Test
    public void singleProducerAndConsumer() throws Exception {
        IterableAsyncQueue<Integer> queue = new IterableAsyncQueue<>();
        int itemCount = 10000;

        AtomicInteger producedCount = new AtomicInteger(0);
        AtomicInteger consumedCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(2);

        // Producer
        CompletableFuture<Void> producer = CompletableFuture.runAsync(() -> {
            for (int i = 0; i < itemCount; i++) {
                queue.put(i);
                producedCount.incrementAndGet();
            }
        }, executor);

        // Consumer
        CompletableFuture<Void> consumer = CompletableFuture.runAsync(() -> {
            try {
                for (int i = 0; i < itemCount; i++) {
                    Integer item = queue.take().get(5, TimeUnit.SECONDS);
                    assertEquals(Integer.valueOf(i), item);
                    consumedCount.incrementAndGet();
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, executor);

        // Wait for both to complete
        CompletableFuture.allOf(producer, consumer).get(10, TimeUnit.SECONDS);

        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        assertEquals("All items should be produced", itemCount, producedCount.get());
        assertEquals("All items should be consumed", itemCount, consumedCount.get());
    }

    @Test
    public void multipleProducersSingleConsumer() throws Exception {
        IterableAsyncQueue<String> queue = new IterableAsyncQueue<>();
        int producersCount = 10;
        int itemsPerProducer = 100;
        int totalItems = producersCount * itemsPerProducer;

        ExecutorService executor = Executors.newFixedThreadPool(producersCount + 1);
        CountDownLatch producerLatch = new CountDownLatch(producersCount);

        // Start multiple producers
        for (int p = 0; p < producersCount; p++) {
            final int producerId = p;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < itemsPerProducer; i++) {
                        queue.put("producer-" + producerId + "-item-" + i);
                    }
                } finally {
                    producerLatch.countDown();
                }
            });
        }

        // Single consumer
        List<String> consumed = new ArrayList<>();
        CompletableFuture<Void> consumer = CompletableFuture.runAsync(() -> {
            try {
                for (int i = 0; i < totalItems; i++) {
                    String item = queue.take().get(5, TimeUnit.SECONDS);
                    consumed.add(item);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, executor);

        assertTrue("Producers should complete", producerLatch.await(10, TimeUnit.SECONDS));
        consumer.get(10, TimeUnit.SECONDS);

        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        assertEquals("Consumer should receive all items", totalItems, consumed.size());
    }

    @Test
    public void singleProducerMultipleConsumers() throws Exception {
        IterableAsyncQueue<Integer> queue = new IterableAsyncQueue<>();
        int consumersCount = 10;
        int totalItems = 1000;
        int itemsPerConsumer = totalItems / consumersCount;

        ExecutorService executor = Executors.newFixedThreadPool(consumersCount + 1);
        CountDownLatch consumerLatch = new CountDownLatch(consumersCount);

        List<Integer> allConsumed = new ArrayList<>();
        Object consumedLock = new Object();

        // Start multiple consumers
        for (int c = 0; c < consumersCount; c++) {
            executor.submit(() -> {
                try {
                    for (int i = 0; i < itemsPerConsumer; i++) {
                        Integer item = queue.take().get(5, TimeUnit.SECONDS);
                        synchronized (consumedLock) {
                            allConsumed.add(item);
                        }
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    consumerLatch.countDown();
                }
            });
        }

        // Single producer
        CompletableFuture<Void> producer = CompletableFuture.runAsync(() -> {
            for (int i = 0; i < totalItems; i++) {
                queue.put(i);
            }
        }, executor);

        producer.get(5, TimeUnit.SECONDS);
        assertTrue("Consumers should complete", consumerLatch.await(10, TimeUnit.SECONDS));

        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        assertEquals("All items should be consumed", totalItems, allConsumed.size());
    }

    @Test
    public void nullValuesAreSupported() throws Exception {
        IterableAsyncQueue<String> queue = new IterableAsyncQueue<>();

        queue.put(null);
        queue.put("not-null");
        queue.put(null);

        assertNull(queue.take().get());
        assertEquals("not-null", queue.take().get());
        assertNull(queue.take().get());
    }

    @Test
    public void takeCompletesAsynchronously() throws Exception {
        IterableAsyncQueue<String> queue = new IterableAsyncQueue<>();

        CompletableFuture<String> future = queue.take();
        AtomicInteger callbackInvoked = new AtomicInteger(0);

        // Attach callback
        future.thenAccept(item -> {
            assertEquals("async-item", item);
            callbackInvoked.incrementAndGet();
        });

        assertFalse("Future should not be completed yet", future.isDone());
        assertEquals(0, callbackInvoked.get());

        // Put item should trigger callback
        queue.put("async-item");

        // Give callback time to execute
        Thread.sleep(50);

        assertTrue("Future should be completed", future.isDone());
        assertEquals("Callback should have been invoked", 1, callbackInvoked.get());
    }
}
